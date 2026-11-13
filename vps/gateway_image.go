package main

import (
	"context"
	"net/http"
)

func confirmImageConnection(ctx context.Context, db statementExecutor, scope Selection) error {
	_, err := db.ExecContext(ctx, `INSERT INTO capability_confirmations VALUES(?, '["IMAGE_GENERATION"]','MANUAL_CONFIRMATION',?)
		ON CONFLICT(scope_key) DO UPDATE SET
		capabilities_json=json_insert(capabilities_json,'$[#]','IMAGE_GENERATION'),
		source=excluded.source,observed_at=excluded.observed_at
		WHERE NOT EXISTS(SELECT 1 FROM json_each(capability_confirmations.capabilities_json) WHERE value='IMAGE_GENERATION')`,
		scopeKey(scope), now())
	return err
}

func (s *Server) gatewayImageTest(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Selection
		Prompt    string `json:"prompt"`
		RequestID string `json:"request_id"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	if !validScope(in.Selection) || s.operation(r.Context(), in.Selection) != "IMAGES_GENERATION" {
		http.Error(w, "selected image generation endpoint required", 422)
		return
	}
	s.runMediaInference(w, r, MediaRequest{Selection: in.Selection, Prompt: in.Prompt, RequestID: in.RequestID}, "GATEWAY_TEST")
}
