-- Separate donor-profile flow.
--
-- Account creation and donor registration are decoupled: a user creates a
-- normal account first and then opts in to donating. This table is the
-- canonical opt-in record, keyed by the account (user_id). The operational
-- `donors` row that the matching engine reads is kept in sync by the
-- donor-profile store, so matching logic is not duplicated.
--
-- Privacy: this table is reachable only through the FastAPI service. A client
-- role must never read it directly; matching recipients only ever receive the
-- minimum details (blood type, distance, travel estimate), never coordinates
-- or contact details.

DO $$ BEGIN
    CREATE TYPE blood_type_enum AS ENUM ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-', 'UNKNOWN');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TABLE IF NOT EXISTS donor_profiles (
    user_id VARCHAR(128) PRIMARY KEY,
    donor_id VARCHAR(128) NOT NULL,
    blood_type blood_type_enum NOT NULL,
    latitude NUMERIC(9, 6) NOT NULL,
    longitude NUMERIC(9, 6) NOT NULL,
    area VARCHAR(120) NOT NULL DEFAULT '',
    availability_status VARCHAR(16) NOT NULL DEFAULT 'offline'
        CHECK (availability_status IN ('available', 'paused', 'offline')),
    last_donation_date DATE,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    service_radius_km NUMERIC(6, 2) NOT NULL DEFAULT 15,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_donor_profiles_donor_id ON donor_profiles (donor_id);
CREATE INDEX IF NOT EXISTS ix_donor_profiles_availability
    ON donor_profiles (availability_status, verified);
