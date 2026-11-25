// Package engine exposes a userspace WireGuard TCP forwarder through gomobile.
// It never creates an Android VPN interface and only listens on loopback.
package engine

import (
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
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/go-ini/ini"
	"github.com/windtf/wireproxy"
	"golang.zx2c4.com/wireguard/device"
)

type rule struct {
	Name       string `json:"name"`
	LocalPort  int    `json:"localPort"`
	TargetHost string `json:"targetHost"`
	TargetPort int    `json:"targetPort"`
	Enabled    bool   `json:"enabled"`
	Mode       string `json:"mode"`    // tcp (default) or http-https
	TLSHost    string `json:"tlsHost"` // verified HTTPS name, not a URL
}

var dnsName = regexp.MustCompile(`(?i)^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$`)
var numericAddress = regexp.MustCompile(`^[0-9.]+$`)

type counterWriter struct {
	writer io.Writer
	count  *atomic.Int64
}

func (w counterWriter) Write(p []byte) (int, error) {
	n, err := w.writer.Write(p)
	w.count.Add(int64(n))
	return n, err
}

type session struct {
	tunnel         *wireproxy.VirtualTun
	listeners      []net.Listener
	httpServers    []*http.Server
	httpTransports []*http.Transport
	rules          map[int]rule
	httpActive     atomic.Int64
	mu             sync.Mutex
	active         map[net.Conn]net.Conn
	tx             atomic.Int64
	rx             atomic.Int64
	lastError      string
	closing        bool
}

var state = struct {
	sync.Mutex
	current *session
}{}

func parseConfig(text string) (*wireproxy.Configuration, error) {
	opts := ini.LoadOptions{Insensitive: true, AllowShadows: true, AllowNonUniqueSections: true}
	cfg, err := ini.LoadSources(opts, []byte(text))
	if err != nil {
		return nil, errors.New("invalid WireGuard config format")
	}
	result := &wireproxy.Configuration{
		Device:  &wireproxy.DeviceConfig{MTU: 1420},
		Resolve: &wireproxy.ResolveConfig{ResolveStrategy: "auto"},
	}
	// Keep keys in memory. Do not write plaintext .conf to Android storage.
	if err := wireproxy.ParseInterface(cfg, result.Device); err != nil {
		return nil, errors.New("invalid [Interface] settings or private key")
	}
	if err := wireproxy.ParsePeers(cfg, &result.Device.Peers); err != nil {
		return nil, errors.New("invalid [Peer] settings, keys, or endpoint")
	}
	return result, nil
}

func validateRules(raw string) ([]rule, error) {
	var rules []rule
	if err := json.Unmarshal([]byte(raw), &rules); err != nil {
		return nil, errors.New("invalid forwarding rules JSON")
	}
	seen := make(map[int]bool)
	enabled := make([]rule, 0, len(rules))
	for _, r := range rules {
		if !r.Enabled {
			continue
		}
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
		if seen[r.LocalPort] {
			return nil, fmt.Errorf("duplicate local port: %d", r.LocalPort)
		}
		seen[r.LocalPort] = true
		enabled = append(enabled, r)
	}
	if len(enabled) == 0 {
		return nil, errors.New("enable at least one forwarding rule")
	}
	return enabled, nil
}

// Start starts WireGuard and all loopback TCP listeners. It returns only after
// listeners have been successfully opened. The actual peer TCP handshake is
// tested separately with Probe, not assumed to have occurred here.
func Start(configText, routesJSON string) error {
	rules, err := validateRules(routesJSON)
	if err != nil {
		return err
	}
	conf, err := parseConfig(configText)
	if err != nil {
		return err
	}

	state.Lock()
	defer state.Unlock()
	if state.current != nil {
		return errors.New("forwarder is already running")
	}

	vt, err := wireproxy.StartWireguard(conf, device.LogLevelError)
	if err != nil {
		return errors.New("could not initialize WireGuard engine")
	}
	s := &session{tunnel: vt, active: make(map[net.Conn]net.Conn), rules: make(map[int]rule)}
	for _, r := range rules {
		l, listenErr := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", r.LocalPort))
		if listenErr != nil {
			s.close()
			if r.LocalPort == 443 && (errors.Is(listenErr, syscall.EACCES) || errors.Is(listenErr, syscall.EPERM)) {
				return fmt.Errorf("Android denied local TCP port 443; use a port such as 8443 (and include :8443 in the URL): %w", listenErr)
			}
			return fmt.Errorf("could not listen on 127.0.0.1:%d: %w", r.LocalPort, listenErr)
		}
		s.listeners = append(s.listeners, l)
		s.rules[r.LocalPort] = r
	}
	state.current = s
	for i, l := range s.listeners {
		r := rules[i]
		if r.Mode == "http-https" {
			server, transport := newHTTPBridge(r, s.tunnel.Tnet.DialContext,
				&s.tx, &s.rx, &s.httpActive, s.setError)
			s.httpServers = append(s.httpServers, server)
			s.httpTransports = append(s.httpTransports, transport)
			go func() { _ = server.Serve(l) }()
		} else {
			go s.accept(l, r)
		}
	}
	return nil
}

