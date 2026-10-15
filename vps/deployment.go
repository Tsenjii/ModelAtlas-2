package main

import (
	"encoding/base64"
	"errors"
	"net"
	"net/http"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
)

type deploymentConfig struct {
	addr, db, token, publicOrigin string
	key                           []byte
}

// Platform mode is an explicit trust boundary: the hosting service must isolate
// this HTTP port and overwrite X-Forwarded-Proto at its HTTPS reverse proxy.
// Merely inheriting PORT must never expose the default local deployment.
func readDeploymentConfig(getenv func(string) string) (deploymentConfig, error) {
	c := deploymentConfig{token: getenv("MODELATLAS_ADMIN_TOKEN"), db: getenv("MODELATLAS_DB"), addr: getenv("MODELATLAS_LISTEN")}
	if len(c.token) < 24 {
		return c, errors.New("MODELATLAS_ADMIN_TOKEN must be at least 24 characters")
	}
	if value := getenv("MODELATLAS_MASTER_KEY"); value != "" {
		var err error
		c.key, err = base64.StdEncoding.DecodeString(value)
		if err != nil || len(c.key) != 32 {
			return c, errors.New("MODELATLAS_MASTER_KEY must be base64 of exactly 32 bytes")
		}
	}
	mode := getenv("MODELATLAS_DEPLOYMENT")
	if mode != "" && mode != "local" && mode != "platform" {
		return c, errors.New("MODELATLAS_DEPLOYMENT must be local or platform")
	}
	if mode == "platform" {
		origin, err := url.Parse(getenv("MODELATLAS_PUBLIC_ORIGIN"))
		if err != nil || origin.Scheme != "https" || origin.Hostname() == "" || origin.User != nil || (origin.Path != "" && origin.Path != "/") || origin.RawQuery != "" || origin.ForceQuery || origin.Fragment != "" || origin.Opaque != "" {
			return c, errors.New("platform mode requires MODELATLAS_PUBLIC_ORIGIN as an HTTPS origin without credentials, path, query or fragment")
		}
		if port := origin.Port(); port != "" && !validListenPort(port) {
			return c, errors.New("invalid MODELATLAS_PUBLIC_ORIGIN port")
		}
		c.publicOrigin = "https://" + canonicalDeploymentHost(origin.Host)
		if len(c.key) != 32 {
			return c, errors.New("platform mode requires a stable MODELATLAS_MASTER_KEY")
		}
		if !filepath.IsAbs(c.db) {
			return c, errors.New("platform mode requires MODELATLAS_DB as an absolute path on persistent storage")
		}
		if c.addr == "" {
			port := getenv("PORT")
			if port == "" {
				port = "8787"
			}
			if !validListenPort(port) {
				return c, errors.New("PORT must be an integer from 1 to 65535")
			}
			c.addr = net.JoinHostPort("0.0.0.0", port)
		}
	} else {
		if getenv("MODELATLAS_PUBLIC_ORIGIN") != "" {
			return c, errors.New("MODELATLAS_PUBLIC_ORIGIN requires explicit MODELATLAS_DEPLOYMENT=platform")
		}
		if c.addr == "" {
			c.addr = "127.0.0.1:8787"
		}
		if c.db == "" {
			c.db = "./data/modelatlas.db"
		}
	}
	host, port, err := net.SplitHostPort(c.addr)
	if err != nil || !validListenPort(port) {
		return c, errors.New("invalid MODELATLAS_LISTEN: require numeric IP and port 1..65535")
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return c, errors.New("MODELATLAS_LISTEN requires a numeric IP")
	}
	if mode != "platform" && !ip.IsLoopback() {
		return c, errors.New("refuse public listener: use loopback or explicitly configure platform mode behind an isolated HTTPS reverse proxy")
	}
	return c, nil
}

func validListenPort(port string) bool {
	if port == "" || strings.IndexFunc(port, func(r rune) bool { return r < '0' || r > '9' }) != -1 {
		return false
	}
	n, err := strconv.Atoi(port)
	return err == nil && n >= 1 && n <= 65535
}

func canonicalDeploymentHost(host string) string {
	host = strings.ToLower(host)
	if h, p, err := net.SplitHostPort(host); err == nil && p == "443" {
		if strings.Contains(h, ":") {
			return "[" + h + "]"
		}
		return h
	}
	return host
}

func (c deploymentConfig) handler(next http.Handler) http.Handler {
	if c.publicOrigin == "" {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Platform health probes may use an internal host and plaintext transport.
		// Only this existing non-sensitive endpoint is exempt, never the UI/API.
		if (r.Method == "GET" || r.Method == "HEAD") && r.URL.Path == "/healthz" {
			next.ServeHTTP(w, r)
			return
		}
		security(w)
		if "https://"+canonicalDeploymentHost(r.Host) != c.publicOrigin {
			http.Error(w, "public origin mismatch", http.StatusMisdirectedRequest)
			return
		}
		proto := r.Header.Values("X-Forwarded-Proto")
		if r.TLS == nil && (len(proto) != 1 || proto[0] != "https") {
			http.Error(w, "HTTPS reverse proxy required", http.StatusUpgradeRequired)
			return
		}
		w.Header().Set("Strict-Transport-Security", "max-age=31536000")
		if origin := r.Header.Get("Origin"); origin != "" && origin != c.publicOrigin {
			http.Error(w, "origin mismatch", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}
