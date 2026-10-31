-- Separate GPS/presence freshness from availability-toggle timestamps.
-- Additive and backwards-compatible: legacy rows inherit their last known availability timestamp.
ALTER TABLE donors ADD COLUMN IF NOT EXISTS last_presence_at TIMESTAMPTZ;
UPDATE donors
SET last_presence_at = availability_updated_at
WHERE last_presence_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_donors_last_presence_at ON donors (last_presence_at);
