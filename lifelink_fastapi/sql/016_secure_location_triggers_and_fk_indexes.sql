-- Pin PostGIS trigger-function resolution and index the remaining foreign keys.
--
-- The PostGIS extension is installed in the dedicated extensions schema. These
-- trigger functions do not need public objects in their search path.
-- The three indexes cover foreign keys that were not covered by a leading
-- index column; existing composite indexes with the FK in a later position
-- do not cover the FK lookup efficiently.

BEGIN;

ALTER FUNCTION public.set_facility_location()
    SET search_path = pg_catalog, extensions;

ALTER FUNCTION public.set_donor_location()
    SET search_path = pg_catalog, extensions;

CREATE INDEX IF NOT EXISTS ix_donor_location_shares_donor_id
    ON public.donor_location_shares (donor_id);

CREATE INDEX IF NOT EXISTS ix_emergency_requests_facility_id
    ON public.emergency_requests (facility_id);

CREATE INDEX IF NOT EXISTS ix_saved_messages_message_id
    ON public.saved_messages (message_id);

COMMIT;
