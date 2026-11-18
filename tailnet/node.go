// MG4 Streams: embedded Tailscale node.
//
// Parts adapted from NovaScale for Android (github.com/GalaxNet-Ltd/novascale-android,
// native/nova-tailnet-go), Copyright (C) 2026 NovaScale contributors, Apache-2.0.
// Tailscale (tailscale.com) is BSD-3-Clause.

// Package mg4tailnet runs one Tailscale node inside the app (tsnet, no Android VpnService) and
// serves a loopback HTTP proxy whose connections leave through a chosen exit node. The app points
// its HTTP stack at that proxy, so Australian stations see the exit node's home address instead
// of the car modem's German one.
//
// The API is shaped for gomobile: strings, ints, bools and errors only.
package mg4tailnet

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"tailscale.com/client/local"
	"tailscale.com/envknob"
	"tailscale.com/ipn"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
)

// Node owns the app's one embedded tailnet node.
type Node struct {
	mu       sync.Mutex
	server   *tsnet.Server
	lc       *local.Client
	proxy    *proxy
	cancel   context.CancelFunc
	started  bool
	closed   bool
	state    string
	loginURL string
	lastErr  string
}

// NewNode creates an unstarted node. stateDir must be absolute and app-private: it holds the
// node's identity, so the car stays signed in across restarts.
func NewNode(stateDir, hostname string) (*Node, error) {
	stateDir = strings.TrimSpace(stateDir)
	hostname = strings.TrimSpace(hostname)
	if stateDir == "" || !filepath.IsAbs(stateDir) {
		return nil, errors.New("state directory must be an absolute path")
	}
	if hostname == "" {
		return nil, errors.New("hostname must not be empty")
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return nil, fmt.Errorf("create state directory: %w", err)
	}
	// No log uploads to Tailscale. Must be set before tsnet creates its logger.
	envknob.SetNoLogsNoSupport()
	// Android has no usable cache or service-state directory for Tailscale's log policy file.
	_ = os.Setenv("TS_LOGS_DIR", stateDir)

	return &Node{
		server: &tsnet.Server{
			Dir:      stateDir,
			Hostname: hostname,
			Logf:     func(string, ...any) {},
			UserLogf: func(string, ...any) {},
		},
		state: "Stopped",
	}, nil
}

