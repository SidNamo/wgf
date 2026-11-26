// Package engine exposes userspace WireGuard TCP forwarders through gomobile.
//
// Several WireGuard profiles can run at the same time. Each running profile is a
// "tunnel" with its own WireGuard device, UDP socket and userspace network stack,
// so the Address/AllowedIPs of one profile never affect another. The engine never
// creates an Android VPN interface and only listens on loopback addresses.
package engine

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/go-ini/ini"
	"github.com/windtf/wireproxy"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type rule struct {
	Name         string `json:"name"`
	LocalAddress string `json:"localAddress"`
	LocalPort    int    `json:"localPort"`
	TargetHost   string `json:"targetHost"`
	TargetPort   int    `json:"targetPort"`
	Enabled      bool   `json:"enabled"`
	Mode         string `json:"mode"`    // tcp (default) or http-https
	TLSHost      string `json:"tlsHost"` // verified HTTPS name, not a URL
	// AllowedDomains limits a TCP rule to these names (exact or "*.domain"), checked
	// against the TLS SNI or HTTP Host header. Empty means every connection is forwarded.
	AllowedDomains []string `json:"allowedDomains"`
}

var dnsName = regexp.MustCompile(`(?i)^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$`)
var numericAddress = regexp.MustCompile(`^[0-9.]+$`)

const (
	// dnsErrorPrefix starts every error caused by a failed Endpoint host name lookup.
	// Such failures are usually temporary (right after a network change, or while
	// the phone has no network), so callers should retry instead of giving up.
	dnsErrorPrefix = "endpoint DNS lookup failed"
	dnsTimeout     = 10 * time.Second
	// A peer whose last handshake is older than this while we keep sending to it is
	// treated as unreachable and its Endpoint host name is looked up again (the
	// server's IP may have changed, e.g. dynamic DNS). Same threshold as
	// wireguard-tools' reresolve-dns.sh.
	staleHandshake  = 135 * time.Second
	monitorInterval = 30 * time.Second
	maxProfileID    = 128
)

type counterWriter struct {
	writer io.Writer
	count  *atomic.Int64
}

func (w counterWriter) Write(p []byte) (int, error) {
	n, err := w.writer.Write(p)
	w.count.Add(int64(n))
	return n, err
}

// peerEndpoint remembers the Endpoint of one [Peer] so it can be re-resolved later.
type peerEndpoint struct {
	publicKey string // hex, as used by the WireGuard UAPI
	host      string // DNS name from the config; empty for a literal IP endpoint
	port      string
	current   string // ip:port currently configured on the device
}

// tunnel is one running WireGuard profile and its loopback listeners.
type tunnel struct {
	id             string
	name           string
	dev            *device.Device
	tnet           *netstack.Net
	listenPort     int // configured [Interface] ListenPort, 0 when random
	endpointsMu    sync.Mutex
	endpoints      []*peerEndpoint
	listeners      []net.Listener
	httpServers    []*http.Server
	httpTransports []*http.Transport
	rules          map[string]rule
	httpActive     atomic.Int64
	mu             sync.Mutex
	active         map[net.Conn]net.Conn
	tx             atomic.Int64
	rx             atomic.Int64
	lastError      string
	closing        bool
	done           chan struct{}
}

var state = struct {
	sync.Mutex
	tunnels map[string]*tunnel
}{tunnels: make(map[string]*tunnel)}

// lookupHost resolves an Endpoint host name. On Android Go uses the system
// resolver, so Private DNS and per-network DNS servers are respected.
var lookupHost = func(ctx context.Context, host string) ([]netip.Addr, error) {
	return net.DefaultResolver.LookupNetIP(ctx, "ip", host)
}

type dnsError struct {
	host string
	err  error
}

func (e *dnsError) Error() string {
	return fmt.Sprintf("%s for %s: %v (network or DNS may not be ready yet)", dnsErrorPrefix, e.host, e.err)
}

// Retryable reports whether an error message returned by StartTunnel or Reconnect
// describes a temporary condition, such as DNS not being ready after a network
// change. The caller should try again later instead of treating the profile as broken.
func Retryable(message string) bool {
	return strings.Contains(message, dnsErrorPrefix)
}

