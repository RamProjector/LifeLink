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
| `docs/` | Cloud startup, Render guide, changelog, history, and quality audit |
| `artifacts/` | App icon (APKs are built in CI, not committed) |

## Documentation

Start with [`docs/START_CLOUD.md`](docs/START_CLOUD.md). For the confirmed live migration and implementation state, read [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md). The concise milestone record is [`docs/CHANGELOG_CLOUD.md`](docs/CHANGELOG_CLOUD.md), and the student-oriented explanation is [`docs/HISTORY_CLOUD.md`](docs/HISTORY_CLOUD.md).

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
