-- Application session registry used by the IDS/IPS control plane.
-- Session IDs come from verified Supabase JWT claims; this table does not
-- replace Supabase Auth or claim to revoke Supabase refresh tokens.
CREATE TABLE IF NOT EXISTS lifelink_user_sessions (
    session_id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    device_used VARCHAR(255) NOT NULL DEFAULT 'unknown',
    ip_address VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoked_by VARCHAR(128),
    status VARCHAR(16) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'revoked', 'expired'))
);
CREATE INDEX IF NOT EXISTS ix_lifelink_user_sessions_user_seen
    ON lifelink_user_sessions (user_id, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS ix_lifelink_user_sessions_status_seen
    ON lifelink_user_sessions (status, last_seen_at DESC);
