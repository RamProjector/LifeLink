-- Remove PostgreSQL's creator-global default PUBLIC EXECUTE grant.
-- A schema-scoped default revoke cannot cancel PostgreSQL's global PUBLIC grant.
-- Keep the schema-specific anon/authenticated revokes in migration 015 as well.

BEGIN;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, anon, authenticated;

COMMIT;
