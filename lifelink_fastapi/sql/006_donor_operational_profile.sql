-- Operational donor profile metadata only; no clinical or medical-history data.
ALTER TABLE donors ADD COLUMN IF NOT EXISTS donor_note TEXT NOT NULL DEFAULT '';
ALTER TABLE donors ADD COLUMN IF NOT EXISTS preferred_contact_method VARCHAR(32) NOT NULL DEFAULT 'in_app';
ALTER TABLE donors ADD COLUMN IF NOT EXISTS pause_reason VARCHAR(240);
ALTER TABLE donors ADD COLUMN IF NOT EXISTS profile_visible BOOLEAN NOT NULL DEFAULT TRUE;