// splitEndpoint parses "host:port" or "[v6]:port". host is empty for literal IPs.
func splitEndpoint(value string) (*peerEndpoint, error) {
	host, port, err := net.SplitHostPort(strings.ToLower(strings.TrimSpace(value)))
	if err != nil {
		return nil, err
	}
	if n, err := strconv.Atoi(port); err != nil || n < 1 || n > 65535 {
		return nil, errors.New("invalid port")
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		return &peerEndpoint{port: port, current: net.JoinHostPort(ip.Unmap().String(), port)}, nil
	}
	if host == "" {
		return nil, errors.New("missing host")
	}
	return &peerEndpoint{host: host, port: port}, nil
}

// resolveEndpoint looks up host and returns ip:port. It keeps prefer when that
// address is still among the answers (avoids flapping on round-robin DNS) and
// otherwise picks the first IPv4 address, like the standard WireGuard tools.
func resolveEndpoint(ctx context.Context, host, port, prefer string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, dnsTimeout)
	defer cancel()
	addrs, err := lookupHost(ctx, host)
	if err == nil && len(addrs) == 0 {
		err = errors.New("no addresses found")
	}
	if err != nil {
		return "", &dnsError{host: host, err: err}
	}
	var chosen netip.Addr
	for _, a := range addrs {
		a = a.Unmap()
		candidate := net.JoinHostPort(a.String(), port)
		if candidate == prefer {
			return candidate, nil
		}
		if !chosen.IsValid() || (a.Is4() && !chosen.Is4()) {
			chosen = a
		}
	}
	return net.JoinHostPort(chosen.String(), port), nil
}

// parseConfig parses a WireGuard .conf. Endpoint host names are resolved here,
// separately from the rest of [Peer], so that a temporary DNS failure is reported
// as such instead of looking like a broken configuration. Parse errors never
// include the offending value because it may be a private key.
func parseConfig(ctx context.Context, text string) (*wireproxy.Configuration, []*peerEndpoint, error) {
	opts := ini.LoadOptions{Insensitive: true, AllowShadows: true, AllowNonUniqueSections: true}
	cfg, err := ini.LoadSources(opts, []byte(text))
	if err != nil {
		return nil, nil, errors.New("invalid WireGuard config format")
	}
	result := &wireproxy.Configuration{
		Device:  &wireproxy.DeviceConfig{MTU: 1420},
		Resolve: &wireproxy.ResolveConfig{ResolveStrategy: "auto"},
	}
	// Keep keys in memory. Do not write plaintext .conf to Android storage.
	if err := wireproxy.ParseInterface(cfg, result.Device); err != nil {
		return nil, nil, errors.New("invalid [Interface] settings or private key")
	}
	if len(result.Device.Endpoint) == 0 {
		return nil, nil, errors.New("[Interface] Address is required")
	}
	sections, err := cfg.SectionsByName("Peer")
	if err != nil || len(sections) == 0 {
		return nil, nil, errors.New("at least one [Peer] is required")
	}
	endpoints := make([]*peerEndpoint, len(sections))
	for i, section := range sections {
		if !section.HasKey("Endpoint") {
			continue
		}
		key := section.Key("Endpoint")
		ep, err := splitEndpoint(key.String())
		if err != nil {
			return nil, nil, errors.New("invalid [Peer] Endpoint: expected host:port")
		}
		if ep.host != "" {
			if ep.current, err = resolveEndpoint(ctx, ep.host, ep.port, ""); err != nil {
				return nil, nil, err
			}
		}
		key.SetValue(ep.current) // ParsePeers now only sees a literal IP
		endpoints[i] = ep
	}
	if err := wireproxy.ParsePeers(cfg, &result.Device.Peers); err != nil {
		return nil, nil, errors.New("invalid [Peer] settings or keys")
	}
	var tracked []*peerEndpoint
	for i, peer := range result.Device.Peers {
		if i < len(endpoints) && endpoints[i] != nil {
			endpoints[i].publicKey = peer.PublicKey
			tracked = append(tracked, endpoints[i])
		}
	}
	return result, tracked, nil
}

