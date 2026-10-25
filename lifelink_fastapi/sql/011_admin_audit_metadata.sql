-- Admin audit metadata. Compatible with the existing audit_events table.
-- Admin authorization itself is configured through server-side LIFELINK_ADMIN_USER_IDS.
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS outcome VARCHAR(16) NOT NULL DEFAULT 'success';
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS target_type VARCHAR(64);
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS target_id VARCHAR(128);
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(80);
CREATE INDEX IF NOT EXISTS ix_audit_events_created_at ON audit_events (created_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_events_action_created_at ON audit_events (action, created_at DESC);

-- Keep this table append-only to application roles. Administrative access is
-- through authenticated API endpoints; do not grant clients direct mutation.
