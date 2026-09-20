-- Append-only audit events for sensitive request/contact actions.
CREATE TABLE IF NOT EXISTS audit_events (
    id VARCHAR(128) PRIMARY KEY,
    actor_id VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    request_id VARCHAR(128),
    donor_id VARCHAR(128),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_audit_events_actor_id ON audit_events (actor_id);
CREATE INDEX IF NOT EXISTS ix_audit_events_request_id ON audit_events (request_id);
CREATE INDEX IF NOT EXISTS ix_audit_events_donor_id ON audit_events (donor_id);
