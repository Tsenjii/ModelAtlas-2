package main

import (
	"errors"
	"flag"
	"io"
	"path/filepath"
)

// A private configuration file is data, never a shell script. The same entry
// point works under a foreground supervisor, a user service or a container.
func serveConfig(args []string, getenv func(string) string) (deploymentConfig, error) {
	f := flag.NewFlagSet("serve", flag.ContinueOnError)
	f.SetOutput(io.Discard)
	path := f.String("env-file", "", "private configuration file")
	if f.Parse(args) != nil || f.NArg() != 0 {
		return deploymentConfig{}, errors.New("usage: serve [--env-file PRIVATE_FILE]")
	}
	if *path == "" {
		return readDeploymentConfig(getenv)
	}
	values, err := readPrivateEnvironment(*path)
	if err != nil {
		return deploymentConfig{}, err
	}
	// A mounted file must own the complete identity of this deployment. Never
	// combine a database from the file with a key inherited from another service.
	if values["MODELATLAS_ADMIN_TOKEN"] == "" || values["MODELATLAS_MASTER_KEY"] == "" || !filepath.IsAbs(values["MODELATLAS_DB"]) {
		return deploymentConfig{}, errors.New("serve configuration requires its own admin token, stable master key and absolute database path")
	}
	for name := range values {
		if !oneOf(name, "MODELATLAS_ADMIN_TOKEN", "MODELATLAS_MASTER_KEY", "MODELATLAS_DB", "MODELATLAS_LISTEN", "MODELATLAS_DEPLOYMENT", "MODELATLAS_PUBLIC_ORIGIN", "MODELATLAS_MAINTENANCE_FILE", "MODELATLAS_DESKTOP_READY", "MODELATLAS_BACKUP_DESTINATION", "PORT") {
			return deploymentConfig{}, errors.New("unsupported serve configuration field; runtime resource options belong in the supervisor environment")
		}
	}
	return readDeploymentConfig(func(name string) string {
		if value, exists := values[name]; exists {
			return value
		}
		// Only an automatically assigned platform port is inherited. Deployment
		// mode, origin and credentials always come from the selected file.
		if name == "PORT" {
			return getenv(name)
		}
		return ""
	})
}
