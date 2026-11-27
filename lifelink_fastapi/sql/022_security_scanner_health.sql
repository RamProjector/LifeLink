-- Persist the last outcome of the independent IDS/IPS RBAC scanner.
-- Backend-only operational telemetry; no client roles receive table access.
BEGIN;

CREATE TABLE IF NOT EXISTS lifelink_security_scanner_runs (
    scanner_key VARCHAR(64) PRIMARY KEY,
    status VARCHAR(16) NOT NULL DEFAULT 'never_run'
        CHECK (status IN ('never_run', 'running', 'succeeded', 'failed')),
    last_started_at TIMESTAMPTZ,
    last_finished_at TIMESTAMPTZ,
    last_succeeded_at TIMESTAMPTZ,
    last_failed_at TIMESTAMPTZ,
    last_findings INTEGER NOT NULL DEFAULT 0 CHECK (last_findings >= 0),
    last_duration_ms INTEGER CHECK (last_duration_ms IS NULL OR last_duration_ms >= 0),
    consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    last_error_code VARCHAR(64),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_lifelink_security_scanner_runs_status
    ON lifelink_security_scanner_runs (status, updated_at DESC);

ALTER TABLE lifelink_security_scanner_runs ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE lifelink_security_scanner_runs FROM PUBLIC, anon, authenticated;

COMMIT;
