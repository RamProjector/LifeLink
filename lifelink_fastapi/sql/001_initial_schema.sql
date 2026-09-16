-- LifeLink PostgreSQL schema
-- Requires PostgreSQL 14+.
-- PostGIS is recommended for production geospatial ranking.

-- Supabase commonly installs PostGIS in the `extensions` schema. Including it
-- in the search path keeps this schema portable across local PostgreSQL,
-- Supabase, and other hosted PostGIS providers.
SET search_path = public, extensions;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS postgis;

DO $$ BEGIN
    CREATE TYPE blood_type_enum AS ENUM ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-', 'UNKNOWN');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE urgency_enum AS ENUM ('critical', 'urgent', 'planned');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE contact_method_enum AS ENUM ('in_app', 'phone');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE request_status_enum AS ENUM (
        'draft', 'matching', 'awaiting_responses', 'partially_fulfilled',
        'fulfilled', 'expired', 'cancelled', 'manual_broadcast'
    );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE match_status_enum AS ENUM (
        'ranked', 'notified', 'considering', 'confirmed', 'declined', 'withdrawn'
    );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TABLE IF NOT EXISTS facilities (
    id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    area VARCHAR(120) NOT NULL,
    latitude NUMERIC(9, 6) NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude NUMERIC(9, 6) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    location geography(POINT, 4326),
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_facilities_location_gist
    ON facilities USING GIST (location);

CREATE TABLE IF NOT EXISTS donors (
    id VARCHAR(128) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    blood_type blood_type_enum NOT NULL,
    latitude NUMERIC(9, 6) NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude NUMERIC(9, 6) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    location geography(POINT, 4326),
    available BOOLEAN NOT NULL DEFAULT FALSE,
    availability_updated_at TIMESTAMPTZ NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    service_radius_km NUMERIC(6, 2) NOT NULL DEFAULT 15 CHECK (service_radius_km > 0),
    estimated_response_probability NUMERIC(4, 3) NOT NULL DEFAULT 0.5
        CHECK (estimated_response_probability BETWEEN 0 AND 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_donors_active_blood_type
    ON donors (available, verified, blood_type);
CREATE INDEX IF NOT EXISTS ix_donors_availability_updated_at
    ON donors (availability_updated_at);
CREATE INDEX IF NOT EXISTS ix_donors_location_gist
    ON donors USING GIST (location);

CREATE TABLE IF NOT EXISTS emergency_requests (
    id VARCHAR(128) PRIMARY KEY,
    requester_id VARCHAR(128) NOT NULL,
    facility_id VARCHAR(128) NOT NULL REFERENCES facilities(id),
    blood_type blood_type_enum NOT NULL,
    units INTEGER NOT NULL CHECK (units BETWEEN 1 AND 20),
    urgency urgency_enum NOT NULL,
    response_deadline TIMESTAMPTZ NOT NULL,
    contact_method contact_method_enum NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    genuine_request_confirmed BOOLEAN NOT NULL,
    sharing_consent_confirmed BOOLEAN NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    status request_status_enum NOT NULL,
    matching_version VARCHAR(64) NOT NULL DEFAULT 'v1-explainable-weighted',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (genuine_request_confirmed = TRUE),
    CHECK (sharing_consent_confirmed = TRUE)
);

CREATE INDEX IF NOT EXISTS ix_requests_status_deadline
    ON emergency_requests (status, response_deadline);
CREATE INDEX IF NOT EXISTS ix_requests_requester_created
    ON emergency_requests (requester_id, created_at DESC);

CREATE TABLE IF NOT EXISTS request_matches (
    id VARCHAR(128) PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL REFERENCES emergency_requests(id) ON DELETE CASCADE,
    donor_id VARCHAR(128) NOT NULL REFERENCES donors(id),
    rank INTEGER NOT NULL CHECK (rank > 0),
    score NUMERIC(7, 2) NOT NULL CHECK (score >= 0),
    distance_km NUMERIC(8, 2) NOT NULL CHECK (distance_km >= 0),
    estimated_travel_minutes INTEGER NOT NULL CHECK (estimated_travel_minutes >= 0),
    status match_status_enum NOT NULL,
    explanation JSONB NOT NULL DEFAULT '{}'::jsonb,
    notified_at TIMESTAMPTZ,
    responded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (request_id, donor_id),
    UNIQUE (request_id, rank)
);

CREATE INDEX IF NOT EXISTS ix_request_matches_request_rank
    ON request_matches (request_id, rank);
CREATE INDEX IF NOT EXISTS ix_request_matches_donor_status
    ON request_matches (donor_id, status);

CREATE TABLE IF NOT EXISTS pending_submissions (
    id VARCHAR(128) PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    next_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Keep geography columns synchronized with latitude/longitude.
CREATE OR REPLACE FUNCTION set_facility_location() RETURNS trigger AS $$
BEGIN
    NEW.location := ST_SetSRID(ST_MakePoint(NEW.longitude, NEW.latitude), 4326)::geography;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_facility_location ON facilities;
CREATE TRIGGER trg_facility_location
    BEFORE INSERT OR UPDATE OF latitude, longitude ON facilities
    FOR EACH ROW EXECUTE FUNCTION set_facility_location();

CREATE OR REPLACE FUNCTION set_donor_location() RETURNS trigger AS $$
BEGIN
    NEW.location := ST_SetSRID(ST_MakePoint(NEW.longitude, NEW.latitude), 4326)::geography;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_donor_location ON donors;
CREATE TRIGGER trg_donor_location
    BEFORE INSERT OR UPDATE OF latitude, longitude ON donors
    FOR EACH ROW EXECUTE FUNCTION set_donor_location();
