package mg4tailnet

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"testing"
	"time"
)

// publicConn pretends a loopback test connection went to a public address.
type publicConn struct{ net.Conn }

func (publicConn) RemoteAddr() net.Addr {
	return &net.TCPAddr{IP: net.ParseIP("203.0.113.9"), Port: 80}
}

// startOrigin serves raw bytes, like a Shoutcast server answering "ICY 200 OK".
func startOrigin(t *testing.T, response string) (dialFunc, chan string) {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	got := make(chan string, 4)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				req, err := http.ReadRequest(bufio.NewReader(c))
				if err != nil {
					return
				}
				got <- req.Method + " " + req.RequestURI + " host=" + req.Host + " conn=" + req.Header.Get("Connection") +
					" proxyauth=" + req.Header.Get("Proxy-Authorization")
				io.WriteString(c, response)
			}(c)
		}
	}()
	dial := func(ctx context.Context, network, address string) (net.Conn, error) {
		c, err := net.Dial("tcp4", ln.Addr().String())
		if err != nil {
			return nil, err
		}
		return publicConn{c}, nil
	}
	return dial, got
}

func clientVia(t *testing.T, p *proxy) *http.Client {
	u, _ := url.Parse("http://" + p.ln.Addr().String())
	return &http.Client{Timeout: 5 * time.Second, Transport: &http.Transport{Proxy: http.ProxyURL(u)}}
}

func TestPlainHTTPForwardedInOriginForm(t *testing.T) {
	dial, got := startOrigin(t, "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello")
	p, err := startProxy(dial)
	if err != nil {
		t.Fatal(err)
	}
	defer p.close()

	req, _ := http.NewRequest("GET", "http://radio.example/live?x=1", nil)
	req.Header.Set("Proxy-Authorization", "Basic c2VjcmV0")
	resp, err := clientVia(t, p).Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "hello" {
		t.Fatalf("body %q", body)
	}
	line := <-got
	if line != "GET /live?x=1 host=radio.example conn=close proxyauth=" {
		t.Fatalf("origin saw %q", line)
	}
}

func TestIcyResponsePassesThrough(t *testing.T) {
	dial, _ := startOrigin(t, "ICY 200 OK\r\nicy-name: Test\r\n\r\nAUDIO")
	p, _ := startProxy(dial)
	defer p.close()

	c, err := net.Dial("tcp4", p.ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	io.WriteString(c, "GET http://radio.example/ HTTP/1.1\r\nHost: radio.example\r\n\r\n")
	all, _ := io.ReadAll(c)
	if !strings.HasPrefix(string(all), "ICY 200 OK") || !strings.HasSuffix(string(all), "AUDIO") {
		t.Fatalf("got %q", all)
	}
}

func TestConnectTunnels(t *testing.T) {
	dial, got := startOrigin(t, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
	p, _ := startProxy(dial)
	defer p.close()

	c, err := net.Dial("tcp4", p.ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	io.WriteString(c, "CONNECT secure.example:443 HTTP/1.1\r\nHost: secure.example:443\r\n\r\n")
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, nil)
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("CONNECT: %v %v", resp, err)
	}
	// Inside the tunnel, speak to the origin directly.
	io.WriteString(c, "GET /inner HTTP/1.1\r\nHost: secure.example\r\n\r\n")
	inner, err := http.ReadResponse(br, nil)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(inner.Body)
	if string(b) != "ok" || !strings.HasPrefix(<-got, "GET /inner") {
		t.Fatalf("tunnel body %q", b)
	}
}

func TestRefusesNonPublicDestinations(t *testing.T) {
	dialed := false
	p, _ := startProxy(func(ctx context.Context, n, a string) (net.Conn, error) {
		dialed = true
		return nil, io.EOF
	})
	defer p.close()
	client := clientVia(t, p)
	for _, u := range []string{"http://192.168.1.1/", "http://100.69.229.36/", "http://localhost/", "http://deathstar.tail1234.ts.net/", "http://10.0.0.1:8080/"} {
		resp, err := client.Get(u)
		if err != nil {
			t.Fatalf("%s: %v", u, err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Fatalf("%s: status %d", u, resp.StatusCode)
		}
	}
	if dialed {
		t.Fatal("dialed a refused destination")
	}
}

func TestRefusesNameResolvingToPrivate(t *testing.T) {
	ln, _ := net.Listen("tcp4", "127.0.0.1:0")
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	p, _ := startProxy(func(ctx context.Context, n, a string) (net.Conn, error) {
		return net.Dial("tcp4", ln.Addr().String()) // RemoteAddr is 127.0.0.1
	})
	defer p.close()
	resp, err := clientVia(t, p).Get("http://rebind.example/")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("status %d", resp.StatusCode)
	}
}

func TestDirectRouteIsRefusedDistinctly(t *testing.T) {
	p, _ := startProxy(func(ctx context.Context, n, a string) (net.Conn, error) { return nil, errDirect })
	defer p.close()
	resp, err := clientVia(t, p).Get("http://example.com/")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusServiceUnavailable || resp.Status != "503 Exit Route Not Active" {
		t.Fatalf("status %q", resp.Status)
	}
}

func TestIsPublic(t *testing.T) {
	for s, want := range map[string]bool{
		"151.189.28.231": true, "8.8.8.8": true, "2606:4700::1111": true,
		"10.1.2.3": false, "172.16.0.1": false, "192.168.225.1": false, "127.0.0.1": false,
		"100.78.249.83": false, "169.254.1.1": false, "fd7a:115c:a1e0::1": false, "::1": false,
		"::ffff:192.168.0.1": false, "0.0.0.0": false, "224.0.0.1": false,
	} {
		if got := isPublic(netip.MustParseAddr(s)); got != want {
			t.Errorf("isPublic(%s) = %v", s, got)
		}
	}
}
