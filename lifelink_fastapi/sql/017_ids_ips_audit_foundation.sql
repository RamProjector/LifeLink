-- IDS/IPS audit foundation.
-- Additive only: retain legacy audit_events columns and existing records.
ALTER TABLE audit_events
    ADD COLUMN IF NOT EXISTS log_id UUID,
    ADD COLUMN IF NOT EXISTS actions VARCHAR(16) NOT NULL DEFAULT 'OTHER',
    ADD COLUMN IF NOT EXISTS description TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS session_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS ip_address VARCHAR(64),
    ADD COLUMN IF NOT EXISTS timestamp TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS device_used VARCHAR(255) NOT NULL DEFAULT 'unknown';

UPDATE audit_events
SET log_id = gen_random_uuid()
WHERE log_id IS NULL;

ALTER TABLE audit_events
    ALTER COLUMN log_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_audit_events_log_id
    ON audit_events (log_id);

CREATE INDEX IF NOT EXISTS ix_audit_events_session_timestamp
    ON audit_events (session_id, timestamp DESC);

CREATE INDEX IF NOT EXISTS ix_audit_events_actor_timestamp
    ON audit_events (actor_id, timestamp DESC);

-- Existing id/action/created_at fields remain authoritative for backward
-- compatibility. The new columns provide the required IDS/IPS event envelope.
