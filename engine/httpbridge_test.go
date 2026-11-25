package engine

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestHTTPBridgeWebDAV(t *testing.T) {
	const body = "example upload payload"
	backend := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Host != "example.com" {
			t.Errorf("unexpected Host: %s", req.Host)
		}
		if req.URL.Path != "/dav/demo.txt" {
			t.Errorf("unexpected path: %s", req.URL.Path)
		}
		if req.Header.Get("Authorization") != "Basic example" {
			t.Error("authorization header missing")
		}
		if req.Method == "MOVE" && req.Header.Get("Destination") != "https://example.com/dav/new.txt" {
			t.Errorf("unexpected WebDAV Destination: %s", req.Header.Get("Destination"))
		}
		data, _ := io.ReadAll(req.Body)
		if string(data) != body {
			t.Errorf("unexpected request body: %q", data)
		}
		w.Header().Set("Location", "https://example.com/dav/new.txt")
		w.WriteHeader(http.StatusMultiStatus)
		_, _ = w.Write([]byte("done"))
	}))
	defer backend.Close()
	pool := x509.NewCertPool()
	pool.AddCert(backend.Certificate())
	rule := rule{Name: "dav", LocalPort: 5212, TargetHost: "192.168.0.230", TargetPort: 443, TLSHost: "example.com", Mode: "http-https", Enabled: true}
	var sent, received, active atomic.Int64
	var errorMessage string
	server, transport := newHTTPBridge(rule, func(ctx context.Context, network, address string) (net.Conn, error) {
		if address != "192.168.0.230:443" {
			t.Errorf("dial not pinned to internal IP: %s", address)
		}
		return (&net.Dialer{}).DialContext(ctx, "tcp", backend.Listener.Addr().String())
	}, &sent, &received, &active, func(s string) { errorMessage = s })
	defer transport.CloseIdleConnections()
	transport.TLSClientConfig.RootCAs = pool
	client := httptest.NewRecorder()
	req := httptest.NewRequest("MOVE", "http://127.0.0.1:5212/dav/demo.txt", strings.NewReader(body))
	req.Header.Set("Authorization", "Basic example")
	req.Header.Set("Destination", "http://127.0.0.1:5212/dav/new.txt")
	server.Handler.ServeHTTP(client, req)
	if client.Code != http.StatusMultiStatus {
		t.Fatalf("upstream status=%d; error=%s", client.Code, errorMessage)
	}
	if got := client.Header().Get("Location"); got != "http://127.0.0.1:5212/dav/new.txt" {
		t.Errorf("redirect not localized: %s", got)
	}
	if got := client.Body.String(); got != "done" {
		t.Errorf("response body: %s", got)
	}
	if sent.Load() != int64(len(body)) || received.Load() != 4 {
		t.Errorf("bytes sent/received: %d / %d", sent.Load(), received.Load())
	}
	if active.Load() != 0 {
		t.Error("request still marked active")
	}
}

func TestHTTPBridgeTLSVerification(t *testing.T) {
	backend := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("should not be accepted"))
	}))
	defer backend.Close()
	rule := rule{Name: "dav", LocalPort: 5212, TargetHost: "192.168.0.230", TargetPort: 443, TLSHost: "wrong.example.com", Mode: "http-https", Enabled: true}
	var tx, rx, active atomic.Int64
	var errorText string
	server, transport := newHTTPBridge(rule, func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "tcp", backend.Listener.Addr().String())
	}, &tx, &rx, &active, func(msg string) { errorText = msg })
	defer transport.CloseIdleConnections()
	pool := x509.NewCertPool()
	pool.AddCert(backend.Certificate())
	transport.TLSClientConfig.RootCAs = pool
	recorder := httptest.NewRecorder()
	server.Handler.ServeHTTP(recorder, httptest.NewRequest("GET", "http://127.0.0.1:5212/dav/", nil))
	if recorder.Code != http.StatusBadGateway {
		t.Fatalf("expected rejection (502), got %d", recorder.Code)
	}
	if !strings.Contains(errorText, "certificate") {
		t.Errorf("certificate failure not reported: %q", errorText)
	}
}

func TestHTTPBridgeUsesNPMSNI(t *testing.T) {
	sni := make(chan string, 1)
	backend := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "example.com" {
			t.Errorf("NPM host routing received %q, want example.com", r.Host)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	backend.TLS = &tls.Config{GetConfigForClient: func(hello *tls.ClientHelloInfo) (*tls.Config, error) {
		sni <- hello.ServerName
		return nil, nil
	}}
	backend.StartTLS()
	defer backend.Close()

	pool := x509.NewCertPool()
	pool.AddCert(backend.Certificate())
	r := rule{Name: "NPM", LocalPort: 5213, TargetHost: "192.168.0.230", TargetPort: 443,
		TLSHost: "example.com", Mode: "http-https", Enabled: true}
	var sent, received, active atomic.Int64
	server, transport := newHTTPBridge(r, func(ctx context.Context, _, address string) (net.Conn, error) {
		if address != "192.168.0.230:443" {
			t.Errorf("unexpected WireGuard destination: %s", address)
		}
		return (&net.Dialer{}).DialContext(ctx, "tcp", backend.Listener.Addr().String())
	}, &sent, &received, &active, func(message string) { t.Error(message) })
	defer transport.CloseIdleConnections()
	transport.TLSClientConfig.RootCAs = pool

	recorder := httptest.NewRecorder()
	server.Handler.ServeHTTP(recorder, httptest.NewRequest("GET", "http://127.0.0.1:5213/dav", nil))
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("unexpected response: %d", recorder.Code)
	}
	select {
	case receivedName := <-sni:
		if receivedName != "example.com" {
			t.Errorf("unexpected TLS SNI: %s", receivedName)
		}
	default:
		t.Fatal("no TLS SNI observed")
	}
}
