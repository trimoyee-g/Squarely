-- Cross-references the ledger-service Settlement created when a user settles an
-- obligation, so the UI can show live claim/acknowledge/dispute progress inline
-- instead of a dead "settled" badge. No FK constraint: settlements live in a
-- different service/database, same convention as ledger-service's own
-- claimed_by_user_id / acknowledged_by_user_id columns.
ALTER TABLE payment_obligations ADD COLUMN settlement_id BIGINT;
CREATE INDEX idx_obligations_settlement ON payment_obligations(settlement_id);
