package engine

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

// The HTTP-to-HTTPS bridge accepts HTTP only on loopback. It sends HTTPS to
// the configured DNS hostname while dialing its fixed IPv4 address strictly
// through the userspace WireGuard TCP stack (never through the Android route).
// The target DNS name is used for both SNI and certificate verification.
func newHTTPBridge(
	rule rule,
	dial func(context.Context, string, string) (net.Conn, error),
	tx, rx *atomic.Int64,
	active *atomic.Int64,
	recordError func(string),
) (*http.Server, *http.Transport) {
	destination := net.JoinHostPort(rule.TargetHost, strconv.Itoa(rule.TargetPort))
	targetURL := &url.URL{Scheme: "https", Host: rule.TLSHost}
	localAddress := rule.LocalAddress
	if localAddress == "" {
		localAddress = "127.0.0.1" // backwards compatibility with existing rules
	}
	localAuthority := net.JoinHostPort(localAddress, strconv.Itoa(rule.LocalPort))

	transport := &http.Transport{
		Proxy: nil,
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			ctx, cancel := context.WithTimeout(ctx, 12*time.Second)
			defer cancel()
			return dial(ctx, "tcp", destination)
		},
		TLSClientConfig: &tls.Config{
			ServerName: rule.TLSHost,
			MinVersion: tls.VersionTLS12,
			// Go verifies the certificate chain by default. Also require the
			// certificate to match the configured NPM hostname explicitly.
			VerifyConnection: func(cs tls.ConnectionState) error {
				if len(cs.PeerCertificates) == 0 {
					return errors.New("TLS peer certificate is missing")
				}
				return cs.PeerCertificates[0].VerifyHostname(rule.TLSHost)
			},
		},
		TLSHandshakeTimeout: 12 * time.Second,
		IdleConnTimeout:     90 * time.Second,
		MaxIdleConnsPerHost: 8,
	}
	proxy := &httputil.ReverseProxy{
		Transport: transport,
		Rewrite: func(request *httputil.ProxyRequest) {
			// SetURL preserves the incoming WebDAV method, path and query.
			request.SetURL(targetURL)
			request.Out.Host = rule.TLSHost // NPM must receive the expected Host
			request.Out.Header.Del("Forwarded")
			request.Out.Header.Del("X-Forwarded-For")
			request.Out.Header.Del("X-Forwarded-Host")
			request.Out.Header.Del("X-Forwarded-Proto")

			// WebDAV COPY/MOVE use Destination, often an absolute localhost URL.
			if original := request.Out.Header.Get("Destination"); original != "" {
				parsed, err := url.Parse(original)
				if err == nil && strings.EqualFold(parsed.Scheme, "http") &&
					(strings.EqualFold(parsed.Host, localAuthority) || strings.EqualFold(parsed.Host, "localhost:"+strconv.Itoa(rule.LocalPort))) {
					parsed.Scheme = "https"
					parsed.Host = rule.TLSHost
					request.Out.Header.Set("Destination", parsed.String())
				}
			}
		},
		ModifyResponse: func(response *http.Response) error {
			response.Body = &countedBody{ReadCloser: response.Body, count: rx}
			// Redirects to the internal-only HTTPS name must remain in this proxy.
			if location := response.Header.Get("Location"); location != "" {
				if parsed, err := url.Parse(location); err == nil && strings.EqualFold(parsed.Scheme, "https") &&
					strings.EqualFold(parsed.Hostname(), rule.TLSHost) && (parsed.Port() == "" || parsed.Port() == "443") {
					parsed.Scheme = "http"
					parsed.Host = localAuthority
					response.Header.Set("Location", parsed.String())
				}
			}
			return nil
		},
		ErrorHandler: func(w http.ResponseWriter, req *http.Request, err error) {
			recordError(fmt.Sprintf("%s HTTPS upstream error: %v", rule.Name, err))
			http.Error(w, "HTTPS upstream connection failed", http.StatusBadGateway)
		},
	}
	handler := http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		active.Add(1)
		defer active.Add(-1)
		if req.Body != nil {
			req.Body = &countedBody{ReadCloser: req.Body, count: tx}
		}
		proxy.ServeHTTP(&countedResponseWriter{ResponseWriter: w}, req)
	})
	return &http.Server{Handler: handler, ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 90 * time.Second}, transport
}

type countedBody struct {
	io.ReadCloser
	count *atomic.Int64
}

func (b *countedBody) Read(p []byte) (int, error) {
	n, err := b.ReadCloser.Read(p)
	b.count.Add(int64(n))
	return n, err
}

type countedResponseWriter struct{ http.ResponseWriter }

func (w *countedResponseWriter) Flush() {
	if flusher, ok := w.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
}
func (w *countedResponseWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }
