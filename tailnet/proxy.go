// MG4 Streams: loopback HTTP proxy over the embedded Tailscale node.

package mg4tailnet

import (
	"bufio"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"
)

type dialFunc func(ctx context.Context, network, address string) (net.Conn, error)

// proxy is a small HTTP/1.1 forward proxy on 127.0.0.1: CONNECT tunnels for HTTPS, and plain
// HTTP requests forwarded one per connection. Responses are copied byte for byte, so endless
// Shoutcast/Icecast streams (including "ICY 200 OK" replies) pass through unchanged.
//
// It has no password: it listens on loopback only and refuses every destination that isn't a
// public internet address, so another app on the head unit can't use it to reach the tailnet
// or the home network behind the exit node.
type proxy struct {
	ln    net.Listener
	dial  dialFunc
	slots chan struct{}

	mu     sync.Mutex
	conns  map[net.Conn]struct{}
	closed bool
}

func startProxy(dial dialFunc) (*proxy, error) {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return nil, errors.New("can't open the local proxy port")
	}
	p := &proxy{ln: ln, dial: dial, slots: make(chan struct{}, 64), conns: map[net.Conn]struct{}{}}
	go p.accept()
	return p, nil
}

func (p *proxy) port() int { return p.ln.Addr().(*net.TCPAddr).Port }

func (p *proxy) close() {
	p.mu.Lock()
	p.closed = true
	all := make([]net.Conn, 0, len(p.conns))
	for c := range p.conns {
		all = append(all, c)
	}
	p.mu.Unlock()
	_ = p.ln.Close()
	for _, c := range all {
		_ = c.Close()
	}
}

func (p *proxy) track(c net.Conn) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		_ = c.Close()
		return false
	}
	p.conns[c] = struct{}{}
	return true
}

func (p *proxy) untrack(c net.Conn) {
	_ = c.Close()
	p.mu.Lock()
	delete(p.conns, c)
	p.mu.Unlock()
}

func (p *proxy) accept() {
	for {
		c, err := p.ln.Accept()
		if err != nil {
			return
		}
		select {
		case p.slots <- struct{}{}:
			go func() {
				defer func() { <-p.slots }()
				p.serve(c)
			}()
		default:
			_ = c.Close() // too many at once
		}
	}
}

func (p *proxy) serve(client net.Conn) {
	if !p.track(client) {
		return
	}
	defer p.untrack(client)

	_ = client.SetReadDeadline(time.Now().Add(15 * time.Second))
	br := bufio.NewReader(client)
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	_ = client.SetReadDeadline(time.Time{})

	target := req.Host
	if req.Method != http.MethodConnect {
		if req.URL == nil || !strings.EqualFold(req.URL.Scheme, "http") || req.URL.Host == "" {
			reply(client, http.StatusBadRequest)
			return
		}
		target = req.URL.Host
	}
	if _, _, err := net.SplitHostPort(target); err != nil {
		if req.Method == http.MethodConnect {
			reply(client, http.StatusBadRequest)
			return
		}
		target = net.JoinHostPort(strings.Trim(target, "[]"), "80")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	upstream, err := p.dialPublic(ctx, target)
	cancel()
	if err != nil {
		if errors.Is(err, errNotPublic) {
			reply(client, http.StatusForbidden)
		} else {
			reply(client, http.StatusBadGateway)
		}
		return
	}
	if !p.track(upstream) {
		return
	}
	defer p.untrack(upstream)

	if req.Method == http.MethodConnect {
		if _, err := io.WriteString(client, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
			return
		}
		// Bytes the client sent after the CONNECT header (TLS ClientHello) are in br.
		splice(client, br, upstream)
		return
	}

	// One request per upstream connection: the next request may be for another host.
	req.Header.Del("Proxy-Connection")
	req.Header.Del("Proxy-Authorization")
	req.Header.Set("Connection", "close")
	req.Close = true
	if err := req.Write(upstream); err != nil {
		reply(client, http.StatusBadGateway)
		return
	}
	_, _ = io.Copy(client, upstream)
}

// splice copies both ways until either side closes.
func splice(client net.Conn, clientReader io.Reader, upstream net.Conn) {
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(upstream, clientReader)
		if tc, ok := upstream.(interface{ CloseWrite() error }); ok {
			_ = tc.CloseWrite()
		}
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(client, upstream)
		if tc, ok := client.(interface{ CloseWrite() error }); ok {
			_ = tc.CloseWrite()
		}
		done <- struct{}{}
	}()
	<-done
	<-done
}

var errNotPublic = errors.New("destination is not a public internet address")

func (p *proxy) dialPublic(ctx context.Context, target string) (net.Conn, error) {
	host, _, err := net.SplitHostPort(target)
	if err != nil {
		return nil, err
	}
	if a, err := netip.ParseAddr(host); err == nil && !isPublic(a) {
		return nil, errNotPublic
	}
	if strings.EqualFold(host, "localhost") || strings.HasSuffix(strings.ToLower(host), ".ts.net") {
		return nil, errNotPublic
	}
	c, err := p.dial(ctx, "tcp", target)
	if err != nil {
		return nil, err
	}
	// A public name can still resolve to a private address: check where it actually went.
	if ra, ok := c.RemoteAddr().(*net.TCPAddr); ok {
		if a, ok := netip.AddrFromSlice(ra.IP); ok && !isPublic(a.Unmap()) {
			_ = c.Close()
			return nil, errNotPublic
		}
	}
	return c, nil
}

var (
	cgnat         = netip.MustParsePrefix("100.64.0.0/10")
	tailscaleULA  = netip.MustParsePrefix("fd7a:115c:a1e0::/48")
	benchmarkNets = netip.MustParsePrefix("198.18.0.0/15")
)

func isPublic(a netip.Addr) bool {
	a = a.Unmap()
	return a.IsValid() && a.IsGlobalUnicast() && !a.IsPrivate() &&
		!cgnat.Contains(a) && !tailscaleULA.Contains(a) && !benchmarkNets.Contains(a)
}

func reply(w io.Writer, code int) {
	_, _ = io.WriteString(w, "HTTP/1.1 "+strconv.Itoa(code)+" "+http.StatusText(code)+"\r\n")
	_, _ = io.WriteString(w, "Content-Length: 0\r\nConnection: close\r\n\r\n")
}
