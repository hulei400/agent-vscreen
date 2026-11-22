package main

import (
	"context"
	"net"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// A dead first resolver (the VPN's DNS when the app is bypassed) must not stop the lookup: the next one answers.
func TestFanoutSkipsSilentServer(t *testing.T) {
	silent, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer silent.Close() // reads nothing, answers nothing

	good, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer good.Close()
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := good.ReadFrom(buf)
			if err != nil {
				return
			}
			var p dnsmessage.Parser
			h, err := p.Start(buf[:n])
			if err != nil {
				continue
			}
			q, err := p.Question()
			if err != nil {
				continue
			}
			b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: h.ID, Response: true, Authoritative: true})
			b.EnableCompression()
			_ = b.StartQuestions()
			_ = b.Question(q)
			_ = b.StartAnswers()
			if q.Type == dnsmessage.TypeA {
				_ = b.AResource(dnsmessage.ResourceHeader{Name: q.Name, Class: dnsmessage.ClassINET, TTL: 60}, dnsmessage.AResource{A: [4]byte{10, 1, 2, 3}})
			}
			out, _ := b.Finish()
			_, _ = good.WriteTo(out, from)
		}
	}()

	servers := []string{silent.LocalAddr().String(), good.LocalAddr().String()}
	r := newResolver(servers)
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	start := time.Now()
	ips, err := r.LookupIP(ctx, "ip4", "headscale.example.test")
	if err != nil {
		t.Fatalf("lookup failed: %v", err)
	}
	if len(ips) != 1 || ips[0].String() != "10.1.2.3" {
		t.Fatalf("got %v", ips)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("too slow (%v): the silent server was waited for", time.Since(start))
	}
}

func TestServerListDedupAndPublicTail(t *testing.T) {
	got := dnsServerList(" 172.19.0.2 ,[fe80::1],172.19.0.2,,223.5.5.5")
	want := []string{"172.19.0.2:53", "[fe80::1]:53", "223.5.5.5:53", "1.1.1.1:53", "8.8.8.8:53"}
	if len(got) != len(want) {
		t.Fatalf("got %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v", got)
		}
	}
}
