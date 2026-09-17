-- GPS-first request location migration.
-- Facility discovery is intentionally deferred; facility_id remains optional for future use.
ALTER TABLE emergency_requests
    ALTER COLUMN facility_id DROP NOT NULL;

ALTER TABLE emergency_requests
    ADD COLUMN IF NOT EXISTS requester_latitude NUMERIC(9, 6),
    ADD COLUMN IF NOT EXISTS requester_longitude NUMERIC(9, 6),
    ADD COLUMN IF NOT EXISTS location_precision_meters INTEGER NOT NULL DEFAULT 100;

-- Existing rows must be backfilled from their facility before enforcing coordinates.
UPDATE emergency_requests r
SET requester_latitude = f.latitude,
    requester_longitude = f.longitude
FROM facilities f
WHERE r.facility_id = f.id
  AND (r.requester_latitude IS NULL OR r.requester_longitude IS NULL);

ALTER TABLE emergency_requests
    ALTER COLUMN requester_latitude SET NOT NULL,
    ALTER COLUMN requester_longitude SET NOT NULL;

ALTER TABLE emergency_requests
    ADD CONSTRAINT emergency_requests_location_precision_check
    CHECK (location_precision_meters BETWEEN 10 AND 10000);

ALTER TABLE emergency_requests
    ADD CONSTRAINT emergency_requests_latitude_check
    CHECK (requester_latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT emergency_requests_longitude_check
    CHECK (requester_longitude BETWEEN -180 AND 180);

CREATE INDEX IF NOT EXISTS ix_requests_location
    ON emergency_requests (requester_latitude, requester_longitude);

COMMENT ON COLUMN emergency_requests.requester_latitude IS 'Private requester location used only for donor matching.';
COMMENT ON COLUMN emergency_requests.requester_longitude IS 'Private requester location used only for donor matching.';
COMMENT ON COLUMN emergency_requests.location_precision_meters IS 'Approximate accuracy supplied by the device; raw location is never donor-facing.';
