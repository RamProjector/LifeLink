-- Deduplicated IDS/IPS alert lifecycle.
CREATE TABLE IF NOT EXISTS lifelink_security_alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('critical', 'high', 'medium', 'low')),
    status VARCHAR(20) NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'acknowledged', 'investigating', 'resolved', 'false_positive')),
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128),
    summary TEXT NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurrences INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_at TIMESTAMPTZ,
    acknowledged_by VARCHAR(128),
    resolved_at TIMESTAMPTZ,
    resolved_by VARCHAR(128),
    correlation_id VARCHAR(80)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_lifelink_security_alerts_open_rule_user
    ON lifelink_security_alerts (rule_id, user_id)
    WHERE status IN ('open', 'acknowledged', 'investigating');
CREATE INDEX IF NOT EXISTS ix_lifelink_security_alerts_status_created
    ON lifelink_security_alerts (status, created_at DESC);
