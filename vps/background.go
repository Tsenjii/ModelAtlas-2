package main

import (
	"context"
	"encoding/json"
	"net/http"
	"time"
)

type BackgroundSettings struct {
	Enabled         bool `json:"enabled"`
	IntervalMinutes int  `json:"interval_minutes"`
	MaxScopes       int  `json:"max_scopes"`
}
type NotificationRules struct {
	BecameFree          bool  `json:"became_free"`
	QuotaLow            bool  `json:"quota_low"`
	CheckinPending      bool  `json:"checkin_pending"`
	FavoriteRecovered   bool  `json:"favorite_recovered"`
	PriceChanged        bool  `json:"price_changed"`
	Outage              bool  `json:"outage"`
	Recovery            bool  `json:"recovery"`
	QuotaRemainingBelow int64 `json:"quota_remaining_below"`
}
type notificationSnapshot struct {
	Version    int    `json:"version"`
	PriceState string `json:"price_state"`
	Price      string `json:"price"`
	Remaining  *int64 `json:"remaining"`
	Outcome    string `json:"outcome"`
}

func loadSetting[T any](s *Server, ctx context.Context, key string, fallback T) T {
	var raw string
	if s.Store.DB.QueryRowContext(ctx, "SELECT value_json FROM settings WHERE key=?", key).Scan(&raw) == nil {
		_ = json.Unmarshal([]byte(raw), &fallback)
	}
	return fallback
}
func (s *Server) backgroundTick(ctx context.Context) {
	cfg := loadSetting(s, ctx, "background", BackgroundSettings{IntervalMinutes: 60, MaxScopes: 2})
	if cfg.Enabled {
		var last int64
		var cursor int64
		_ = s.Store.DB.QueryRowContext(ctx, "SELECT due_at,cursor FROM background_state WHERE id='sync'").Scan(&last, &cursor)
		if last <= now() {
			scopes, next := s.backgroundScopes(ctx, cursor, cfg.MaxScopes)
			for _, v := range scopes {
				if s.validateSelection(ctx, v) && s.groupBound(ctx, v) {
					var n int
					_ = s.Store.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM sync_history WHERE scope_key=? AND status='PENDING'", scopeKey(v)).Scan(&n)
					if n == 0 {
						s.scheduleEnrichment(ctx, v)
					}
				}
			}
			_, _ = s.Store.DB.ExecContext(ctx, "INSERT INTO background_state VALUES('sync',?,?) ON CONFLICT(id) DO UPDATE SET due_at=excluded.due_at,cursor=excluded.cursor", now()+int64(cfg.IntervalMinutes)*60000, next)
		}
	}
	if loadSetting(s, ctx, "notifications", false) {
		s.checkNotificationRules(ctx)
	}
}
func (s *Server) backgroundScopes(ctx context.Context, cursor int64, limit int) ([]Selection, int64) {
	read := func(after int64) ([]Selection, int64) {
		rows, e := s.Store.DB.QueryContext(ctx, "SELECT rowid,provider_id,account_id,credential_id,model_id,group_id,endpoint FROM my_models WHERE rowid>? ORDER BY rowid LIMIT ?", after, limit)
		list := []Selection{}
		next := after
		if e != nil {
			return list, next
		}
		defer rows.Close()
		for rows.Next() {
			var v Selection
			var id int64
			if rows.Scan(&id, &v.ProviderID, &v.AccountID, &v.CredentialID, &v.ModelID, &v.Group, &v.Endpoint) == nil {
				list = append(list, v)
				next = id
			}
		}
		return list, next
	}
	list, next := read(cursor)
	if len(list) == 0 && cursor > 0 {
		return read(0)
	}
	return list, next
}
func (s *Server) notify(ctx context.Context, v Selection, kind, body, dedupe string) {
	_, _ = s.Store.DB.ExecContext(ctx, "INSERT INTO notification_events(id,scope_key,kind,body,dedupe,created_at) VALUES(?,?,?,?,?,?) ON CONFLICT(dedupe) DO NOTHING", newID(), scopeKey(v), kind, body, dedupe, now())
}
func (s *Server) checkNotificationRules(ctx context.Context) {
	rules := loadSetting(s, ctx, "notification_rules", NotificationRules{QuotaRemainingBelow: 100})
	var cursor int64
	_ = s.Store.DB.QueryRowContext(ctx, "SELECT cursor FROM background_state WHERE id='notify'").Scan(&cursor)
	scopes, next := s.backgroundScopes(ctx, cursor, 20)
	_, _ = s.Store.DB.ExecContext(ctx, "INSERT INTO background_state VALUES('notify',0,?) ON CONFLICT(id) DO UPDATE SET cursor=excluded.cursor", next)
	zoneName := str(s.setting(ctx, "timezone", "Asia/Taipei"))
	zone, e := time.LoadLocation(zoneName)
	if e != nil {
		zone = time.UTC
	}
	date := time.Now().In(zone).Format("2006-01-02")
	for _, v := range scopes {
		if !s.validateSelection(ctx, v) {
			continue
		}
		offer := s.offerSummary(ctx, v)
		current := notificationSnapshot{Version: offer["credential_version"].(int), PriceState: "UNKNOWN"}
		if p, ok := offer["price"].(map[string]any); ok {
			raw, _ := json.Marshal(p["legs"])
			var legs PriceLegs
			_ = json.Unmarshal(raw, &legs)
			current.PriceState = priceState(legs)
			current.Price = string(raw)
		}
		if q, ok := offer["quota"].(map[string]any); ok {
			current.Remaining = quotaInteger(q["remaining"])
		}
		var sampleID string
		_ = s.Store.DB.QueryRowContext(ctx, "SELECT id,outcome FROM telemetry WHERE provider_id=? AND account_id=? AND credential_id=? AND model_id=? AND group_id=? AND endpoint=? AND credential_version=? AND outcome NOT IN ('RUNNING','CANCELLED','INTERRUPTED') ORDER BY rowid DESC LIMIT 1", v.ProviderID, v.AccountID, v.CredentialID, v.ModelID, v.Group, v.Endpoint, current.Version).Scan(&sampleID, &current.Outcome)
		var previous notificationSnapshot
		var old string
		exists := s.Store.DB.QueryRowContext(ctx, "SELECT state_json FROM notification_state WHERE scope_key=?", scopeKey(v)).Scan(&old) == nil && json.Unmarshal([]byte(old), &previous) == nil && previous.Version == current.Version
		raw, _ := json.Marshal(current)
		eventKey := scopeKey(v) + "/" + contentHash([]byte(old)) + "/" + contentHash(raw) + "/" + sampleID
		if exists {
			if rules.BecameFree && previous.PriceState == "PAID" && current.PriceState == "FREE_NOW" {
				s.notify(ctx, v, "BECAME_FREE", v.ModelID+" 已確認 FREE NOW", eventKey+"free")
			}
			if rules.PriceChanged && previous.Price != "" && current.Price != "" && previous.Price != current.Price {
				s.notify(ctx, v, "PRICE_CHANGED", v.ModelID+" 價格已變更", eventKey+"price")
			}
			if rules.QuotaLow && previous.Remaining != nil && current.Remaining != nil && *previous.Remaining > rules.QuotaRemainingBelow && *current.Remaining <= rules.QuotaRemainingBelow {
				s.notify(ctx, v, "QUOTA_LOW", v.ModelID+" 額度低於設定門檻", eventKey+"quota")
			}
			failed := current.Outcome != "" && current.Outcome != "SUCCESS"
			if rules.Outage && previous.Outcome == "SUCCESS" && failed {
				s.notify(ctx, v, "OUTAGE", v.ModelID+" 最近一次請求失敗", eventKey+"outage")
			}
			if previous.Outcome != "" && previous.Outcome != "SUCCESS" && current.Outcome == "SUCCESS" {
				if rules.Recovery {
					s.notify(ctx, v, "RECOVERY", v.ModelID+" 請求已恢復成功", eventKey+"recovery")
				}
				var favorite bool
				_ = s.Store.DB.QueryRowContext(ctx, "SELECT favorite FROM membership_state WHERE scope_key=?", scopeKey(v)).Scan(&favorite)
				if favorite && rules.FavoriteRecovered {
					s.notify(ctx, v, "FAVORITE_RECOVERED", v.ModelID+" 最愛模型已恢復", eventKey+"favorite")
				}
			}
		}
		// Missing/stale values do not overwrite the last confirmed value within this credential epoch.
		if exists {
			if current.Price == "" {
				current.Price = previous.Price
				current.PriceState = previous.PriceState
			}
			if current.Remaining == nil {
				current.Remaining = previous.Remaining
			}
			if current.Outcome == "" {
				current.Outcome = previous.Outcome
			}
		}
		raw, _ = json.Marshal(current)
		_, _ = s.Store.DB.ExecContext(ctx, "INSERT INTO notification_state VALUES(?,?) ON CONFLICT(scope_key) DO UPDATE SET state_json=excluded.state_json", scopeKey(v), string(raw))
		p, e := s.provider(ctx, v.ProviderID)
		if e == nil && rules.CheckinPending && s.config(ctx, p).RequiresCheckin {
			var n int
			_ = s.Store.DB.QueryRowContext(ctx, "SELECT COUNT(*) FROM checkins WHERE provider_id=? AND date=?", v.ProviderID, date).Scan(&n)
			if n == 0 {
				s.notify(ctx, v, "CHECKIN_PENDING", p.Name+" 今天尚未記錄簽到", v.ProviderID+"/checkin/"+date)
			}
		}
	}
}
func (s *Server) notificationsHTTP(w http.ResponseWriter, r *http.Request) {
	rows, e := s.Store.DB.QueryContext(r.Context(), "SELECT id,scope_key,kind,body,created_at,seen_at FROM notification_events ORDER BY rowid DESC LIMIT 100")
	if e != nil {
		http.Error(w, "database error", 500)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var id, scope, kind, body string
		var at int64
		var seen any
		if rows.Scan(&id, &scope, &kind, &body, &at, &seen) == nil {
			var v Selection
			_ = json.Unmarshal([]byte(scope), &v)
			out = append(out, map[string]any{"id": id, "selection": v, "kind": kind, "body": body, "created_at": at, "seen_at": seen})
		}
	}
	jsonOK(w, 200, out)
}
func (s *Server) seenNotifications(w http.ResponseWriter, r *http.Request) {
	var v struct {
		IDs []string `json:"ids"`
	}
	if !decodeJSON(w, r, &v) {
		return
	}
	if len(v.IDs) > 100 {
		http.Error(w, "too many events", 400)
		return
	}
	for _, id := range v.IDs {
		if !validID(id) {
			http.Error(w, "invalid event", 400)
			return
		}
	}
	for _, id := range v.IDs {
		_, _ = s.Store.DB.ExecContext(r.Context(), "UPDATE notification_events SET seen_at=? WHERE id=? AND seen_at IS NULL", now(), id)
	}
	jsonOK(w, 200, map[string]bool{"saved": true})
}
