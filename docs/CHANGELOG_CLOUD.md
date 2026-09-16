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
