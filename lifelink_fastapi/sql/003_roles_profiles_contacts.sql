-- LifeLink role and contact model.
-- Apply after 001_initial_schema.sql and 002_gps_request_location.sql.
-- The FastAPI service remains the policy boundary; exact donor location is never returned to requesters.

DO $$ BEGIN
    CREATE TYPE lifelink_role_enum AS ENUM ('requester', 'donor');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TABLE IF NOT EXISTS lifelink_profiles (
    user_id VARCHAR(128) PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    role lifelink_role_enum NOT NULL,
    display_name VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_lifelink_profiles_role ON lifelink_profiles (role);

CREATE TABLE IF NOT EXISTS donor_contact_requests (
    id VARCHAR(128) PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL REFERENCES emergency_requests(id) ON DELETE CASCADE,
    donor_id VARCHAR(128) NOT NULL REFERENCES donors(id) ON DELETE CASCADE,
    requester_id VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'accepted', 'declined', 'cancelled', 'expired')),
    contact_shared_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (request_id, donor_id)
);

CREATE INDEX IF NOT EXISTS ix_donor_contact_requests_donor_status
    ON donor_contact_requests (donor_id, status);
CREATE INDEX IF NOT EXISTS ix_donor_contact_requests_request_status
    ON donor_contact_requests (request_id, status);

ALTER TABLE donors ADD COLUMN IF NOT EXISTS user_id VARCHAR(128);
CREATE UNIQUE INDEX IF NOT EXISTS ux_donors_user_id ON donors (user_id) WHERE user_id IS NOT NULL;
ALTER TABLE donors ADD COLUMN IF NOT EXISTS last_location_precision_meters INTEGER NOT NULL DEFAULT 500
    CHECK (last_location_precision_meters BETWEEN 10 AND 10000);