func validateRules(raw string) ([]rule, error) {
	var rules []rule
	if err := json.Unmarshal([]byte(raw), &rules); err != nil {
		return nil, errors.New("invalid forwarding rules JSON")
	}
	seen := make(map[string]bool)
	enabled := make([]rule, 0, len(rules))
	for _, r := range rules {
		if !r.Enabled {
			continue
		}
		localAddress, err := validateLocalAddress(r.LocalAddress)
		if err != nil {
			return nil, fmt.Errorf("invalid local bind IP in rule %q: %w", r.Name, err)
		}
		r.LocalAddress = localAddress
		if (r.LocalPort != 443 && (r.LocalPort < 1024 || r.LocalPort > 65535)) || r.TargetPort < 1 || r.TargetPort > 65535 {
			return nil, fmt.Errorf("invalid TCP port in rule %q", r.Name)
		}
		ip, err := netip.ParseAddr(strings.TrimSpace(r.TargetHost))
		if err != nil || !ip.Is4() || ip.IsLoopback() || ip.IsUnspecified() || ip.IsMulticast() {
			return nil, fmt.Errorf("target must be an IPv4 address in rule %q", r.Name)
		}
		if r.Mode == "" {
			r.Mode = "tcp" // backwards compatibility with existing rules
		}
		if r.Mode != "tcp" && r.Mode != "http-https" {
			return nil, fmt.Errorf("invalid forwarding mode in rule %q", r.Name)
		}
		if r.Mode == "http-https" {
			r.TLSHost = strings.TrimSpace(r.TLSHost)
			if len(r.TLSHost) > 253 || !dnsName.MatchString(r.TLSHost) || numericAddress.MatchString(r.TLSHost) {
				return nil, fmt.Errorf("HTTPS domain must be a valid DNS name in rule %q", r.Name)
			}
		}
		domains, err := normalizeDomainPatterns(r.AllowedDomains)
		if err != nil {
			return nil, fmt.Errorf("invalid allowed domain in rule %q: %w", r.Name, err)
		}
		if r.Mode == "http-https" && len(domains) > 0 {
			return nil, fmt.Errorf("allowed domains apply only to TCP rules, not HTTP -> HTTPS (rule %q)", r.Name)
		}
		r.AllowedDomains = domains
		key := listenerKey(r.LocalAddress, r.LocalPort)
		if seen[key] {
			return nil, fmt.Errorf("duplicate local listener: %s", key)
		}
		seen[key] = true
		enabled = append(enabled, r)
	}
	if len(enabled) == 0 {
		return nil, errors.New("enable at least one forwarding rule")
	}
	return enabled, nil
}

func validateLocalAddress(address string) (string, error) {
	address = strings.TrimSpace(address)
	if address == "" {
		address = "127.0.0.1" // backwards compatibility with existing rules
	}
	ip, err := netip.ParseAddr(address)
	if err != nil || !ip.Is4() || !ip.IsLoopback() {
		return "", errors.New("local bind IP must be an IPv4 loopback address (127.0.0.0/8)")
	}
	return ip.String(), nil
}

func listenerKey(address string, port int) string {
	return net.JoinHostPort(address, fmt.Sprintf("%d", port))
}

func validateID(id string) (string, error) {
	id = strings.TrimSpace(id)
	if id == "" || len(id) > maxProfileID {
		return "", errors.New("invalid profile id")
	}
	return id, nil
}

// checkConflictsLocked rejects listeners or a ListenPort already used by another
// running profile, with a message that names that profile. state must be locked.
func checkConflictsLocked(id string, rules []rule, listenPort int) error {
	for otherID, other := range state.tunnels {
		if otherID == id {
			continue
		}
		for _, r := range rules {
			key := listenerKey(r.LocalAddress, r.LocalPort)
			if _, used := other.rules[key]; used {
				return fmt.Errorf("local %s is already used by running profile %q; use another 127.x.x.x address or port", key, other.name)
			}
		}
		if listenPort != 0 && other.listenPort == listenPort {
			return fmt.Errorf("[Interface] ListenPort %d is already used by running profile %q", listenPort, other.name)
		}
	}
	return nil
}

// startDevice creates a WireGuard device on its own userspace network stack.
func startDevice(conf *wireproxy.Configuration) (*device.Device, *netstack.Net, error) {
	setting, err := wireproxy.CreateIPCRequest(conf.Device)
	if err != nil {
		return nil, nil, errors.New("invalid WireGuard settings")
	}
	tunDev, tnet, err := netstack.CreateNetTUN(setting.DeviceAddr, setting.DNS, setting.MTU)
	if err != nil {
		return nil, nil, errors.New("could not create the userspace network stack")
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, ""))
	if err := dev.IpcSet(setting.IpcRequest); err != nil {
		dev.Close()
		return nil, nil, errors.New("invalid WireGuard settings or keys")
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		if conf.Device.ListenPort != nil && *conf.Device.ListenPort != 0 {
			return nil, nil, fmt.Errorf("could not open WireGuard UDP port %d (in use?): %w", *conf.Device.ListenPort, err)
		}
		return nil, nil, fmt.Errorf("could not open WireGuard UDP socket: %w", err)
	}
	return dev, tnet, nil
}

