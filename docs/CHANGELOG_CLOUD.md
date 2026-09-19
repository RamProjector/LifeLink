# LifeLink Cloud Package Changelog

This changelog lists the major release milestones. For the teaching-style explanation of what changed, why it changed, and how the pieces connect, read [HISTORY_CLOUD.md](HISTORY_CLOUD.md).

## Project foundation

Defined LifeLink as a GPS-assisted blood-donor discovery helper with requester/coordinator and donor roles. Planned the emergency-request journey, donor workflow, Material 3 interface, and external medical-screening boundary.

## Native Android MVP

Built the Kotlin/Jetpack Compose client with ViewModels, repositories, Retrofit contracts, Material 3 components, emergency-request validation, donor mode, status polling, cancellation, Room caching, and WorkManager retry behavior.

## FastAPI and matching

Added the in-memory FastAPI prototype, explainable GPS-assisted matching, optional AI ranking, donor responses, manual broadcast, request status, and cancellation. The PostgreSQL adapter was kept compatible with the same API contract.

## PostgreSQL and PostGIS

Added asynchronous SQLAlchemy sessions, repositories, typed models, database constraints, facilities, donors, emergency requests, request matches, pending submissions, geography points, spatial indexes, and coordinate synchronization triggers.

## Audit5 — Dual-mode discovery

Added requester-selected donor cards, selected-donor contact, external-screening disclosure, `POST /v1/emergency-requests/{request_id}/contact`, matched-donor validation, ownership checks, and regression tests.

## Audit6 — App icon and package quality

Designed and integrated the LifeLink launcher icon, wired it to `android:icon` and `android:roundIcon`, verified APK embedding, and corrected stale tooling evidence.

## Audit7 — Interaction and authorization

Replaced an empty urgency-chip click handler with a non-interactive status surface. Added authenticated cross-owner contact regression coverage. Reached 14 passing backend tests and a clean empty-handler scan.

## Supabase cloud database

Documented Supabase Free as a hosted PostgreSQL option. Added PostGIS setup guidance, `extensions` schema compatibility, hosted SSL URL conversion, `.env.example`, and a regression test for provider connection strings.

## Render Free API hosting

Added `Dockerfile`, `render.yaml`, Render health-check configuration, environment-variable instructions, free-tier limitations, and public architecture guidance:

```text
Android app → Render Free FastAPI → Supabase Free PostgreSQL
```

## Cloud package split — 2026-09-15/16

Created a standalone cloud package containing Android source, Expo source, PostgreSQL FastAPI source, PostGIS migration, cloud configuration, Docker deployment files, tests, instructions, audit report, APK, and icon.

See `docs/START_CLOUD.md`, `docs/RENDER_DEPLOYMENT.md`, and `docs/HISTORY_CLOUD.md` for the detailed setup and teaching history.

## Audit8 — Build configuration and cloud handoff — 2026-09-16

Made the Android API base URL configurable with `-PlifelinkApiBaseUrl=https://...` or `LIFELINK_API_BASE_URL`, so the same source can target local, LAN, or Render-hosted APIs without source edits. Corrected the Android README’s Gradle-wrapper statement. Backend verification now passes 15 tests.

## Audit9 — Release and security defaults — 2026-09-16

Compared Android and FastAPI route inventories, confirmed cloud-package hygiene, and verified Android debug/release builds with an HTTPS API URL. The PostgreSQL adapter now fails closed on authentication when `LIFELINK_AUTH_REQUIRED` is omitted. Backend verification remains at 15 passing tests.

## Audit10 — GitHub repository hygiene and CI hardening — 2026-09-17

Removed obsolete build-round logs from the repository tree and added least-privilege `contents: read` permissions plus concurrency cancellation to the GitHub Actions workflow. Credential-name and generated-state scans remained clean.

## Role-backed cloud continuation — 2026-09-18

Added Supabase email/password authentication with local input validation, hosted email-confirmation handling, persisted requester/donor onboarding, authenticated donor identity, donor GPS capture, and a role-specific product plan. Requesters can use GPS or enter an approximate location, while donor matching continues to keep exact coordinates private. The role/profile/contact migration is `lifelink_fastapi/sql/003_roles_profiles_contacts.sql` and must be applied after the first two migrations.

## Requester map picker — 2026-09-19

Added an interactive Leaflet/OpenStreetMap picker to the Android emergency-request location step. Tapping the map or dragging the pin stores an approximate requester location through the existing GPS request model; the selected coordinates remain private and are used only for matching. The latest GitHub Actions validation and Android APK build completed successfully for commit `6140256`.

## Operational verification follow-up — 2026-09-19

Rechecked the deployed Render service after its documented cold-start concern. `/health` returned HTTP 200 with `{"status":"ok","service":"lifelink-matching-postgres"}` after a 45-second wake-up, and `/openapi.json` was available. The local sandbox does not currently expose the Android SDK or Python test dependencies; CI remains the authoritative full verification environment until those tools are restored locally.

## GPS reliability and map-centering improvement — 2026-09-19

Applied Android location guidance to the shared GPS provider and requester flow. One-shot fixes now use a bounded high-accuracy request with a 30-second cached-location limit, a 15-second timeout, coroutine cancellation, and validation of the reported accuracy. When location permission is already granted, opening the requester location step automatically obtains one current fix and centers the map without forcing a new permission prompt. The map picker, manual latitude/longitude fields, and approximate-location privacy boundary remain available as fallbacks. See [`GPS_RESEARCH_AND_IMPLEMENTATION.md`](GPS_RESEARCH_AND_IMPLEMENTATION.md) for the research and next recommended `SettingsClient` iteration.

## Device location-settings resolution — 2026-09-19

Added a `SettingsClient` check before requester GPS capture. If Android can resolve disabled location settings, LifeLink opens the system dialog and retries after the user accepts. If the user declines or the device cannot resolve the settings, the UI preserves the map and manual-coordinate fallbacks with an explanatory message. Donor-profile capture remains the next parity update.

## Privacy-safe donor map summary — 2026-09-19

Added an optional map summary to the post-submit donor results. The list remains the default and actionable view. The map centers on the requester location and shows anonymous donor counts within 5 km, between 5–10 km, and beyond 10 km. It does not receive or display donor coordinates, names, or individual pins, so GPS remains useful without exposing donor whereabouts.

## Live migration state confirmed — 2026-09-19

The project owner confirmed that `001_initial_schema.sql`, `002_gps_request_location.sql`, and `003_roles_profiles_contacts.sql` have all been applied to the live Supabase database in order. This state is recorded in [`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) for continuity across future maintenance chats; no credentials or sensitive deployment values are stored there.

## Donor location controls — 2026-09-19

Added donor-profile location parity without a database migration. Donors can now capture or update their current location, tap the map, drag an approximate pin, or enter latitude and longitude manually. The profile shows the saved accuracy and clearly explains that requesters receive distance and travel estimates rather than donor coordinates. Existing donor matching fields and the three applied SQL migrations remain unchanged.

The donor profile and map are now expanded by default so the location feature is visible when entering donor mode. The toggle is labeled `Show/Hide donor profile and map` for explicit discoverability.

## WebView map rendering fallback — 2026-09-19

Replaced the requester and donor WebView map previews with a native Compose-rendered interactive coordinate picker. This prevents a blank white map when Android WebView cannot render third-party map assets. The visible grid, location pin, coordinate readout, tap selection, and draggable pin work without WebView, external JavaScript, map tiles, or an API key; the existing privacy boundary and manual coordinate fallbacks remain unchanged.
