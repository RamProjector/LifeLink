-- Separate GPS/presence freshness from availability-toggle timestamps.
-- Legacy rows remain unknown (NULL) until an actual server-recorded GPS presence update.
ALTER TABLE donors ADD COLUMN IF NOT EXISTS last_presence_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS ix_donors_last_presence_at ON donors (last_presence_at);
