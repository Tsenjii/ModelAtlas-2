package main

import (
	"errors"
	"net/http"
)

// Only fixed codes and HTTP metadata cross the desktop boundary. Upstream
// bodies, URLs with query secrets, and credential values never become errors.
type providerReadError struct {
	Code           string
	UpstreamStatus int
}

func (e *providerReadError) Error() string {
	switch e.Code {
	case "UPSTREAM_HTTP":
		return "upstream HTTP failure"
	case "UPSTREAM_NETWORK", "UPSTREAM_TIMEOUT":
		return "upstream network failure"
	case "UNSUPPORTED_PRIVATE_VERSION":
		return "New API version not certified for private endpoints"
	case "CREDENTIAL_ROLE":
		return "credential role/scope unavailable"
	default:
		return e.Code
	}
}

func writeProviderProblem(w http.ResponseWriter, err error, operation string) {
	problem := providerReadError{Code: "INVALID_PROVIDER_RESPONSE"}
	var known *providerReadError
	if errors.As(err, &known) {
		problem = *known
	}
	status := http.StatusBadGateway
	switch problem.Code {
	case "RECIPE_STORAGE_UNAVAILABLE", "KILO_CATALOG_UNAVAILABLE", "KILO_FREE_MODEL_UNAVAILABLE", "KILO_DEFAULT_CONNECTION_CHANGED":
		status = http.StatusServiceUnavailable
	case "RECIPE_REVIEW_REQUIRED":
		status = http.StatusConflict
	case "CREDENTIAL_ROLE", "CREDENTIAL_ORIGIN", "UNSUPPORTED_PRIVATE_VERSION", "INVALID_WINDOW", "MODEL_REQUIRED":
		status = http.StatusUnprocessableEntity
	case "PROVIDER_NOT_FOUND":
		status = http.StatusNotFound
	}
	jsonOK(w, status, map[string]any{"error": map[string]any{
		"code": problem.Code, "upstream_status": problem.UpstreamStatus, "operation": operation,
	}})
}
