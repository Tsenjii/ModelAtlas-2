package main

import (
	"errors"
	"strings"
)

// Retain a short suffix so a secret split across chunks cannot reach the UI.
// The full text is still checked before persistence. No per-token DB writes.
type streamGuard struct {
	pending string
	secrets []string
	emit    func(string) error
}

func (g *streamGuard) write(text string) error {
	g.pending += text
	keep := 0
	for _, secret := range g.secrets {
		if secret != "" && strings.Contains(g.pending, secret) {
			return errors.New("secret in response")
		}
		keep = max(keep, len(secret)-1)
	}
	if len(g.pending) > keep {
		n := len(g.pending) - keep
		for n > 0 && n < len(g.pending) && g.pending[n]&0xc0 == 0x80 {
			n--
		}
		if n > 0 {
			if err := g.emit(g.pending[:n]); err != nil {
				return err
			}
			g.pending = g.pending[n:]
		}
	}
	return nil
}
func (g *streamGuard) flush() error {
	if g.pending == "" {
		return nil
	}
	v := g.pending
	g.pending = ""
	return g.emit(v)
}