func (s *session) accept(listener net.Listener, r rule) {
	for {
		client, err := listener.Accept()
		if err != nil {
			return
		} // listener closed on Stop
		go s.forward(client, r)
	}
}

func (s *session) forward(client net.Conn, r rule) {
	target := net.JoinHostPort(r.TargetHost, fmt.Sprintf("%d", r.TargetPort))
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	upstream, err := s.tunnel.Tnet.DialContext(ctx, "tcp", target)
	cancel()
	if err != nil {
		s.setError(fmt.Sprintf("%s (%s): %v", r.Name, target, err))
		_ = client.Close()
		return
	}
	s.mu.Lock()
	if s.closing {
		s.mu.Unlock()
		_ = client.Close()
		_ = upstream.Close()
		return
	}
	s.active[client] = upstream
	s.mu.Unlock()

	// Preserve TCP half-close so HTTP uploads/downloads are not prematurely cut.
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(counterWriter{upstream, &s.tx}, client)
		if c, ok := upstream.(interface{ CloseWrite() error }); ok {
			_ = c.CloseWrite()
		}
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(counterWriter{client, &s.rx}, upstream)
		if c, ok := client.(interface{ CloseWrite() error }); ok {
			_ = c.CloseWrite()
		}
	}()
	wg.Wait()
	_ = upstream.Close()
	_ = client.Close()
	s.mu.Lock()
	delete(s.active, client)
	s.mu.Unlock()
}

func (s *session) setError(message string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.closing {
		s.lastError = message
	}
}

func (s *session) close() {
	s.mu.Lock()
	s.closing = true
	for client, upstream := range s.active {
		_ = client.Close()
		_ = upstream.Close()
	}
	s.mu.Unlock()
	for _, server := range s.httpServers {
		_ = server.Close()
	}
	for _, transport := range s.httpTransports {
		transport.CloseIdleConnections()
	}
	for _, l := range s.listeners {
		_ = l.Close()
	}
	if s.tunnel != nil {
		s.tunnel.Dev.Close()
	}
}

// Stop closes all listeners, active connections, and the WireGuard device.
func Stop() {
	state.Lock()
	s := state.current
	state.current = nil
	state.Unlock()
	if s != nil {
		s.close()
	}
}

// Probe attempts an actual TCP connection to the selected WireGuard destination.
// Unlike Start, a successful Probe confirms that the peer route is reachable.
func Probe(localPort int) error {
	state.Lock()
	s := state.current
	state.Unlock()
	if s == nil {
		return errors.New("forwarder is not running")
	}
	r, ok := s.rules[localPort]
	if !ok {
		return errors.New("unknown local port")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	c, err := s.tunnel.Tnet.DialContext(ctx, "tcp", net.JoinHostPort(r.TargetHost, fmt.Sprintf("%d", r.TargetPort)))
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

// Stats returns JSON with runtime state and byte counters. It does not expose keys.
func Stats() string {
	state.Lock()
	s := state.current
	state.Unlock()
	if s == nil {
		return `{"running":false,"tx":0,"rx":0,"active":0,"error":""}`
	}
	s.mu.Lock()
	active := len(s.active) + int(s.httpActive.Load())
	last := s.lastError
	s.mu.Unlock()
	doc := struct {
		Running bool   `json:"running"`
		TX      int64  `json:"tx"`
		RX      int64  `json:"rx"`
		Active  int    `json:"active"`
		Error   string `json:"error"`
	}{true, s.tx.Load(), s.rx.Load(), active, last}
	out, _ := json.Marshal(doc)
	return string(out)
}
