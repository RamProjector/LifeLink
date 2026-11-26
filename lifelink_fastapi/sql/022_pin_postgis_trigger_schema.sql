-- Re-pin both location triggers using the actual installed PostGIS schema.
-- This is a follow-up to migration 016 for databases where that migration has
-- already been applied with a fixed extensions-only search path.

BEGIN;

DO $migration$
DECLARE
    postgis_schema text;
BEGIN
    SELECT n.nspname
      INTO postgis_schema
      FROM pg_extension AS e
      JOIN pg_namespace AS n ON n.oid = e.extnamespace
     WHERE e.extname = 'postgis';

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

COMMIT;
