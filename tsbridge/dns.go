package main

// 1.4.1-beta1: DNS that survives a proxy VPN.
//
// Android has no /etc/resolv.conf, so the bridge brings its own resolvers: the ones the app read from the phone's
// networks, then public ones. Before, they were tried one after another by "dialing" each: a UDP dial never fails
// (nothing is sent), so the first server was the only one ever asked. With a proxy VPN such as FlClash, the active
// network's DNS is the VPN's own (172.19.0.2 and the like); when Agent-VScreen is bypassed in the proxy, its
// packets never reach that address, every lookup timed out, and the custom control server could not be resolved
// ("fetch control key … context canceled", then "no DNS fallback candidates remain").
//
// Now a UDP query goes to every server at once and the first answer with the right id wins. TCP (used for long
// answers) still dials the servers in turn, where a dead one does fail the dial.

import (
	"context"
	"errors"
	"net"
	"strings"
	"time"
)

func dnsServerList(fromApp string) []string {
	seen := map[string]bool{}
	var out []string
	add := func(s string) {
		s = strings.TrimSpace(strings.Trim(strings.TrimSpace(s), "[]"))
		if s == "" {
			return
		}
		hp := net.JoinHostPort(s, "53")
		if !seen[hp] {
			seen[hp] = true
			out = append(out, hp)
		}
	}
	for _, s := range strings.Split(fromApp, ",") {
		add(s)
	}
	for _, s := range []string{"223.5.5.5", "1.1.1.1", "8.8.8.8"} {
		add(s)
	}
	return out
}

func newResolver(servers []string) *net.Resolver {
	return &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			if strings.HasPrefix(network, "udp") {
				return newFanoutConn(servers)
			}
			d := net.Dialer{Timeout: 3 * time.Second}
			var err error
			for _, s := range servers {
				var c net.Conn
				if c, err = d.DialContext(ctx, network, s); err == nil {
					return c, nil
				}
			}
			return nil, err
		},
	}
}

// fanoutConn looks like one connected UDP socket to Go's resolver: Write sends the query to every server, Read
// returns the first reply from any of them.
type fanoutConn struct {
	pc      net.PacketConn
	targets []*net.UDPAddr
	from    map[string]bool
}

func newFanoutConn(servers []string) (net.Conn, error) {
	var targets []*net.UDPAddr
	from := map[string]bool{}
	for _, s := range servers {
		if a, err := net.ResolveUDPAddr("udp", s); err == nil {
			targets = append(targets, a)
			from[a.String()] = true
		}
	}
	if len(targets) == 0 {
		return nil, errors.New("no DNS servers")
	}
	pc, err := net.ListenPacket("udp", ":0")
	if err != nil {
		return nil, err
	}
	return &fanoutConn{pc: pc, targets: targets, from: from}, nil
}

func (c *fanoutConn) Write(b []byte) (int, error) {
	sent := 0
	var last error
	for _, t := range c.targets {
		if _, err := c.pc.WriteTo(b, t); err == nil {
			sent++
		} else {
			last = err // e.g. no IPv6 route: the others still go
		}
	}
	if sent == 0 {
		return 0, last
	}
	return len(b), nil
}

func (c *fanoutConn) Read(b []byte) (int, error) {
	for {
		n, a, err := c.pc.ReadFrom(b)
		if err != nil {
			return n, err
		}
		if c.from[a.String()] {
			return n, nil
		}
	}
}

// The resolver checks for net.PacketConn to use datagram framing; these route to the same fan-out.
func (c *fanoutConn) ReadFrom(b []byte) (int, net.Addr, error) {
	n, err := c.Read(b)
	return n, c.RemoteAddr(), err
}
func (c *fanoutConn) WriteTo(b []byte, _ net.Addr) (int, error) { return c.Write(b) }

func (c *fanoutConn) Close() error                       { return c.pc.Close() }
func (c *fanoutConn) LocalAddr() net.Addr                { return c.pc.LocalAddr() }
func (c *fanoutConn) RemoteAddr() net.Addr               { return c.targets[0] }
func (c *fanoutConn) SetDeadline(t time.Time) error      { return c.pc.SetDeadline(t) }
func (c *fanoutConn) SetReadDeadline(t time.Time) error  { return c.pc.SetReadDeadline(t) }
func (c *fanoutConn) SetWriteDeadline(t time.Time) error { return c.pc.SetWriteDeadline(t) }
