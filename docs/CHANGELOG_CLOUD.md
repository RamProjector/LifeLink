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

## Supabase access-token refresh — 2026-09-20

Investigated the requester screenshot showing `401 Unauthorized` during emergency-request submission. The Android client was attaching the persisted Supabase access token correctly, but it had no refresh-token path after that access token expired. Added a Supabase refresh-token call and one transparent API retry on 401 for requester, donor, and profile API clients. The implementation is compile-verified locally; no SQL migration or Render route change is required.

## Coordinate picker clarification — 2026-09-20

The native fallback is explicitly an **offline coordinate picker**, not a street map. Corrected its gesture handling to use the project-compatible drag detector so the pin can be moved reliably. A real street map remains a separate provider-integration decision; Google Maps requires an API key and billing configuration, while MapLibre requires a compliant vector-tile/style provider.

## Real native maps — 2026-09-20

Replaced the offline coordinate-picker placeholder and all remaining WebView map surfaces with MapLibre Native Android (`org.maplibre.gl:android-sdk:11.8.0`). The requester and donor location screens now render real street-level vector maps using OpenFreeMap's documented Liberty style (`https://tiles.openfreemap.org/styles/liberty`). Tapping or long-pressing the map moves the private selected-location marker; normal MapLibre pan and zoom gestures remain available. The requester donor-distance summary now also uses a native MapLibre map and does not expose donor pins or coordinates.

## GPS centering and location-picker research — 2026-09-20

Investigated the report that the preview stayed centered on Manila. The cause was an implicit Manila fallback combined with an asynchronous MapLibre style callback that could reapply the initial camera after a real GPS result arrived. Removed the implicit requester fallback, made the style callback read the latest coordinates, updated markers in place, and added a visible confirmation showing the reported GPS accuracy after centering.

The sourced improvement plan is recorded in `docs/LOCATION_PICKING_RESEARCH_AND_PLAN.md`. Its next priorities are typed location quality and source states, explicit approximate-versus-precise permission handling, in-flight cancellation and retry states, map loading/error/recenter controls, and a review-screen summary of source, freshness, uncertainty, and privacy.

## Submission 500 and Africa preview correction — 2026-09-20

Investigated the report that login succeeded but request submission returned HTTP 500. The PostgreSQL adapter used SQLAlchemy native enums without a `values_callable`, so SQLAlchemy could persist Python member names such as `O_POS` and `AWAITING_RESPONSES` while the Supabase migrations define values such as `O+` and `awaiting_responses`. Updated every mapped enum to persist the migration values. The backend test suite passes with 15 tests.

The requester map previously rendered a nullable location as `(0, 0)`, which is in the Gulf of Guinea and appeared as Africa. The requester screen now shows a neutral location prompt until GPS or a user-selected/manual coordinate exists; it no longer renders a geographic map at `(0, 0)`.

## Location and donor-results UX — 2026-09-20

The location picker now temporarily disallows the surrounding `LazyColumn` from intercepting MapLibre touch gestures, so map panning and zooming work without the page scroll competing for the same drag. Camera updates are applied only when the selected coordinates change, preserving a user’s map position during Compose recomposition. A full-screen location-picker sheet is available for easier map inspection and marker placement.

Submission feedback remains card-based rather than relying on a short-lived toast: errors include a retry action, and successful submission/sync states use a prominent status card. Donor results remain list-first with an optional privacy-safe map summary. The list is the actionable surface because donors can be selected and contacted there; the map intentionally does not expose individual donor pins or coordinates. There is no separate donor map page yet—the current combined results surface appears below the submission state in the request flow.

## Proposal contact lifecycle — 2026-09-20

Completed the proposal-aligned requester-to-donor contact loop. The PostgreSQL adapter now maps the existing `donor_contact_requests` table, creates pending contact records when a requester selects donors, and updates those records when a donor accepts or declines. Requesters can retrieve their own contact statuses, and accepted donor email is disclosed only after acceptance. Donor inbox responses now read and update the persisted contact state.

The Android client now enters a dedicated donor-results state after successful submission. It shows the actionable donor list, optional privacy-safe map summary, contact selection, and pending/accepted/declined status cards. Android compilation passes and the backend suite passes with 15 tests. The post-update roadmap is documented in `docs/POST_UPDATE_IMPROVEMENT_SCOPE.md`.

## Post-MVP reliability and completeness direction — 2026-09-20

The core MVP is functional: requesters can submit emergency requests, review eligible donors, select donors, and observe donor contact responses; donors can accept or decline contact requests; and accepted contact details remain protected by server-side authorization. The next additions are intentionally focused on reliability and completeness rather than unnecessary coordination complexity.

The recommended next implementation slice is **request history**, a **complete accepted-donor contact screen**, and **map loading/retry/recenter controls**. Request history should cover active, completed, cancelled, and expired requests together with previous donor responses. Accepted-donor handling should add clear accepted cards, consent-based contact disclosure, accepted timestamps, contact-shared and meeting-arranged states, and fulfilled or cancelled actions. Map improvements should add loading and error states, tile retry, direct recentering, location-source and accuracy feedback, and confirmation when a pin moves.

Additional follow-up areas are donor profile completeness, password reset and confirmation resend, sign-out and account deletion where required, physical-device and two-account verification, rate limiting, abuse prevention, report/block functionality, contact-sharing audit logs, and a signed production APK. Temporary consent-based location sharing, expiring links, institutional verification, admin tooling, multi-role accounts, and live donor tracking remain lower-priority features. Exact live donor tracking remains disabled by design.
