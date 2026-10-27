# LifeLink Cloud

LifeLink Cloud is the persistent and deployable package for the LifeLink emergency blood-donor discovery helper. It contains the native Kotlin/Jetpack Compose Android source, PostgreSQL/SQLAlchemy FastAPI backend, PostGIS migration, Supabase setup guidance, Render deployment files, tests, documentation, and configured development artifacts.

## Architecture

```text
Android app → FastAPI service on Render → PostgreSQL on Supabase
```

The Android app never connects directly to PostgreSQL. FastAPI owns database credentials, authorization, validation, matching, donor selection, and contact-request operations. Medical screening remains outside LifeLink.

## Database setup

Create a Supabase project, enable PostGIS, and apply all SQL migrations in `lifelink_fastapi/sql/` in filename order to a new, empty database. Configure the API with a hosted PostgreSQL URL:

```bash
export LIFELINK_DATABASE_URL='postgresql://USER:PASSWORD@HOST:5432/postgres?sslmode=require'
export LIFELINK_AUTH_REQUIRED=true
```

Run the migrations once, before starting the API; do not replay them against
an existing database. The current deployment's migration state is documented
in [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md).

The API converts hosted `sslmode=require` URLs into the asyncpg-compatible SSL option.

## Run the cloud API locally

```bash
cd lifelink_fastapi
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main_postgres:app --reload --host 0.0.0.0 --port 8000
```

## Deploy publicly with Render

The repository includes `lifelink_fastapi/Dockerfile` and `lifelink_fastapi/render.yaml`. Follow [`docs/RENDER_DEPLOYMENT.md`](docs/RENDER_DEPLOYMENT.md). Configure `LIFELINK_DATABASE_URL`, keep `LIFELINK_AUTH_REQUIRED=true`, and use the Render HTTPS URL in the Android build:

```bash
cd LifeLinkAndroid
./gradlew assembleDebug -PlifelinkApiBaseUrl=https://YOUR-RENDER-SERVICE.onrender.com/
```

The cloud-configured debug APK is produced by the **Build Android APKs** workflow and attached to the run as the `lifelink-android-apks-<sha>` artifact. Prebuilt APKs are no longer committed to the repository. A release build must be signed with a real production keystore before distribution.

## Package structure

| Path | Purpose |
|---|---|
| `LifeLinkAndroid/` | Native Kotlin/Compose Android application |
| `lifelink_fastapi/` | PostgreSQL-backed FastAPI service, schema, and tests |
| `docs/` | Cloud startup, deployment, implementation status, system documentation, and quality guidance |
| `artifacts/` | App icon (APKs are built in CI, not committed) |

## Documentation

For an overview of the entire repository, workflows, architecture, privacy boundaries, development, testing, and contribution process, start with the [Repository Guide](docs/REPOSITORY_GUIDE.md).

For setup, start with [Cloud startup](docs/START_CLOUD.md). For the current deployment and migration state, read [Implementation Status](docs/IMPLEMENTATION_STATUS.md). The concise milestone record is [Cloud changelog](docs/CHANGELOG_CLOUD.md), and the student-oriented explanation is [project history](docs/HISTORY_CLOUD.md).

The complete academic-style system integration report is [available as PDF](docs/LifeLink_System_Functional_Integration_Documentation.pdf), with editable [Markdown source](docs/LifeLink_System_Functional_Integration_Documentation.md).

The plain-language project assessment is [available as PDF](docs/LifeLink_Project_Review_Laymans_Terms.pdf), with editable [Markdown source](docs/LifeLink_Project_Review_Laymans_Terms.md).

## Status / known limitations

LifeLink Cloud is a working development package, not yet a public production
service. The authoritative, up-to-date state (live migration version, deployed
services, and open work) is tracked in
[`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md). Known
limitations before public production use:

- **Android toolchain is on AGP 9.4.1 / Gradle 9.6.0 / `compileSdk` 37**
  (Kotlin 2.2.10 built into AGP, KSP 2.3.12). The earlier AGP 8.7.3 / Gradle
  8.10.2 / `compileSdk` 35 pin has been migrated.
- **A signed release keystore is still required before distribution.** The
  release build type is now wired for real signing (keystore supplied through
  `LIFELINK_RELEASE_*` env vars or `lifelinkRelease*` Gradle properties) with
  R8 minification and resource shrinking enabled; without a configured keystore
  it still produces an unsigned artifact. See
  [`docs/ANDROID_RELEASE_SIGNING.md`](docs/ANDROID_RELEASE_SIGNING.md).
- **Rate limiting is in-process** (per-worker, reset on restart) and must move
  to a shared store before public launch.
- **Medical screening is out of scope** — profile completion enables operational
  matching only.

## Security

The PostgreSQL adapter fails closed on authentication when `LIFELINK_AUTH_REQUIRED` is omitted. Hosted authentication verifies Supabase JWTs and applies ownership checks; rate limiting, audit logging, signed release configuration, and operational/privacy review are still required before public production use. See [`SECURITY.md`](SECURITY.md).

## License

Released under the MIT License. See [`LICENSE`](LICENSE).


## Current implementation update — 28 October 2026

The consolidated donor-location work is tracked in [PR #153](https://github.com/RamProjector/LifeLink/pull/153). It replaces manual donor coordinate entry with precise device-GPS capture, records server-side presence freshness separately from availability preference, expires stale locations from matching and map results, and adds privacy-safe donor-area details. The PR also consolidates map rendering evidence, donor-tab restoration through chat, and the removal of an embedded development API token.

This is implementation work under CI review, not a claim of production certification. Physical-device GPS behavior and live Firebase Cloud Messaging delivery still require a configured device/test environment. See [the Android client audit](docs/APP_CLIENT_IMPROVEMENT_AUDIT.md), [repository guide](docs/REPOSITORY_GUIDE.md), and [Android visual review notes](docs/ANDROID_VISUAL_REVIEW.md).
