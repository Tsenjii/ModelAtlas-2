package main

import "context"

type conversationAdmissionError struct {
	Outcome string
	Status  int
	Cause   error
}

func (e *conversationAdmissionError) Error() string { return e.Cause.Error() }
func (e *conversationAdmissionError) Unwrap() error { return e.Cause }

// Reserve research meters and persist the exact outgoing conversation together.
// Local rejection or any failed write leaves neither spent quota nor a partial
// request. Once committed, the reservation remains even if HTTP fails or the
// process stops; that is an admitted attempt, not proof of provider billing.
func (s *Server) admitConversation(ctx context.Context, record requestRecord, messages []Message, tools []ToolDefinition) error {
	failure := func(outcome string, status int, cause error) error {
		return &conversationAdmissionError{outcome, status, cause}
	}
	tx, err := s.Store.DB.BeginTx(ctx, nil)
	if err != nil {
		return failure("TELEMETRY_SAVE", 500, err)
	}
	defer tx.Rollback()
	if err = s.chargeResearchNetworkTx(ctx, tx, record.Source == "CRAWLER_VERIFY", ""); err != nil {
		return failure("RESEARCH_BUDGET_EXHAUSTED", 429, err)
	}
	if err = s.reserveResearchInference(ctx, tx, record); err != nil {
		return failure("RESEARCH_BUDGET_EXHAUSTED", 429, err)
	}
	if err = s.reserveResearchResources(ctx, tx, record); err != nil {
		return failure("RESEARCH_BUDGET_EXHAUSTED", 429, err)
	}
	if tools != nil {
		if err = saveSessionTools(ctx, tx, record.SessionID, tools); err != nil {
			return failure("HISTORY_SAVE", 500, err)
		}
	}
	for _, message := range messages {
		if err = saveMessage(ctx, tx, record.SessionID, message); err != nil {
			return failure("HISTORY_SAVE", 500, err)
		}
	}
	if err = record.insert(ctx, tx); err != nil {
		return failure("TELEMETRY_SAVE", 500, err)
	}
	if err = tx.Commit(); err != nil {
		return failure("TELEMETRY_SAVE", 500, err)
	}
	return nil
}
