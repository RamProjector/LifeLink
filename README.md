# LifeLink Cloud

LifeLink Cloud is the persistent and deployable package for the LifeLink emergency blood-donor discovery helper. It contains the native Kotlin/Jetpack Compose Android source, PostgreSQL/SQLAlchemy FastAPI backend, PostGIS migration, Supabase setup guidance, Render deployment files, tests, documentation, and configured development artifacts.

## Architecture

```text
Android app → FastAPI service on Render → PostgreSQL on Supabase
```

The Android app never connects directly to PostgreSQL. FastAPI owns database credentials, authorization, validation, matching, donor selection, and contact-request operations. Medical screening remains outside LifeLink.

## Database setup

Create a Supabase project, enable PostGIS, and run `lifelink_fastapi/sql/001_initial_schema.sql`. Configure the API with a hosted PostgreSQL URL:

```bash
export LIFELINK_DATABASE_URL='postgresql://USER:PASSWORD@HOST:5432/postgres?sslmode=require'
export LIFELINK_AUTH_REQUIRED=true
```

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

The cloud-configured development APK is in `artifacts/app-debug-cloud-configured.apk`. The unsigned release build is provided for release configuration inspection and must be signed with a real production keystore before distribution.

## Package structure

| Path | Purpose |
|---|---|
| `LifeLinkAndroid/` | Native Kotlin/Compose Android application |
| `lifelink_fastapi/` | PostgreSQL-backed FastAPI service, schema, and tests |
| `lifelink-mobile/` | Expo/React Native mobile source |
| `docs/` | Cloud startup, Render guide, changelog, history, and quality audit |
| `artifacts/` | APKs and app icon |

## Documentation

Start with [`docs/START_CLOUD.md`](docs/START_CLOUD.md). For the confirmed live migration and implementation state, read [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md). The concise milestone record is [`docs/CHANGELOG_CLOUD.md`](docs/CHANGELOG_CLOUD.md), and the student-oriented explanation is [`docs/HISTORY_CLOUD.md`](docs/HISTORY_CLOUD.md).

## Security

The PostgreSQL adapter fails closed on authentication when `LIFELINK_AUTH_REQUIRED` is omitted. Hosted authentication verifies Supabase JWTs and applies ownership checks; rate limiting, audit logging, signed release configuration, and operational/privacy review are still required before public production use. See [`SECURITY.md`](SECURITY.md).

## License

Released under the MIT License. See [`LICENSE`](LICENSE).
