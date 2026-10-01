# LifeLink Implementation Status

**Status date:** 30 September 2026
**Repository:** `RamProjector/LifeLink`  
**Source of live-state confirmation:** repository verification plus user confirmation in the project chat.

## Confirmed deployment state

The repository now contains eight Supabase SQL migrations. Migrations 001–007 have been applied to the live Supabase database, in order:

1. `lifelink_fastapi/sql/001_initial_schema.sql`
2. `lifelink_fastapi/sql/002_gps_request_location.sql`
3. `lifelink_fastapi/sql/003_roles_profiles_contacts.sql`
4. `lifelink_fastapi/sql/004_contact_lifecycle.sql`
5. `lifelink_fastapi/sql/005_audit_events.sql`
6. `lifelink_fastapi/sql/006_donor_operational_profile.sql`
7. `lifelink_fastapi/sql/007_donor_map_chat_contact_sharing.sql` (applied 2026-09-29)
8. `lifelink_fastapi/sql/008_conversation_blocks.sql` (added with the conversation block/report feature; confirm its live-application state before relying on it)

This means the live database is expected to support the initial schema, GPS-based requester locations, authenticated requester/donor roles, profiles, controlled contact-request records, expanded contact lifecycle states, append-only audit events, the donor operational profile, donor map/chat/contact sharing, and conversation blocks. Future chats should not ask to reapply these migrations unless the live database is recreated or migration state is independently found to be inconsistent.

The current Render API endpoint is documented as `https://lifelink-api-uzje.onrender.com/`. A live check previously returned HTTP 200 from `/health` and successfully served `/openapi.json`. Render free-tier cold starts can make the first request slow.

## Live donor database verification — 2026-09-24

The live Supabase database was inspected through the protected GitHub Actions workflow [`diagnose-donor-database.yml`](../.github/workflows/diagnose-donor-database.yml). The inspection was read-only and emitted only aggregate or schema results; it did not expose donor identifiers, names, coordinates, emails, tokens, or database credentials.

The inspection confirmed that the `donors`, `lifelink_profiles`, `request_matches`, and `donor_contact_requests` tables exist. All donor operational columns are present, including `user_id`, `last_location_precision_meters`, `donor_note`, `preferred_contact_method`, `pause_reason`, and `profile_visible`. The live blood-type enum uses the expected labels such as `A+`, `O-`, and `UNKNOWN`; therefore, the current donor-save failure is not caused by a missing migration or blood-type enum mismatch.

The live database contains two profiles and one donor-capable profile with a matching donor row. The donor data has no invalid coordinates, blank display name, invalid service radius, null blood type, or duplicate `user_id`. The one profile without a donor row is not donor-capable and is unrelated to donor setup. Donor location triggers are present. **Do not reapply SQL migrations as a speculative fix.**

Commit `e021b92` adds rollback handling and operation-specific logging to the PostgreSQL donor profile save route. After Render redeploys that commit, a failure will return `donor_profile_save_database_failure` or a sanitized `donor_profile_save_application_failure:<exception type>` instead of an opaque generic 500. The next troubleshooting step is to retry donor setup after deployment and record that operation code.

## Current application capabilities

The Android client currently includes Supabase email/password authentication, email-confirmation handling, persisted sessions with refresh-token renewal and one-retry handling for expired access tokens, requester/donor onboarding, authenticated donor identity, GPS capture, native MapLibre street maps using OpenFreeMap's Liberty style, tap/long-press location selection, manual latitude/longitude fallbacks, automatic requester map centering when permission is available, device location-settings resolution, bounded and cancellable one-shot location requests, emergency-request submission, GPS-assisted matching, a dedicated post-submit donor-results state, donor cards, an optional privacy-safe native donor distance-band map summary, donor location accuracy display and update controls, donor selection and persisted contact requests, donor accept/decline responses, requester contact-status refresh, accepted-contact email disclosure, offline submission retry, active-request status polling, manual broadcast fallback, and request cancellation. The implicit Manila requester fallback has been removed, and MapLibre now applies the latest GPS target after asynchronous style loading.

