# LifeLink Cloud Package — Student Project History

This document explains how LifeLink evolved from a local prototype into a cloud-ready system. It is written as a teaching guide and covers the major milestones rather than every individual source edit.

## 1. Product boundary

LifeLink helps coordinators discover and contact potential blood donors. It does not perform medical screening or replace hospitals, blood banks, or doctors.

That boundary affects the cloud design: the system stores operational request and donor data, while clinical eligibility remains an external responsibility.

## 2. Android client

The mobile client uses Kotlin, Jetpack Compose, ViewModels, repositories, Retrofit, Room, and WorkManager.

The cloud architecture separates responsibilities:

```text
Android client → FastAPI application → PostgreSQL database
```

The Android app should never contain a PostgreSQL password or connect directly to the database.

## 3. FastAPI prototype

The original backend was an in-memory FastAPI service at `app.main:app`. It was easy to run and test but lost data when the process stopped.

It implemented validation, emergency requests, matching, donor responses, status tracking, manual broadcast, cancellation, and selected-donor contact.

## 4. Matching and AI option

The matching layer considers compatibility, availability, distance, estimated travel time, freshness, verification, response likelihood, and urgency. Its score is an operational priority, not medical advice.

An AI-ranking toggle was added so the system can use optional AI assistance while retaining deterministic fallback behavior. This supports explainability, reliability, cost control, and testing.

## 5. PostgreSQL persistence

The cloud implementation added asynchronous SQLAlchemy, database sessions, repositories, and `app.main_postgres:app`.

The schema contains:

- `facilities`
- `donors`
- `emergency_requests`
- `request_matches`
- `pending_submissions`

Constraints protect coordinate ranges, valid units, consent, statuses, and unique idempotency keys.

The teaching lesson is that a relational database provides durable records, relationships, indexes, and constraints that in-memory dictionaries cannot provide.

## 6. PostGIS location support

LifeLink stores geographic coordinates and creates geography points with spatial indexes. PostGIS is more suitable for scalable geographic operations than doing every distance calculation manually in application code.

Database triggers keep geography points synchronized with latitude and longitude. A GIST index supports spatial queries.

## 7. Selected donor contact

The requester can select specific donors after matching:

```text
POST /v1/emergency-requests/{request_id}/contact
```

The backend checks that selected donors are in the request’s eligible match list and, when authentication is enabled, that the requester owns the request.

This server-side validation is essential in a shared cloud database because any client could otherwise send a crafted request.

## 8. Authentication boundary

Bearer-token seams and ownership checks were introduced for development. A real public service should replace the seam with verified Firebase, Supabase Auth, or another JWT provider.

Production verification should check signature, issuer, audience, expiry, and resource ownership. The FastAPI server—not Android—should hold the database credentials.

## 9. Audit milestones

### Audit5 — Dual-mode discovery

Confirmed GPS-assisted discovery, optional AI ranking, requester-selected donors, and contact before external screening. Added demo and PostgreSQL endpoint parity and regression tests.

### Audit6 — Icon and package quality

Designed and integrated the LifeLink launcher icon using a location pin, heart, and medical cross. Verified the resource in the APK and corrected outdated tooling evidence.

### Audit7 — Authorization consistency

Replaced a misleading empty urgency-chip handler with a non-interactive status surface. Added a regression test preventing one coordinator from contacting donors for another coordinator’s request. Backend verification reached 14 passing tests.

## 10. Supabase hosted PostgreSQL

Supabase was selected as a convenient hosted PostgreSQL option for an MVP. The setup process is:

1. Create a Supabase project.
2. Enable PostGIS.
3. Copy the PostgreSQL connection string.
4. Run `sql/001_initial_schema.sql`.
5. Set `LIFELINK_DATABASE_URL`.
6. Start `app.main_postgres:app`.

The migration includes both `public` and `extensions` in the search path because hosted PostGIS installations may place the extension outside `public`.

Supabase hosts the database, not FastAPI. During development, FastAPI can run locally while Supabase provides persistent storage.

## 11. Hosted SSL compatibility

Cloud providers commonly provide URLs containing:

```text
?sslmode=require
```

The `asyncpg` driver expects an equivalent SSL option in its own format. The database URL helper converts the provider URL before SQLAlchemy creates the engine. A regression test protects this behavior.

The `.env.example` file documents configuration without containing a real secret.

## 12. Render Free API hosting

To avoid keeping a personal computer running, the project added a public deployment path using Render Free:

```text
Android app → Render Free FastAPI → Supabase Free PostgreSQL
```

The package includes a Dockerfile, `render.yaml`, health-check route, environment-variable instructions, and Android URL guidance.

The Docker command starts `app.main_postgres:app` using Render’s `PORT` value.

## 13. Render deployment checklist

The cloud repository contains a `lifelink_fastapi` directory. When creating the Render service, set the Render root directory to that folder so the Dockerfile and requirements file are found.

Configure:

```text
LIFELINK_DATABASE_URL
LIFELINK_AUTH_REQUIRED=true
DB_POOL_SIZE=3
DB_MAX_OVERFLOW=5
```

After deployment, test:

```bash
curl https://YOUR-RENDER-SERVICE.onrender.com/health
```

Then configure Android with the Render HTTPS URL, not the Supabase URL.

## 14. Free-tier limitations

A free deployment is useful for an MVP but is not guaranteed production infrastructure. Render Free services can sleep after inactivity, restart, and operate within monthly limits. Supabase Free projects can pause after inactivity and have storage and bandwidth limits.

Render’s filesystem is ephemeral, so donor and request data must remain in Supabase. The API server’s local filesystem must not be treated as the database.

## 15. Cloud package

The cloud package contains Android source, Expo source, PostgreSQL FastAPI source, PostGIS migration, `.env.example`, Docker deployment files, tests, instructions, changelog, audit report, APK, and icon.

## 16. Remaining production work

A real deployment should still add verified authentication, privacy and data-retention policies, rate limiting, monitoring, notification delivery, migration tooling, backups, incident response, and formal local blood-bank policy review.

## Learning summary

The cloud architecture developed in this order:

```text
Mobile idea
→ Android client
→ FastAPI prototype
→ matching and donor workflow
→ PostgreSQL persistence
→ PostGIS
→ authorization
→ Supabase database
→ Render public API
```

The central lesson is:

> Supabase stores persistent data, FastAPI protects and operates on it, and Android provides the user experience.

## Audit8 — Build configuration and cloud handoff

The Android API URL was made configurable at build time using `-PlifelinkApiBaseUrl=...` or `LIFELINK_API_BASE_URL`. This allows one source tree to target the local demo, a LAN API, or Render HTTPS without source edits. The Android README was also corrected so its build instructions match the included Gradle wrapper.

## Audit9 — Release and security defaults

The cloud audit compared every Android API declaration with the FastAPI route inventory. It also changed the PostgreSQL adapter to fail closed when `LIFELINK_AUTH_REQUIRED` is omitted, reducing the risk of accidentally exposing a mutable cloud API. A release Android build using an HTTPS API endpoint was verified successfully.