// StartTunnel starts the WireGuard profile id with its loopback TCP listeners. If
// the profile is already running it is replaced, but only after the new
// configuration has been parsed and its Endpoint resolved, so a DNS failure keeps
// the running tunnel untouched. It returns once the listeners are open; whether the
// server answers is checked separately (ProbeTunnel, handshake time in Stats).
func StartTunnel(id, name, configText, routesJSON string) error {
	id, err := validateID(id)
	if err != nil {
		return err
	}
	rules, err := validateRules(routesJSON)
	if err != nil {
		return err
	}
	conf, endpoints, err := parseConfig(context.Background(), configText)
	if err != nil {
		return err
	}
	listenPort := 0
	if conf.Device.ListenPort != nil {
		listenPort = *conf.Device.ListenPort
	}

	state.Lock()
	defer state.Unlock()
	if old := state.tunnels[id]; old != nil {
		delete(state.tunnels, id)
		old.close() // frees its listeners before the new ones are opened
	}
	if err := checkConflictsLocked(id, rules, listenPort); err != nil {
		return err
	}
	dev, tnet, err := startDevice(conf)
	if err != nil {
		return err
	}
	t := &tunnel{
		id: id, name: name, dev: dev, tnet: tnet, listenPort: listenPort, endpoints: endpoints,
		active: make(map[net.Conn]net.Conn), rules: make(map[string]rule), done: make(chan struct{}),
	}
	for _, r := range rules {
		address := listenerKey(r.LocalAddress, r.LocalPort)
		l, listenErr := net.Listen("tcp", address)
		if listenErr != nil {
			t.close()
			if r.LocalPort == 443 && (errors.Is(listenErr, syscall.EACCES) || errors.Is(listenErr, syscall.EPERM)) {
				return fmt.Errorf("Android denied local TCP port 443 on %s; use a port such as 8443 (and include :8443 in the URL): %w", r.LocalAddress, listenErr)
			}
			return fmt.Errorf("could not listen on %s: %w", address, listenErr)
		}
		t.listeners = append(t.listeners, l)
		t.rules[address] = r
	}
	state.tunnels[id] = t
	for i, l := range t.listeners {
		r := rules[i]
		if r.Mode == "http-https" {
			server, transport := newHTTPBridge(r, t.tnet.DialContext, &t.tx, &t.rx, &t.httpActive, t.setError)
			t.httpServers = append(t.httpServers, server)
			t.httpTransports = append(t.httpTransports, transport)
			go func() { _ = server.Serve(l) }()
		} else {
			go t.accept(l, r)
		}
	}
	go t.monitor()
	return nil
}

func lookup(id string) *tunnel {
	state.Lock()
	defer state.Unlock()
	return state.tunnels[strings.TrimSpace(id)]
}

// IsRunning reports whether profile id currently has a running tunnel.
func IsRunning(id string) bool { return lookup(id) != nil }

// StopTunnel closes the listeners, active connections and WireGuard device of profile id.
func StopTunnel(id string) {
	state.Lock()
	t := state.tunnels[strings.TrimSpace(id)]
	delete(state.tunnels, strings.TrimSpace(id))
	state.Unlock()
	if t != nil {
		t.close()
	}
}

// StopAll stops every running profile.
func StopAll() {
	state.Lock()
	all := state.tunnels
	state.tunnels = make(map[string]*tunnel)
	state.Unlock()
	for _, t := range all {
		t.close()
	}
}

// Reconnect is called after the phone's network changed. Unlike a restart it keeps
// the tunnel, its listeners and every forwarded TCP connection: it looks up the
// Endpoint host names again, reopens the WireGuard UDP socket on the new network and
// sends keepalives so the server learns the new address right away. A DNS failure
// keeps the previous server address and is returned as a Retryable error.
func Reconnect(id string) error {
	t := lookup(id)
	if t == nil {
		return errors.New("profile is not running")
	}
	dnsErr := t.refreshEndpoints(context.Background(), func(*peerEndpoint) bool { return true })
	if err := t.dev.BindUpdate(); err != nil {
		// The old port may not be reusable on the new network; retry on a random port.
		if err := t.dev.BindUpdate(); err != nil {
			t.setError("could not reopen WireGuard UDP socket: " + err.Error())
			return fmt.Errorf("could not reopen WireGuard UDP socket: %w", err)
		}
	}
	t.dev.SendKeepalivesToPeersWithCurrentKeypair()
	return dnsErr
}

