# LifeLink Cloud Package

This package uses hosted PostgreSQL, recommended with Supabase Free. The database can remain free within the provider's current quotas; the FastAPI server still needs to run locally or on a hosting provider.

## Supabase setup

1. Create a Supabase project.
2. Enable `postgis` under Database → Extensions.
3. Copy the PostgreSQL connection string from Connect.
4. Run the schema:

```bash
cd lifelink_fastapi
psql "postgresql://USER:PASSWORD@HOST:5432/postgres?sslmode=require" \
  -f sql/001_initial_schema.sql
```

5. Apply the GPS-first request-location migration:

```bash
psql "postgresql://USER:PASSWORD@HOST:5432/postgres?sslmode=require" \
  -f sql/002_gps_request_location.sql
```

Facility discovery is intentionally deferred. Requests now use a private approximate requester location for donor matching; facility metadata remains optional for future use.

## Start the cloud-connected API

```bash
cd lifelink_fastapi
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export LIFELINK_DATABASE_URL='postgresql://USER:PASSWORD@HOST:5432/postgres?sslmode=require'
export LIFELINK_AUTH_REQUIRED='true'
uvicorn app.main_postgres:app --reload --host 0.0.0.0 --port 8000
```

The API converts `sslmode=require` to the asyncpg-compatible SSL parameter. Never commit the real connection string or password.

## Important distinction

Supabase hosts the database. It does not automatically host this FastAPI application. The Android app must connect to the public URL of the FastAPI server.

## Included

- Native Android source
- FastAPI PostgreSQL backend
- Supabase/PostGIS migration
- `.env.example`
- Hosted PostgreSQL URL compatibility test
- Tests and documentation
- Launcher icon
- Debug APK

## Optional free public API hosting with Render

To keep your computer offline while the Android app uses the API, deploy the FastAPI service to Render Free:

1. Push the cloud package to a private or public GitHub repository.
2. Create an account at [render.com](https://render.com/).
3. Select **New → Web Service** and connect the repository.
4. Choose the `Dockerfile` in `lifelink_fastapi/` or set the root directory to `lifelink_fastapi`.
5. Choose the **Free** instance type.
6. Add these environment variables in Render:

```text
LIFELINK_DATABASE_URL=postgresql://USER:PASSWORD@HOST:5432/postgres?sslmode=require
LIFELINK_AUTH_REQUIRED=true
DB_POOL_SIZE=3
DB_MAX_OVERFLOW=5
```

7. Deploy and copy the HTTPS URL Render gives you.
8. Configure the Android app to use that FastAPI URL.

The current deployed service URL is `https://lifelink-api-uzje.onrender.com/`. Build the Android client with:

```bash
cd LifeLinkAndroid
./gradlew assembleDebug -PlifelinkApiBaseUrl=https://lifelink-api-uzje.onrender.com/
```

The repository also includes a **Build Android APKs** GitHub Actions workflow. It builds both a debug APK and an unsigned release APK on pushes to `main`, pull requests that modify `LifeLinkAndroid`, or manual workflow dispatch. Download the results from the workflow's **Artifacts** section. The manual dispatch form allows a different API base URL to be supplied for staging or local testing.

The release artifact is intentionally unsigned. Before distributing it through an app store or to end users, configure a protected Android signing key in GitHub Actions and sign the release with the same key used for future updates.

The production Android client must also provide a verified bearer token when `LIFELINK_AUTH_REQUIRED=true`. Until Firebase/JWT verification is connected, use `LIFELINK_AUTH_REQUIRED=false` only for controlled development testing; do not use that setting for a public emergency service.

Render Free services sleep after 15 minutes without inbound traffic and take about a minute to wake up. Render also provides a monthly free-instance-hour allowance and can suspend free services if usage limits are exceeded. It is suitable for an MVP or testing, not guaranteed production availability. See the [official Render free-service limits](https://render.com/docs/free).

The resulting architecture is:

```text
Android app → Render Free FastAPI → Supabase Free PostgreSQL
```

Supabase remains the database provider; Render hosts the API process.
