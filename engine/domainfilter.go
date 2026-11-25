package engine

import (
	"bufio"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strings"
	"time"
)

// maxAllowedDomains bounds the per-rule allow list.
const maxAllowedDomains = 50

// maxSniffBytes bounds how much of a connection is buffered while looking for the
// requested host. A TLS ClientHello or HTTP request head is far smaller than this.
const maxSniffBytes = 96 * 1024

// sniffTimeout is how long a client may take to send its ClientHello or HTTP request head.
const sniffTimeout = 10 * time.Second

var (
	errSniffTooLarge = errors.New("request head is too large")
	errSniffReadOnly = errors.New("connection is read-only while checking the domain")
	errSniffDone     = errors.New("client hello captured")
)

// normalizeDomainPatterns validates an allow list. Each entry is either an exact name
// ("example.com") or a wildcard ("*.example.com", any subdomain but not the name itself).
// Entries are lower-cased, a trailing dot is dropped, blanks and duplicates are removed.
func normalizeDomainPatterns(in []string) ([]string, error) {
	if len(in) > maxAllowedDomains {
		return nil, fmt.Errorf("at most %d allowed domains per rule", maxAllowedDomains)
	}
	out := make([]string, 0, len(in))
	seen := make(map[string]bool, len(in))
	for _, raw := range in {
		p := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(raw)), ".")
		if p == "" {
			continue
		}
		name := strings.TrimPrefix(p, "*.")
		if len(name) > 253 || !dnsName.MatchString(name) || numericAddress.MatchString(name) {
			return nil, fmt.Errorf("%q is not a domain name or *.domain pattern", strings.TrimSpace(raw))
		}
		if !seen[p] {
			seen[p] = true
			out = append(out, p)
		}
	}
	return out, nil
}

// domainAllowed reports whether host matches one of the normalized patterns.
func domainAllowed(host string, patterns []string) bool {
	host = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(host)), ".")
	if len(host) > 253 || !dnsName.MatchString(host) {
		return false
	}
	for _, p := range patterns {
		if suffix, wildcard := strings.CutPrefix(p, "*"); wildcard {
			if strings.HasSuffix(host, suffix) { // suffix is ".example.com"
				return true
			}
		} else if host == p {
			return true
		}
	}
	return false
}

// recorder keeps every byte read from the client so it can be replayed to the upstream
// server unchanged once the host has been checked.
type recorder struct {
	conn net.Conn
	data []byte
}

func (r *recorder) Read(p []byte) (int, error) {
	room := maxSniffBytes - len(r.data)
	if room <= 0 {
		return 0, errSniffTooLarge
	}
	if len(p) > room {
		p = p[:room]
	}
	n, err := r.conn.Read(p)
	r.data = append(r.data, p[:n]...)
	return n, err
}

// readOnlyConn lets crypto/tls parse a ClientHello without ever writing to the client.
type readOnlyConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c readOnlyConn) Read(p []byte) (int, error)  { return c.reader.Read(p) }
func (c readOnlyConn) Write(p []byte) (int, error) { return 0, errSniffReadOnly }

// sniffHost reads the start of a client connection and returns the host it asks for:
// the TLS SNI for a TLS connection, or the Host header for plain HTTP. It also returns
// every byte consumed from the client, which must be sent upstream before relaying.
// Nothing is decrypted or written to the client.
func sniffHost(client net.Conn, timeout time.Duration) (string, []byte, error) {
	if err := client.SetReadDeadline(time.Now().Add(timeout)); err != nil {
		return "", nil, err
	}
	defer client.SetReadDeadline(time.Time{})

	rec := &recorder{conn: client}
	br := bufio.NewReader(rec)
	first, err := br.Peek(1)
	if err != nil {
		return "", rec.data, err
	}
	if first[0] == 0x16 { // TLS handshake record
		var host string
		server := tls.Server(readOnlyConn{Conn: client, reader: br}, &tls.Config{
			GetConfigForClient: func(hello *tls.ClientHelloInfo) (*tls.Config, error) {
				host = hello.ServerName
				return nil, errSniffDone
			},
		})
		err := server.Handshake()
		if host == "" {
			if err == nil || errors.Is(err, errSniffDone) {
				err = errors.New("TLS client did not send a server name (SNI)")
			}
			return "", rec.data, err
		}
		return host, rec.data, nil
	}
	req, err := http.ReadRequest(br)
	if err != nil {
		return "", rec.data, fmt.Errorf("not a TLS or HTTP request: %w", err)
	}
	host := req.Host
	if h, _, splitErr := net.SplitHostPort(host); splitErr == nil {
		host = h
	}
	if host == "" {
		return "", rec.data, errors.New("HTTP request has no Host header")
	}
	return host, rec.data, nil
}