// refreshEndpoints re-resolves host name endpoints selected by want and updates
// the device when a server's address changed.
func (t *tunnel) refreshEndpoints(ctx context.Context, want func(*peerEndpoint) bool) error {
	t.endpointsMu.Lock()
	defer t.endpointsMu.Unlock()
	var firstErr error
	for _, ep := range t.endpoints {
		if ep.host == "" || !want(ep) {
			continue
		}
		addr, err := resolveEndpoint(ctx, ep.host, ep.port, ep.current)
		if err == nil && addr != ep.current {
			err = t.dev.IpcSet(fmt.Sprintf("public_key=%s\nupdate_only=true\nendpoint=%s\n", ep.publicKey, addr))
			if err == nil {
				ep.current = addr
			}
		}
		if err != nil && firstErr == nil {
			firstErr = err
		}
	}
	if firstErr != nil {
		t.setError(firstErr.Error())
	}
	return firstErr
}

type peerInfo struct {
	publicKey     string
	endpoint      string
	lastHandshake int64 // unix seconds, 0 = never
	txBytes       uint64
}

// peers reads the runtime state of every peer. The UAPI dump also contains the
// private key, which is skipped and never leaves this function.
func (t *tunnel) peers() []peerInfo {
	dump, err := t.dev.IpcGet()
	if err != nil {
		return nil
	}
	var out []peerInfo
	scanner := bufio.NewScanner(strings.NewReader(dump))
	for scanner.Scan() {
		key, value, ok := strings.Cut(scanner.Text(), "=")
		if !ok {
			continue
		}
		if key == "public_key" {
			out = append(out, peerInfo{publicKey: value})
			continue
		}
		if len(out) == 0 {
			continue // device-level keys, including private_key
		}
		p := &out[len(out)-1]
		switch key {
		case "endpoint":
			p.endpoint = value
		case "last_handshake_time_sec":
			p.lastHandshake, _ = strconv.ParseInt(value, 10, 64)
		case "tx_bytes":
			p.txBytes, _ = strconv.ParseUint(value, 10, 64)
		}
	}
	return out
}

// monitor re-resolves the Endpoint of a peer that we keep sending to but that has
// not completed a handshake for staleHandshake, which is what happens when a
// dynamic-DNS server changed its IP while the tunnel was running. Idle tunnels
// cause no DNS traffic.
func (t *tunnel) monitor() {
	ticker := time.NewTicker(monitorInterval)
	defer ticker.Stop()
	lastTx := make(map[string]uint64)
	for {
		select {
		case <-t.done:
			return
		case <-ticker.C:
		}
		now := time.Now().Unix()
		stale := make(map[string]bool)
		for _, p := range t.peers() {
			sending := p.txBytes > lastTx[p.publicKey]
			lastTx[p.publicKey] = p.txBytes
			if sending && now-p.lastHandshake > int64(staleHandshake/time.Second) {
				stale[p.publicKey] = true
			}
		}
		if len(stale) > 0 {
			_ = t.refreshEndpoints(context.Background(), func(ep *peerEndpoint) bool { return stale[ep.publicKey] })
		}
	}
}

func (t *tunnel) accept(listener net.Listener, r rule) {
	for {
		client, err := listener.Accept()
		if err != nil {
			return
		} // listener closed on Stop
		go t.forward(client, r)
	}
}

