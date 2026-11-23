-- Lock public-schema data access behind the LifeLink FastAPI service.
--
-- Android and the admin console authenticate with Supabase Auth, then call the
-- FastAPI API. Neither client is intended to query public tables through
-- PostgREST. The API uses the private LIFELINK_DATABASE_URL connection.
--
-- RLS remains enabled as defense in depth, but policies are not a substitute
-- for table grants. The live project previously granted anon/authenticated
-- direct privileges on every public table, including backend-only tables.
-- Keep service_role and database-owner privileges unchanged.

BEGIN;

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public
    FROM PUBLIC, anon, authenticated;

REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public
    FROM PUBLIC, anon, authenticated;

-- The only public-schema functions are trigger/event-trigger helpers. They
-- are not client RPCs and must not be executable through the Data API.
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public
    FROM PUBLIC, anon, authenticated;

-- The connected project SQL role can alter its own defaults. Supabase's
-- separate supabase_admin defaults are managed by the platform and are not
-- alterable from the project's postgres role; its RLS event trigger remains
-- the default-deny guard for tables created under that owner.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE ALL ON TABLES FROM PUBLIC, anon, authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE ALL ON SEQUENCES FROM PUBLIC, anon, authenticated;
-- PostgreSQL grants EXECUTE to PUBLIC for new functions unless the creator's
-- global default is revoked. A schema-scoped revoke cannot cancel that grant.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, anon, authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, anon, authenticated;

COMMIT;
