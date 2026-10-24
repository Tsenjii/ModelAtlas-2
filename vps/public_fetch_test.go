package main

import (
	"net"
	"testing"
)

func TestPublicResearchBlocksSpecialAndEmbeddedPrivateAddresses(t *testing.T) {
	for _, address := range []string{"0.1.2.3", "100.64.1.1", "192.0.0.170", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "240.1.1.1", "::127.0.0.1", "fec0::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1", "64:ff9b::a00:1", "64:ff9b:1::a00:1", "100::1", "100:0:0:1::1", "2001:2::1", "2001:db8::1", "2002:7f00:0001::1", "2001::1", "3fff::1", "5f00::1", "fe80::1", "fc00::1"} {
		if publicIP(net.ParseIP(address)) {
			t.Errorf("special/private address allowed %s", address)
		}
	}
	for _, address := range []string{"8.8.8.8", "1.1.1.1", "192.0.0.9", "192.0.0.10", "2001:4860:4860::8888", "2606:4700:4700::1111", "64:ff9b::808:808", "::ffff:8.8.8.8"} {
		if !publicIP(net.ParseIP(address)) {
			t.Errorf("public address rejected %s", address)
		}
	}
	for _, url := range []string{"https://[64:ff9b::7f00:1]/", "https://0.1.2.3/", "https://[2002:7f00:0001::1]/"} {
		if publicURL(url) {
			t.Error("untrusted literal origin accepted")
		}
	}
}