// Start brings the node up and keeps its state current. Safe to call again.
func (n *Node) Start() error {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed {
		return errors.New("node is closed")
	}
	if n.started {
		return nil
	}
	lc, err := n.server.LocalClient()
	if err != nil {
		return fmt.Errorf("start tailnet: %w", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	watcher, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState|ipn.NotifyNoPrivateKeys)
	if err != nil {
		cancel()
		return fmt.Errorf("watch tailnet state: %w", err)
	}
	n.lc = lc
	n.cancel = cancel
	n.started = true
	n.state = "Starting"
	go n.watch(watcher)
	return nil
}

func (n *Node) watch(w *local.IPNBusWatcher) {
	defer w.Close()
	for {
		note, err := w.Next()
		if err != nil {
			n.mu.Lock()
			if !n.closed {
				n.lastErr = "Tailscale stopped unexpectedly"
			}
			n.mu.Unlock()
			return
		}
		n.mu.Lock()
		if note.State != nil {
			n.state = note.State.String()
			if *note.State == ipn.Running {
				n.loginURL = ""
				n.lastErr = ""
			}
		}
		if note.BrowseToURL != nil {
			n.loginURL = *note.BrowseToURL
		}
		if note.ErrMessage != nil {
			n.lastErr = *note.ErrMessage
		}
		n.mu.Unlock()
	}
}

func (n *Node) client() (*local.Client, error) {
	if err := n.Start(); err != nil {
		return nil, err
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.lc, nil
}

// Login asks Tailscale for a sign-in link. It appears as loginURL in Status; open it on a phone.
func (n *Node) Login() error {
	lc, err := n.client()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return lc.StartLoginInteractive(ctx)
}

// LoginWithKey signs in with an auth key (from the admin console). The key isn't stored by the
// app; Tailscale keeps the resulting node identity in stateDir.
func (n *Node) LoginWithKey(key string) error {
	key = strings.TrimSpace(key)
	if key == "" {
		return errors.New("auth key is empty")
	}
	lc, err := n.client()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := lc.Start(ctx, ipn.Options{AuthKey: key}); err != nil {
		return errors.New("sign-in failed: check the key and the connection")
	}
	// Start only installs the key; this consumes it (no browser involved).
	if err := lc.StartLoginInteractive(ctx); err != nil {
		return errors.New("sign-in failed: check the key and the connection")
	}
	return nil
}

// Logout removes this node's identity.
func (n *Node) Logout() error {
	lc, err := n.client()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return lc.Logout(ctx)
}

type exitNodeInfo struct {
	Name   string `json:"name"`
	IP     string `json:"ip"`
	Online bool   `json:"online"`
	Active bool   `json:"active"`
}

// ExitNodes lists the peers that offer to be an exit node, as JSON: [{name, ip, online, active}].
func (n *Node) ExitNodes() (string, error) {
	lc, err := n.client()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		return "", err
	}
	out := []exitNodeInfo{}
	for _, p := range st.Peer {
		if !p.ExitNodeOption {
			continue
		}
		ip := ""
		if len(p.TailscaleIPs) > 0 {
			ip = p.TailscaleIPs[0].String()
		}
		out = append(out, exitNodeInfo{Name: shortName(p.DNSName, p.HostName), IP: ip, Online: p.Online, Active: p.ExitNode})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	b, _ := json.Marshal(out)
	return string(b), nil
}

// SetExitNode routes the proxy's traffic through the peer named (short name, MagicDNS name or
// Tailscale IP). An empty name clears it.
func (n *Node) SetExitNode(name string) error {
	lc, err := n.client()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	name = strings.TrimSpace(name)
	mp := &ipn.MaskedPrefs{ExitNodeIDSet: true, ExitNodeIPSet: true}
	if name == "" {
		_, err = lc.EditPrefs(ctx, mp)
		return err
	}
	st, err := lc.Status(ctx)
	if err != nil {
		return err
	}
	var id tailcfg.StableNodeID
	for _, p := range st.Peer {
		if !p.ExitNodeOption {
			continue
		}
		if strings.EqualFold(shortName(p.DNSName, p.HostName), name) ||
			strings.EqualFold(strings.TrimSuffix(p.DNSName, "."), strings.TrimSuffix(name, ".")) ||
			strings.EqualFold(p.HostName, name) || hasIP(p.TailscaleIPs, name) {
			id = p.ID
			break
		}
	}
	if id == "" {
		return fmt.Errorf("no exit node called %q on this tailnet", name)
	}
	mp.ExitNodeID = id
	_, err = lc.EditPrefs(ctx, mp)
	return err
}

type statusInfo struct {
	State      string `json:"state"`
	LoginURL   string `json:"loginURL,omitempty"`
	Error      string `json:"error,omitempty"`
	Self       string `json:"self,omitempty"`
	SelfIP     string `json:"selfIP,omitempty"`
	ExitNode   string `json:"exitNode,omitempty"`
	ExitOnline bool   `json:"exitOnline"`
	ProxyPort  int    `json:"proxyPort"`
}

// Status reports the node as JSON: state (NoState, NeedsLogin, Starting, Running, Stopped...),
// loginURL while a sign-in is pending, error, self, selfIP, exitNode, exitOnline, proxyPort.
func (n *Node) Status() string {
	n.mu.Lock()
	info := statusInfo{State: n.state, LoginURL: n.loginURL, Error: n.lastErr}
	lc := n.lc
	if n.proxy != nil {
		info.ProxyPort = n.proxy.port()
	}
	n.mu.Unlock()
	if lc != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		if st, err := lc.Status(ctx); err == nil {
			if st.Self != nil {
				info.Self = shortName(st.Self.DNSName, st.Self.HostName)
				if len(st.Self.TailscaleIPs) > 0 {
					info.SelfIP = st.Self.TailscaleIPs[0].String()
				}
			}
			if st.ExitNodeStatus != nil {
				for _, p := range st.Peer {
					if p.ID == st.ExitNodeStatus.ID {
						info.ExitNode = shortName(p.DNSName, p.HostName)
					}
				}
				info.ExitOnline = st.ExitNodeStatus.Online
			}
		}
		cancel()
	}
	b, _ := json.Marshal(info)
	return string(b)
}

// StartProxy starts (or returns) the loopback HTTP proxy and returns its port. Connections go out
// through the node: via the exit node when one is set, otherwise directly.
func (n *Node) StartProxy() (int, error) {
	if err := n.Start(); err != nil {
		return 0, err
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.proxy != nil {
		return n.proxy.port(), nil
	}
	p, err := startProxy(n.dialViaTailnet)
	if err != nil {
		return 0, err
	}
	n.proxy = p
	return p.port(), nil
}

// dialViaTailnet dials through the node and fails closed: tsnet sends a public address through
// netstack (a *gonet.TCPConn) only while an exit route is active; otherwise it quietly dials
// straight from the car's internet, which is exactly what the relay is there to avoid.
func (n *Node) dialViaTailnet(ctx context.Context, network, address string) (net.Conn, error) {
	c, err := n.server.Dial(ctx, network, address)
	if err != nil {
		return nil, err
	}
	if _, viaTailnet := c.(*gonet.TCPConn); !viaTailnet {
		_ = c.Close()
		return nil, errDirect
	}
	return c, nil
}

// Close stops the proxy and the node. The node can't be restarted; make a new one.
func (n *Node) Close() error {
	n.mu.Lock()
	if n.closed {
		n.mu.Unlock()
		return nil
	}
	n.closed = true
	p, cancel, started := n.proxy, n.cancel, n.started
	n.proxy = nil
	n.mu.Unlock()
	if p != nil {
		p.close()
	}
	if cancel != nil {
		cancel()
	}
	if started {
		return n.server.Close()
	}
	return nil
}

func shortName(dnsName, hostName string) string {
	if dnsName != "" {
		if i := strings.IndexByte(dnsName, '.'); i > 0 {
			return dnsName[:i]
		}
	}
	return hostName
}

func hasIP(ips []netip.Addr, s string) bool {
	a, err := netip.ParseAddr(s)
	if err != nil {
		return false
	}
	for _, ip := range ips {
		if ip == a {
			return true
		}
	}
	return false
}
