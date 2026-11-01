package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 新傳輸協商是獨立、只讀且受同一裝置撤銷約束的端點；不更改嚴格v1 pair／exchange欄位。
func TestWorkspaceChunkCapabilitiesRequireCurrentDeviceAndNoWork(t *testing.T) {
	for _, share := range []bool{false, true} {
		name := "metadata-only"
		if share {
			name = "with-secrets"
		}
		t.Run(name, func(t *testing.T) {
			hub, peer := testServer(t), testServer(t)
			host := httptest.NewTLSServer(hub.Handler())
			defer host.Close()
			workspacePairForTest(t, hub, peer, host, share)
			var deviceID, encrypted string
			if err := peer.Store.DB.QueryRow("SELECT device_id,encrypted_token FROM workspace_peer WHERE id=1").Scan(&deviceID, &encrypted); err != nil {
				t.Fatal(err)
			}
			token, err := peer.decrypt(encrypted)
			if err != nil {
				t.Fatal("paired device token could not be read")
			}
			call := func(path string, body any) (int, []byte) {
				t.Helper()
				raw, err := json.Marshal(body)
				if err != nil {
					t.Fatal(err)
				}
				request, err := http.NewRequest("POST", host.URL+"/api/workspace/"+path, strings.NewReader(string(raw)))
				if err != nil {
					t.Fatal(err)
				}
				request.Header.Set("Content-Type", "application/json")
				request.Header.Set("Authorization", "Bearer "+token)
				response, err := host.Client().Do(request)
				if err != nil {
					t.Fatal("real TLS workspace request failed")
				}
				defer response.Body.Close()
				data, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
				if err != nil {
					t.Fatal("workspace response was incomplete")
				}
				return response.StatusCode, data
			}
			// 同一真TLS通道的既有exchange先成功，證明token／配對有效。
			status, _ := call("exchange", map[string]any{"protocol": 1, "cursor": 0, "changes": []any{}, "secrets": map[string]any{}, "fetch": []any{}})
			if status != 200 {
				t.Fatalf("existing v1 exchange control returned %d", status)
			}
			counts := map[string]int{}
			for _, table := range []string{"workspace_changes", "workspace_rows", "telemetry"} {
				var count int
				if err := hub.Store.DB.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&count); err != nil {
					t.Fatal(err)
				}
				counts[table] = count
			}
			status, raw := call("capabilities", map[string]any{})
			if status != 200 {
				t.Fatalf("authenticated chunk capability endpoint returned %d; legacy exchange control passed", status)
			}
			var capabilities map[string]any
			if json.Unmarshal(raw, &capabilities) != nil {
				t.Fatal("capability response must be JSON")
			}
			want := map[string]float64{"protocol": 1, "chunked_exchange": 1, "chunk_bytes": 256 << 10, "request_max_bytes": 65 << 20, "reply_max_bytes": 72 << 20, "record_max_bytes": 64 << 20}
			for key, value := range want {
				if capabilities[key] != value {
					t.Fatalf("unsupported or unbounded capability %s", key)
				}
			}
			for table, before := range counts {
				assertSQLInt(t, hub, before, "SELECT COUNT(*) FROM "+table)
			}
			requireCode(t, req(t, hub, "POST", "/api/v1/workspace/revoke", map[string]any{"device_id": deviceID, "confirm": true}), 200)
			status, _ = call("capabilities", map[string]any{})
			if status != 401 {
				t.Fatalf("revoked device retained capabilities access: %d", status)
			}
		})
	}
}
