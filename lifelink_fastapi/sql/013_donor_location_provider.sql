-- Record a bounded, client-reported Android location provider category.
--
-- This is diagnostic provenance, not proof of the underlying physical sensor:
-- Android's fused provider can combine multiple sources (GNSS, Wi-Fi, cellular).
-- Existing rows remain NULL until a client reports a provider with a new fix.
ALTER TABLE donors
    ADD COLUMN IF NOT EXISTS last_location_provider VARCHAR(24);

ALTER TABLE donors
    ADD CONSTRAINT ck_donors_last_location_provider
    CHECK (
        last_location_provider IS NULL
        OR last_location_provider IN ('fused', 'gps', 'network', 'passive')
    );