The FastAPI service includes PostgreSQL/PostGIS support, Supabase JWT verification, ownership checks, blood compatibility validation, explainable distance matching, donor availability, request matching, contact-request operations, and Render deployment configuration. The PostgreSQL SQLAlchemy models now persist the enum values defined by the Supabase migrations rather than Python enum member names; this addresses a submission-time database failure mode affecting values such as blood type and request status.

## Deliberate privacy boundary

The API may use exact coordinates internally for matching, but donor coordinates are not returned in requester donor-match responses. The requester sees donor distance and estimated travel time. The optional donor map shows only anonymous distance bands and counts; it does not show individual donor pins or donor names.

## Confirmed privacy behavior — "Active matched requesters only" (2026-09-29)

The following behavior is now implemented in the FastAPI service and the Android client, and is covered by end-to-end tests in `lifelink_fastapi/tests/test_privacy_flows.py`:

1. **Donor map visibility** — the map (`GET /v1/donor-map`) shows available donors as approximate areas with a freshness timestamp. Coordinates are coarsened to a ~1 km grid and no donor identity is returned. It never shows individual exact pins.
2. **Donor control** — donors opt in explicitly via `PUT /v1/donors/{donor_id}/map-visibility` ("Show me on the donor map"), can pause/hide at any time, and see a location-freshness timestamp. Hiding also revokes any live exact-location share.
3. **Location precision** — the map shows an approximate area/radius by default; exact coordinates are disclosed only after a match and only while the donor allows it.
4. **Matched-requester-only exact location** — `POST /v1/emergency-requests/{request_id}/donors/{donor_id}/location-share` activates sharing for a matched donor that enabled it; `GET .../location` returns exact coordinates only to the requester of that request while a live, unexpired share exists. The donor can revoke at any time (`DELETE .../location-share`), and shares expire when the request is cancelled, fulfilled, or expires.
5. **In-app chat** — `POST /v1/emergency-requests/{request_id}/donors/{donor_id}/conversation` opens a conversation scoped to the requester and donor of one request; messages are stored server-side and readable only by those two users.
6. **Contact details** — phone and email are hidden by default; either participant can explicitly share one field at a time (`POST /v1/conversations/{id}/contact-shares`), and every share writes an append-only audit record.
7. **Tracking policy** — no always-on/background GPS. Donors refresh location while the app is open or when they press an update button.

Migration `lifelink_fastapi/sql/007_donor_map_chat_contact_sharing.sql` adds the supporting tables (`donor_location_shares`, `conversations`, `messages`, `contact_shares`) and the donor map-visibility columns. It was applied to the live Supabase database on 2026-09-29.

## Remaining work

The following items are not confirmed as production-complete: real-device and live-deployment verification of the contact lifecycle, contact cancellation and expiry enforcement, typed location-source contract, precise-versus-approximate permission UX, in-flight location cancellation and classified retry states, in-app conversation or controlled phone handoff, push notifications and deep links, abuse reporting, signed Android release configuration, crash reporting, and real-device accessibility/performance validation. Rate-limiting and audit-event infrastructure are implemented and their migrations are applied, but endpoint-level and live-production verification remain required. A previous 401 screenshot was traced to the missing client-side access-token refresh path; the refresh-and-retry fix is now compile-verified. The requester map no longer renders `(0, 0)` when no location has been captured. The prioritized follow-up scope is recorded in `docs/POST_UPDATE_IMPROVEMENT_SCOPE.md`.

The backend now applies a configurable donor location/availability freshness cutoff through `LIFELINK_DONOR_LOCATION_MAX_AGE_MINUTES`, defaulting to 24 hours. Donors older than the cutoff are excluded from new matches; Android source labeling and explicit freshness UX remain follow-up work.

These are implementation or operational follow-ups. They do not imply that the current SQL migrations are missing or that the live donor schema is corrupt.

## Security note

This document intentionally contains no Supabase URLs beyond the public API endpoint, no publishable or service-role keys, no database connection strings, no JWT secrets, and no personal or medical information. It is a project-state record for continuity across future maintenance chats.
