package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const researchRetryLimit = 3 // Initial retrieval plus at most two automatic retries.

// Only public source GETs produce this error. Inference, authenticated catalog
// requests and integration pagination never become implicit retry candidates.
// Neither response bodies nor raw headers are persisted in the retry journal.
type researchHTTPFailure struct {
	Status      int
	NotBefore   int64
	WaitTooLong bool
}

func (e *researchHTTPFailure) Error() string { return fmt.Sprintf("public endpoint HTTP %d", e.Status) }

func publicHTTPFailure(status int, h http.Header, stamp time.Time) error {
	if status != 429 && status != 502 && status != 503 && status != 504 {
		return errors.New("public endpoint unavailable")
	}
	e := &researchHTTPFailure{Status: status}
	value := strings.TrimSpace(h.Get("Retry-After"))
	// RFC 9110 section 10.2.3: decimal delay-seconds or HTTP-date. A remote
	// delay beyond 24h stops automatic retries rather than retrying too early.
	if value != "" && strings.Trim(value, "0123456789") == "" {
		seconds, err := strconv.ParseUint(value, 10, 64)
		e.WaitTooLong = err != nil || seconds > 86400
		if err != nil || seconds > uint64((math.MaxInt64-stamp.UnixMilli())/1000) {
			e.NotBefore = math.MaxInt64
		} else {
			e.NotBefore = stamp.UnixMilli() + int64(seconds)*1000
		}
	} else if date, err := http.ParseTime(value); err == nil {
		e.WaitTooLong = date.After(stamp.Add(24 * time.Hour))
		if date.After(stamp) {
			e.NotBefore = date.UnixMilli()
		}
	}
	return e
}

type researchRetryAttempt struct {
	HTTPStatus int   `json:"http_status"`
	ObservedAt int64 `json:"observed_at"`
	RetryAt    int64 `json:"retry_at,omitempty"`
}

type researchRetryState struct {
	NextAttemptAt int64                  `json:"next_attempt_at,omitempty"`
	StoppedReason string                 `json:"stopped_reason,omitempty"`
	History       []researchRetryAttempt `json:"history"`
}

func readResearchRetry(raw []byte) *researchRetryState {
	var envelope struct {
		Retry *researchRetryState `json:"retry"`
	}
	if json.Unmarshal(raw, &envelope) != nil || envelope.Retry == nil {
		return nil
	}
	return envelope.Retry
}

// result_json is already durable and synchronized as read-only research
// history. QUEUED + next_attempt_at uses the existing execution intent gates;
// no new status, process, sleeper or remotely activated job is introduced.
func (s *Server) researchRetryResult(t ResearchTask, taskErr error, result map[string]any, status string) (map[string]any, string) {
	retry := readResearchRetry(t.Result)
	var failure *researchHTTPFailure
	if errors.As(taskErr, &failure) && oneOf(t.Kind, "SOURCE", "INSPECT", "INSPECT_LINK") {
		if retry == nil {
			retry = &researchRetryState{History: []researchRetryAttempt{}}
		}
		stamp := s.scheduleTime().UnixMilli()
		event := researchRetryAttempt{HTTPStatus: failure.Status, ObservedAt: stamp}
		retry.NextAttemptAt = 0
		if len(retry.History) >= researchRetryLimit-1 {
			retry.StoppedReason = "ATTEMPT_LIMIT"
		} else if failure.WaitTooLong {
			retry.StoppedReason = "REMOTE_WAIT_TOO_LONG"
		} else {
			// Stable per-task jitter spreads restarts without randomness or an
			// in-memory timer. Retry-After is always a lower bound.
			jitter := int64([]byte(contentHash([]byte(t.ID)))[0] % 6)
			delay := int64(30*(1<<len(retry.History))) + jitter
			event.RetryAt = max(stamp+delay*1000, failure.NotBefore)
			retry.NextAttemptAt = event.RetryAt
			retry.StoppedReason = ""
			if status == "FAILED" {
				status = "QUEUED"
			}
		}
		if len(retry.History) < researchRetryLimit {
			retry.History = append(retry.History, event)
		}
	} else if retry != nil && taskErr == nil {
		retry.NextAttemptAt = 0
		retry.StoppedReason = ""
	}
	if retry != nil {
		if result == nil {
			result = map[string]any{}
		}
		result["retry"] = retry
	}
	return result, status
}