func (t *tunnel) forward(client net.Conn, r rule) {
	// With an allow list, read the requested host first and only open the tunnel
	// connection for an allowed name. The bytes read are replayed unchanged.
	var preface []byte
	if len(r.AllowedDomains) > 0 {
		host, consumed, err := sniffHost(client, sniffTimeout)
		if err != nil || !domainAllowed(host, r.AllowedDomains) {
			if host == "" {
				t.setError(fmt.Sprintf("%s: blocked a connection with no domain name: %v", r.Name, err))
			} else {
				t.setError(fmt.Sprintf("%s: blocked %s (not in allowed domains)", r.Name, host))
			}
			_ = client.Close()
			return
		}
		preface = consumed
	}
	target := net.JoinHostPort(r.TargetHost, fmt.Sprintf("%d", r.TargetPort))
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	upstream, err := t.tnet.DialContext(ctx, "tcp", target)
	cancel()
	if err != nil {
		t.setError(fmt.Sprintf("%s (%s): %v", r.Name, target, err))
		_ = client.Close()
		return
	}
	if len(preface) > 0 {
		n, err := upstream.Write(preface)
		t.tx.Add(int64(n))
		if err != nil {
			t.setError(fmt.Sprintf("%s (%s): %v", r.Name, target, err))
			_ = upstream.Close()
			_ = client.Close()
			return
		}
	}
	t.mu.Lock()
	if t.closing {
		t.mu.Unlock()
		_ = client.Close()
		_ = upstream.Close()
		return
	}
	t.active[client] = upstream
	t.mu.Unlock()

	// Preserve TCP half-close so HTTP uploads/downloads are not prematurely cut.
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(counterWriter{upstream, &t.tx}, client)
		if c, ok := upstream.(interface{ CloseWrite() error }); ok {
			_ = c.CloseWrite()
		}
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(counterWriter{client, &t.rx}, upstream)
		if c, ok := client.(interface{ CloseWrite() error }); ok {
			_ = c.CloseWrite()
		}
	}()
	wg.Wait()
	_ = upstream.Close()
	_ = client.Close()
	t.mu.Lock()
	delete(t.active, client)
	t.mu.Unlock()
}

func (t *tunnel) setError(message string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if !t.closing {
		t.lastError = message
	}
}

func (t *tunnel) close() {
	t.mu.Lock()
	if t.closing {
		t.mu.Unlock()
		return
	}
	t.closing = true
	close(t.done)
	for client, upstream := range t.active {
		_ = client.Close()
		_ = upstream.Close()
	}
	t.mu.Unlock()
	for _, server := range t.httpServers {
		_ = server.Close()
	}
	for _, transport := range t.httpTransports {
		transport.CloseIdleConnections()
	}
	for _, l := range t.listeners {
		_ = l.Close()
	}
	if t.dev != nil {
		t.dev.Close()
	}
}

// ProbeTunnel attempts an actual TCP connection through profile id for the rule
// listening on localAddress:localPort (and a verified TLS handshake for HTTPS rules).
func ProbeTunnel(id, localAddress string, localPort int) error {
	address, err := validateLocalAddress(localAddress)
	if err != nil {
		return err
	}
	t := lookup(id)
	if t == nil {
		return errors.New("profile is not running")
	}
	r, ok := t.rules[listenerKey(address, localPort)]
	if !ok {
		return errors.New("unknown local listener")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	c, err := t.tnet.DialContext(ctx, "tcp", net.JoinHostPort(r.TargetHost, fmt.Sprintf("%d", r.TargetPort)))
	if err != nil {
		return fmt.Errorf("TCP probe failed: %w", err)
	}
	if r.Mode == "http-https" {
		// A TCP connection alone cannot confirm the server certificate or SNI.
		tlsConn := tls.Client(c, &tls.Config{ServerName: r.TLSHost, MinVersion: tls.VersionTLS12})
		defer tlsConn.Close()
		if err := tlsConn.HandshakeContext(ctx); err != nil {
			return fmt.Errorf("HTTPS certificate/handshake failed: %w", err)
		}
		return nil
	}
	return c.Close()
}

type tunnelStats struct {
	Name      string `json:"name"`
	TX        int64  `json:"tx"`
	RX        int64  `json:"rx"`
	Active    int    `json:"active"`
	Error     string `json:"error"`
	Handshake int64  `json:"handshake"` // newest peer handshake, unix seconds, 0 = none yet
	Endpoint  string `json:"endpoint"`  // server address currently used (first peer)
}

// Stats returns a JSON object keyed by profile id with the state of every running
// tunnel. It never includes keys.
func Stats() string {
	state.Lock()
	all := make([]*tunnel, 0, len(state.tunnels))
	for _, t := range state.tunnels {
		all = append(all, t)
	}
	state.Unlock()
	sort.Slice(all, func(i, j int) bool { return all[i].id < all[j].id })
	doc := make(map[string]tunnelStats, len(all))
	for _, t := range all {
		t.mu.Lock()
		s := tunnelStats{Name: t.name, Active: len(t.active) + int(t.httpActive.Load()), Error: t.lastError}
		t.mu.Unlock()
		s.TX, s.RX = t.tx.Load(), t.rx.Load()
		for i, p := range t.peers() {
			if i == 0 {
				s.Endpoint = p.endpoint
			}
			if p.lastHandshake > s.Handshake {
				s.Handshake = p.lastHandshake
			}
		}
		doc[t.id] = s
	}
	out, _ := json.Marshal(doc)
	return string(out)
}
