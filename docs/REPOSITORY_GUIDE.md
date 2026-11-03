# LifeLink Repository Guide

**Repository:** [RamProjector/LifeLink](https://github.com/RamProjector/LifeLink)

**Default branch:** `main`

**Purpose:** Emergency blood-donor discovery and coordination helper. LifeLink supports requesters and donors with location-assisted matching and controlled contact workflows. It is not a blood bank, a clinical screening service, or a replacement for emergency services.

> **Implementation and deployment status:** This guide describes the repository architecture and intended workflows. It does not certify that every feature is production-ready or that the current hosted services are healthy. Before operational decisions, check [Implementation Status](IMPLEMENTATION_STATUS.md), the current GitHub Actions runs, and the live service health endpoint.

## Start here

| If you want to… | Read |
|---|---|
| Understand current deployment/migration status | [Implementation status](IMPLEMENTATION_STATUS.md) |
| Set up the cloud-connected app and database | [Cloud startup](START_CLOUD.md) |
| Run or test the API | [FastAPI README](../lifelink_fastapi/README.md) |
| Build the Android app | [Android README](../LifeLinkAndroid/README.md) |
| Deploy the API to Render | [Render deployment](RENDER_DEPLOYMENT.md) |
| Configure Android release signing | [Android release signing](ANDROID_RELEASE_SIGNING.md) |
| Understand admin access and console setup | [Admin console](ADMIN_CONSOLE.md) |
| Review security practices/report a vulnerability | [SECURITY.md](../SECURITY.md) |
| Read the full system integration report | [System integration documentation](LifeLink_System_Functional_Integration_Documentation.md) |
| Review the plain-language project assessment | [Project review](LifeLink_Project_Review_Laymans_Terms.md) |

## System architecture

```text
Native Android app (Kotlin / Jetpack Compose)
                 |
                 | HTTPS + bearer access token
                 v
FastAPI API (Python, hosted on Render)
                 |
                 | async SQLAlchemy / asyncpg
                 v
PostgreSQL + PostGIS (hosted on Supabase)
```

The Android app must call the FastAPI API for protected application operations; it must not connect directly to the database. Supabase provides authentication and PostgreSQL hosting, while Render hosts the API process. The web admin console, where configured, also calls the API and must not be treated as a trusted authorization boundary.

### Main repository areas

- `LifeLinkAndroid/` — native Android client, UI, authentication/session handling, location and request flows, local persistence, and Android build configuration.
- `lifelink_fastapi/` — FastAPI application, PostgreSQL-backed routes and repositories, SQLAlchemy models, ordered SQL migrations, and backend tests.
- `docs/` — setup/deployment instructions, implementation status, security and release notes, system documentation, and improvement plans.
- `.github/workflows/` — automated build, validation, security, and maintenance workflows.
- `artifacts/` — repository assets. APKs should be obtained from workflow artifacts rather than committed binaries.

For exact package-level commands, use each subproject's README; do not assume commands from the in-memory demo API and the PostgreSQL-backed API are interchangeable.

## Core workflows

### Requester flow

1. Sign in or create an account through the configured Supabase Auth project.
2. Complete the requester profile and provide an emergency request with a blood type, needed quantity, deadline/urgency, and location as required by the current UI.
3. The API validates the request and computes rule-based blood compatibility and donor-priority results.
4. The requester reviews matching results and chooses which donors to contact; creating a request is not intended to automatically broadcast it to every donor.
5. Requester and donor responses update the contact lifecycle. Messaging, location sharing, and contact-detail disclosure are scoped to the relevant request and depend on the configured backend and permissions.

### Donor flow

1. Sign in and complete donor operational-profile information.
2. Set availability and update location while using the app, as appropriate.
3. Review eligible requests and respond to contact requests.
4. Control whether an approximate donor area is shown and whether matched requesters can receive a temporary exact-location share.

Matching and profile completion are operational coordination features, **not medical screening or proof of donation eligibility**. Blood-bank or qualified clinical staff must determine clinical eligibility and compatibility before any transfusion.

## Privacy and authorization boundaries

- Donor exact coordinates must not be exposed in ordinary requester match results.
- The donor map is designed around approximate areas/distance bands, not publicly identifying individual donor pins.
- Exact location sharing is request-scoped, requires the donor's permission, and should be revocable/expire with the relevant request.
- Phone/email disclosure should be explicit rather than automatic.
- API authorization must verify the signed identity token and resource ownership. Hiding a button in Android or the web UI is not authorization.
- Administrator access is restricted to server-configured trusted user UUIDs (see [Admin Console](ADMIN_CONSOLE.md)); public signup must never grant administrator privileges.
- Logs and audit metadata must not include passwords, access/refresh tokens, exact coordinates, or unnecessary request/medical data.

These are security expectations and documented design boundaries; verify current implementation and deployed configuration before relying on them.

## Local development

### Backend

For a fast local demo, follow the demo-mode instructions in [the backend README](../lifelink_fastapi/README.md). The demo uses in-memory storage and its data is not durable. For cloud-connected PostgreSQL development, use [Cloud startup](START_CLOUD.md).

Typical PostgreSQL-backed startup from the repository root:

```bash
cd lifelink_fastapi
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main_postgres:app --reload --host 0.0.0.0 --port 8000
```

With the server running, FastAPI normally exposes interactive API documentation at `http://127.0.0.1:8000/docs` and an OpenAPI schema at `/openapi.json`. The available endpoints and schemas generated by the running app are the authoritative API reference; this guide intentionally does not duplicate every route.

### Android

Use the current [Android README](../LifeLinkAndroid/README.md) for prerequisites and build commands. A typical local verification sequence is:

```bash
cd LifeLinkAndroid
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The exact task availability depends on the checked-out Gradle configuration. Passing compilation/unit tests/lint does not by itself prove that location, maps, authentication, chat, notifications, or the deployed API work end-to-end on a physical device.

## Database and migrations

SQL migrations are under `lifelink_fastapi/sql/` and must be reviewed/applied in filename order to a new database. Do not blindly rerun initial migrations against a database that already contains application data. Do not assume SQLAlchemy's `create_all` reproduces PostGIS types, triggers, or row-level security policies defined by SQL migrations.

Before any schema change:
1. Read [Implementation Status](IMPLEMENTATION_STATUS.md) and inspect the actual deployed migration state.
2. Back up or establish a recovery plan for any non-disposable database.
3. Review the migration for data loss, locking, access-policy changes, and rollback limitations.
4. Test against a disposable database first.
5. Apply to production only with explicit authorization and a verified plan.

Never put real database URLs/passwords, Supabase service-role keys, JWT secrets, Firebase service-account JSON, or Android signing credentials in source control.

## Donor GPS and presence freshness (PR #153)

Donor coordinates must come from a fresh, precise device-GPS capture in both donor setup and the legacy donor workspace. Manual latitude/longitude entry and map-pin editing are intentionally unavailable for donor location. A client-side GPS gate improves UX and reduces accidental fabrication, but it is not cryptographic GPS attestation; a modified client can still attempt to submit fabricated coordinates.

The backend keeps the donor's explicit availability preference separate from the operational presence snapshot:
- `donors.last_presence_at` records the last server-accepted presence/location update.
- Availability toggles do not make old coordinates fresh.
- Matching, public map results, and exact-location sharing reject stale snapshots.
- A periodic sweep expires stale operational presence without overriding an explicit paused/offline preference.
- The Android app refreshes GPS only while foregrounded, with fine-location permission, for verified donors who remain explicitly available.

The public map uses coarsened anonymous coordinates and a details view without identity, contact details, or exact location. Exact-location sharing must remain consent-based, request-scoped, time-limited, and server-authorized.

The additive migration is `lifelink_fastapi/sql/012_donor_presence_freshness.sql`. The application startup also performs an idempotent add/backfill/index upgrade for this column so an existing database is not left without the field. Keep schema changes additive; do not drop or rewrite production data.

## Configuration overview

Configuration is environment-specific. The full source of truth is the deployment guide and each subproject's build documentation.

| Configuration | Where it belongs | Notes |
|---|---|---|
| `LIFELINK_DATABASE_URL` | API host/local environment | Private PostgreSQL connection string; never commit it |
| `LIFELINK_AUTH_REQUIRED` | API host | Keep enabled for hosted environments |
| `SUPABASE_URL` | API/client build configuration as documented | Public project URL; verify issuer/audience configuration |
| `SUPABASE_PUBLISHABLE_KEY` | Android build/CI configuration | Client key only; never substitute a service-role key |
| `LIFELINK_ADMIN_USER_IDS` | API host only | Trusted UUID allowlist; never expose as client-side authorization |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | API host secret configuration, if push is enabled | Private service-account credential |
| `LIFELINK_RELEASE_*` / `lifelinkRelease*` | Protected release environment | Signing secrets must not be committed |

Do not copy example values into production without replacing placeholders and checking the exact variable names expected by the current code and deployment manifest.

## Tests and CI

Use the repository's current GitHub Actions workflows as the actual record of automated verification. Common validation areas include Android build/lint/unit tests, backend tests, dependency/security scanning, and code analysis. Workflow names and required checks can change; inspect the run for the commit being reviewed rather than relying on a remembered status.

For changes that affect authentication, permissions, donor visibility, location sharing, contact lifecycle, admin access, migrations, or audit events, include tests for both allowed and denied cases. Prefer synthetic test users and locations. Never run destructive tests against the production database.

A green build is necessary but not sufficient: emulator availability, real-device GPS behavior, map rendering, push delivery, external service configuration, and live database migration state may require separate verification.

## Contribution workflow

1. Create a focused branch from the latest `main`.
2. Inspect the existing code, related tests, migrations, and relevant docs before editing.
3. Keep the change narrow; avoid unrelated refactors and stack changes.
4. Add/update tests and documentation alongside behavior changes.
5. Run relevant local checks, then open a pull request with the purpose, risk, migration/configuration impact, and verification evidence.
6. Review all required CI checks and code-review feedback. Do not bypass a failed required check simply to merge.
7. Merge only when checks and review requirements pass; document any remaining manual/live verification honestly.

## Known production-readiness boundaries

LifeLink should be treated as a development/MVP coordination package until the [Implementation Status](IMPLEMENTATION_STATUS.md) and live checks confirm otherwise. Items requiring careful verification include:

- current live database migration state and deployment configuration;
- authorization/ownership and admin-denial tests across API endpoints;
- rate limiting across multiple workers (in-process limits are not shared);
- privacy, retention, audit-log coverage, and applicable legal review;
- reliable push delivery and operational monitoring;
- map/location accuracy, permission UX, accessibility, and real-device performance;
- signed release artifact verification and secure key custody;
- operational continuity and emergency-service disclaimers.

Do not claim the app is production-ready solely because it builds or a health endpoint responds successfully.

## External references

- [GitHub repository documentation best practices](https://docs.github.com/en/repositories/creating-and-managing-repositories/best-practices-for-repositories)
- [GitHub README guidance](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-readmes)
- [FastAPI interactive API documentation](https://fastapi.tiangolo.com/tutorial/first-steps/)
- [FastAPI deployment concepts](https://fastapi.tiangolo.com/deployment/concepts/)
