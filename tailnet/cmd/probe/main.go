// Command probe checks, from a Mac, that the embedded node routes public traffic through an exit
// node: it fetches an IP-geolocation page directly and through the node's proxy, then (optionally)
// the start of a radio stream through the proxy.
//
//	go run ./cmd/probe -exit deathstar -stream https://example.com/stream.mp3
//
// The first run prints a sign-in link; the identity is kept in ~/.mg4-tailnet-probe.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	mg4tailnet "github.com/andrew-snape/mg4streams/tailnet"
)

func main() {
	exit := flag.String("exit", "", "exit node name (blank lists them)")
	stream := flag.String("stream", "", "stream URL to test through the exit node")
	authKey := flag.String("authkey", os.Getenv("TS_AUTHKEY"), "auth key (optional; otherwise a sign-in link)")
	flag.Parse()

	home, _ := os.UserHomeDir()
	node, err := mg4tailnet.NewNode(filepath.Join(home, ".mg4-tailnet-probe"), "mg4-probe")
	check(err)
	defer node.Close()
	check(node.Start())

	st := waitState(node, func(s status) bool { return s.State == "NeedsLogin" || s.State == "Running" }, 60*time.Second)
	if st.State == "NeedsLogin" {
		if *authKey != "" {
			check(node.LoginWithKey(*authKey))
		} else {
			check(node.Login())
			s := waitState(node, func(s status) bool { return s.LoginURL != "" || s.State == "Running" }, 30*time.Second)
			if s.LoginURL != "" {
				fmt.Println("Sign in:", s.LoginURL)
			}
		}
		waitState(node, func(s status) bool { return s.State == "Running" }, 5*time.Minute)
	}
	st = readStatus(node)
	fmt.Printf("Running as %s (%s)\n", st.Self, st.SelfIP)

	list, err := node.ExitNodes()
	check(err)
	fmt.Println("Exit nodes:", list)
	if *exit == "" {
		return
	}
	check(node.SetExitNode(*exit))
	time.Sleep(3 * time.Second)
	st = readStatus(node)
	fmt.Printf("Exit node: %s online=%v\n", st.ExitNode, st.ExitOnline)

	port, err := node.StartProxy()
	check(err)
	proxyURL, _ := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", port))
	direct := &http.Client{Timeout: 20 * time.Second}
	viaNode := &http.Client{Timeout: 30 * time.Second, Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}}

	fmt.Println("Direct:   ", geo(direct))
	fmt.Println("Via node: ", geo(viaNode))

	if *stream != "" {
		start := time.Now()
		resp, err := viaNode.Get(*stream)
		if err != nil {
			fmt.Println("Stream:    error:", err)
			return
		}
		defer resp.Body.Close()
		n, _ := io.CopyN(io.Discard, resp.Body, 256*1024)
		secs := time.Since(start).Seconds()
		fmt.Printf("Stream:    HTTP %d %s, %d KB in %.1f s (%.0f kbit/s)\n",
			resp.StatusCode, resp.Header.Get("Content-Type"), n/1024, secs, float64(n*8)/1000/secs)
	}
}

func geo(c *http.Client) string {
	resp, err := c.Get("https://ipinfo.io/json")
	if err != nil {
		return "error: " + err.Error()
	}
	defer resp.Body.Close()
	var v struct{ IP, City, Country, Org string }
	if err := json.NewDecoder(resp.Body).Decode(&v); err != nil {
		return "error: " + err.Error()
	}
	return strings.Join([]string{v.IP, v.City, v.Country, v.Org}, "  ")
}

type status struct {
	State, LoginURL, Error, Self, SelfIP, ExitNode string
	ExitOnline                                     bool
}

func readStatus(n *mg4tailnet.Node) status {
	var s status
	_ = json.Unmarshal([]byte(n.Status()), &s)
	return s
}

func waitState(n *mg4tailnet.Node, ok func(status) bool, limit time.Duration) status {
	deadline := time.Now().Add(limit)
	for {
		s := readStatus(n)
		if ok(s) {
			return s
		}
		if time.Now().After(deadline) {
			fmt.Fprintf(os.Stderr, "timed out in state %s %s\n", s.State, s.Error)
			os.Exit(1)
		}
		time.Sleep(500 * time.Millisecond)
	}
}

func check(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}
