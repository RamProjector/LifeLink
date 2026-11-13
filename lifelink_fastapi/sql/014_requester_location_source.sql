-- Persist the requester's selected location-source category as bounded diagnostic metadata.
-- Historical requests and older clients remain explicitly unknown; source is not matching input.
ALTER TABLE emergency_requests
    ADD COLUMN IF NOT EXISTS requester_location_source VARCHAR(32) NOT NULL DEFAULT 'unknown';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_emergency_requests_requester_location_source'
          AND conrelid = 'emergency_requests'::regclass
    ) THEN
        ALTER TABLE emergency_requests
            ADD CONSTRAINT ck_emergency_requests_requester_location_source
            CHECK (
                requester_location_source IN (
                    'fused', 'gps', 'network', 'passive', 'unknown',
                    'manual_pin', 'manual_coordinates', 'facility'
                )
            );
    END IF;
END;
$$;
