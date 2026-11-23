-- Pin PostGIS trigger-function resolution and index the remaining foreign keys.
--
-- Resolve PostGIS from the installed extension catalog entry rather than
-- assuming it lives in either public or extensions. Only those two trusted
-- installation schemas are supported by this application.
-- The three indexes cover foreign keys that were not covered by a leading
-- index column; existing composite indexes with the FK in a later position
-- do not cover the FK lookup efficiently.

BEGIN;

DO $migration$
DECLARE
    postgis_schema text;
BEGIN
    SELECT namespace.nspname
      INTO postgis_schema
      FROM pg_extension AS extension
      JOIN pg_namespace AS namespace ON namespace.oid = extension.extnamespace
     WHERE extension.extname = 'postgis';

    IF postgis_schema IS NULL OR postgis_schema NOT IN ('public', 'extensions') THEN
        RAISE EXCEPTION 'PostGIS must be installed in public or extensions; found %', postgis_schema;
    END IF;

    EXECUTE format(
        'ALTER FUNCTION public.set_facility_location() SET search_path = pg_catalog, %I',
        postgis_schema
    );
    EXECUTE format(
        'ALTER FUNCTION public.set_donor_location() SET search_path = pg_catalog, %I',
        postgis_schema
    );
END
$migration$;

CREATE INDEX IF NOT EXISTS ix_donor_location_shares_donor_id
    ON public.donor_location_shares (donor_id);

CREATE INDEX IF NOT EXISTS ix_emergency_requests_facility_id
    ON public.emergency_requests (facility_id);

CREATE INDEX IF NOT EXISTS ix_saved_messages_message_id
    ON public.saved_messages (message_id);

COMMIT;
