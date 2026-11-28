-- Role-level RBAC policy integrity findings.
-- Kept separate from user-scoped security alerts: role identifiers are never
-- overloaded into lifelink_security_alerts.user_id.
BEGIN;

CREATE TABLE IF NOT EXISTS lifelink_role_security_findings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id VARCHAR(64) NOT NULL,
    role_key VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('critical', 'high', 'medium', 'low')),
    status VARCHAR(20) NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'acknowledged', 'investigating', 'resolved', 'false_positive')),
    summary TEXT NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurrences INTEGER NOT NULL DEFAULT 1 CHECK (occurrences >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    correlation_id VARCHAR(80)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_lifelink_role_security_findings_open_rule_role
    ON lifelink_role_security_findings (rule_id, role_key)
    WHERE status IN ('open', 'acknowledged', 'investigating');
CREATE INDEX IF NOT EXISTS ix_lifelink_role_security_findings_status_created
    ON lifelink_role_security_findings (status, created_at DESC);

ALTER TABLE lifelink_role_security_findings ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE lifelink_role_security_findings FROM PUBLIC, anon, authenticated;

COMMIT;
