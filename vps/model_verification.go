package main

import (
	"context"
	"encoding/xml"
	"io"
	"net/http"
	"strings"
	"time"
)

const modelVerificationSchema = `
CREATE TABLE IF NOT EXISTS model_verification_runs (
	id TEXT PRIMARY KEY REFERENCES sessions(id),
	scope_key TEXT NOT NULL,
	created_at INTEGER NOT NULL,
	outcome TEXT NOT NULL,
	sample_id TEXT NOT NULL DEFAULT '',
	drawing TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS model_verification_scope ON model_verification_runs(scope_key,created_at DESC,id DESC);
`

const pelicanPrompt = "Draw an SVG of a pelican riding a bicycle. Show the pelican seated on the bicycle, a long bill, two wheels, handlebars and pedals. Use xmlns=\"http://www.w3.org/2000/svg\" and viewBox=\"0 0 640 480\". Return only the complete SVG."
const pelicanMaxTokens = 4096

type modelVerificationRun struct {
	ID                string `json:"id"`
	At                int64  `json:"created_at"`
	Outcome           string `json:"outcome"`
	SampleID          string `json:"sample_id,omitempty"`
	Drawing           string `json:"drawing,omitempty"`
	CredentialVersion *int   `json:"credential_version,omitempty"`
}

func (s *Server) runModelVerification(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ID        string    `json:"id"`
		Selection Selection `json:"selection"`
	}
	if !decodeJSON(w, r, &in) {
		return
	}
	ctx, v := r.Context(), in.Selection
	if !validID(in.ID) || !validScope(v) || !conversationOperation(s.operation(ctx, v)) || !s.selectionUsable(ctx, v) {
		http.Error(w, "a usable text model connection is required", 422)
		return
	}
	at := now()
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		http.Error(w, "could not create verification run", 500)
		return
	}
	defer tx.Rollback()
	inserted, err := tx.ExecContext(ctx, `INSERT INTO sessions VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING`,
		in.ID, v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, "鵜鶘騎單車 · v1", at)
	if err != nil {
		http.Error(w, "could not create verification session", 500)
		return
	}
	if count, _ := inserted.RowsAffected(); count != 1 {
		http.Error(w, "verification already submitted; read history", 409)
		return
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO session_metadata(session_id,archived,revision,updated_at) VALUES(?,1,1,?)", in.ID, at); err == nil {
		_, err = tx.ExecContext(ctx, "INSERT INTO model_verification_runs(id,scope_key,created_at,outcome) VALUES(?,?,?,'UNCONFIRMED')", in.ID, scopeKey(v), at)
	}
	if err != nil || tx.Commit() != nil {
		http.Error(w, "could not save verification run", 500)
		return
	}
	// The caller's ID is reserved before sending anything. Reloading a result
	// or resubmitting the same ID can never start a second inference.
	stream := false
	result, _ := s.execute(ctx, inferRequest{SessionID: in.ID, Prompt: pelicanPrompt, MaxTokens: pelicanMaxTokens, Streaming: &stream}, "MODEL_VERIFICATION", func(string, any) error { return nil })
	run := modelVerificationRun{ID: in.ID, At: at, Outcome: result.Outcome, SampleID: str(result.Details["sample_id"])}
	if run.Outcome == "SUCCESS" {
		run.Drawing = pelicanSVG(result.Text)
		if run.Drawing == "" {
			run.Outcome = "INVALID_SVG"
		}
	}
	save, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()
	if _, err = s.Store.DB.ExecContext(save, "UPDATE model_verification_runs SET outcome=?,sample_id=?,drawing=? WHERE id=?", run.Outcome, run.SampleID, run.Drawing, run.ID); err != nil {
		http.Error(w, "verification sent; result save unconfirmed", 500)
		return
	}
	jsonOK(w, 200, run)
}

func (s *Server) listModelVerificationRuns(w http.ResponseWriter, r *http.Request) {
	v := selectionQuery(r)
	if !validScope(v) {
		http.Error(w, "full selected scope required", 400)
		return
	}
	if id := r.URL.Query().Get("id"); id != "" {
		var run modelVerificationRun
		err := s.Store.DB.QueryRowContext(r.Context(), `SELECT r.id,r.created_at,r.outcome,r.sample_id,r.drawing,t.credential_version
			FROM model_verification_runs r LEFT JOIN telemetry t ON t.id=r.sample_id WHERE r.scope_key=? AND r.id=?`, scopeKey(v), id).Scan(&run.ID, &run.At, &run.Outcome, &run.SampleID, &run.Drawing, &run.CredentialVersion)
		if err != nil {
			http.Error(w, "verification run unavailable", 404)
			return
		}
		jsonOK(w, 200, run)
		return
	}
	limit, offset := pageRange(r, 20)
	rows, err := s.Store.DB.QueryContext(r.Context(), `SELECT r.id,r.created_at,r.outcome,r.sample_id,t.credential_version
		FROM model_verification_runs r LEFT JOIN telemetry t ON t.id=r.sample_id
		WHERE r.scope_key=? ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?`, scopeKey(v), limit+1, offset)
	if err != nil {
		http.Error(w, "verification history unavailable", 500)
		return
	}
	defer rows.Close()
	runs := []modelVerificationRun{}
	for rows.Next() {
		var run modelVerificationRun
		if rows.Scan(&run.ID, &run.At, &run.Outcome, &run.SampleID, &run.CredentialVersion) != nil {
			http.Error(w, "verification history unavailable", 500)
			return
		}
		runs = append(runs, run)
	}
	if rows.Err() != nil {
		http.Error(w, "verification history unavailable", 500)
		return
	}
	jsonOK(w, 200, map[string]any{"runs": runs[:min(limit, len(runs))], "more": len(runs) > limit})
}

// A drawing is always returned as JSON and displayed only as an image. Keep
// XML documents bounded and reject active content even for downloaded copies.
func pelicanSVG(text string) string {
	start, end := strings.Index(text, "<svg"), strings.LastIndex(text, "</svg>")
	if start < 0 || end < start || end+6-start > 64<<10 {
		return ""
	}
	text = text[start : end+6]
	decoder := xml.NewDecoder(strings.NewReader(text))
	depth, elements := 0, 0
	for {
		token, err := decoder.Token()
		if err == io.EOF && depth == 0 && elements > 0 {
			return text
		}
		if err != nil {
			return ""
		}
		switch value := token.(type) {
		case xml.StartElement:
			if depth == 0 && (elements > 0 || value.Name.Local != "svg" || value.Name.Space == "") ||
				value.Name.Space != "http://www.w3.org/2000/svg" && value.Name.Space != "" ||
				oneOf(strings.ToLower(value.Name.Local), "script", "foreignobject", "a", "animate", "animatemotion", "animatetransform", "set") {
				return ""
			}
			for _, attr := range value.Attr {
				name := strings.ToLower(attr.Name.Local)
				if strings.HasPrefix(name, "on") || name == "src" || name == "base" || name == "href" && !strings.HasPrefix(strings.TrimSpace(attr.Value), "#") {
					return ""
				}
			}
			depth++
			elements++
			if depth > 64 || elements > 2048 {
				return ""
			}
		case xml.EndElement:
			depth--
		case xml.Directive, xml.ProcInst:
			return ""
		}
	}
}
