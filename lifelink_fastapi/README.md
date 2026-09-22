# LifeLink FastAPI Matching Service

This package implements the emergency blood-request endpoint and explainable donor matching flow.

## Does it cost money?

No. Running the API on your own computer for development and testing is free. The demo mode uses in-memory storage and needs only Python packages. The PostgreSQL mode is also free when PostgreSQL runs locally. Costs may begin only when you deploy the service to a hosted server, use a managed database, send production push notifications, or connect paid mapping/routing and authentication services.

The easiest hosted free option for this project is **Supabase Free**: it provides managed PostgreSQL and supports the PostGIS extension needed by the schema. Supabase currently lists two free projects, 500 MB database size per project, and 5 GB monthly egress. Free projects can pause when inactive, so this is appropriate for an MVP and testing rather than guaranteed production uptime. See the [Supabase Free Plan](https://supabase.com/docs/guides/platform/billing-on-supabase) and [Supabase PostGIS guide](https://supabase.com/docs/guides/database/extensions/postgis).

## Fastest free demo

Use this mode to test the API without PostgreSQL:

```bash
cd lifelink_fastapi
python3 -m venv .venv
source .venv/bin/activate              # Windows: .venv\\Scripts\\activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

Then open `http://localhost:8000/docs`. This mode is suitable for local development and automated tests; data is reset whenever the server stops.

## Run locally with PostgreSQL

The database layer uses PostgreSQL with async SQLAlchemy 2.0 and recommends PostGIS for distance queries.

```bash
cd lifelink_fastapi
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export LIFELINK_DATABASE_URL='postgresql+asyncpg://lifelink:lifelink@localhost:5432/lifelink'
psql "postgresql://lifelink:lifelink@localhost:5432/lifelink" -f sql/001_initial_schema.sql
uvicorn app.main_postgres:app --reload --port 8000
```

Open the API documentation at `http://localhost:8000/docs`.

## Free cloud database with Supabase

1. Create a project at [supabase.com](https://supabase.com/) and choose the Free plan.
2. In **Database → Extensions**, enable `postgis`. Supabase may place it in the `extensions` schema; the LifeLink migration includes that schema in its search path.
3. Copy the project’s PostgreSQL connection string from **Connect → ORMs / Connection string**. Prefer the session or transaction pooler string if the provider recommends it for serverless hosting.
4. From a machine with `psql` installed, create the LifeLink tables:

```bash
cd lifelink_fastapi
psql "postgresql://USER:PASSWORD@HOST:5432/postgres?sslmode=require" \
  -f sql/001_initial_schema.sql
```

5. Configure and start the PostgreSQL-backed API without committing the password:

```bash
export LIFELINK_DATABASE_URL='postgresql://USER:PASSWORD@HOST:5432/postgres?sslmode=require'
export LIFELINK_AUTH_REQUIRED='true'
uvicorn app.main_postgres:app --host 0.0.0.0 --port 8000
```

The application converts `sslmode=require` to the `ssl=require` parameter expected by `asyncpg`. Use `.env.example` as a template, but keep the real `.env` file out of Git.

The database is cloud-hosted, but the FastAPI server still needs a reachable host. For a completely free development setup, run FastAPI on your own computer and connect it to Supabase. To make the API publicly reachable, deploy FastAPI to a hosting provider with a free tier or run it on your own server; availability and free-tier limits vary.

**Important:** Supabase is the database provider, not the complete API hosting solution. The Android app needs the public URL of the FastAPI server, not the Supabase URL.

## Optional free public API hosting with Render

The repository includes `Dockerfile` and `render.yaml` for deploying the PostgreSQL-backed API to Render Free. Create a Render Web Service from the repository, choose the Free instance type, and set these environment variables in the Render dashboard:

```text
LIFELINK_DATABASE_URL=postgresql://USER:PASSWORD@HOST:5432/postgres?sslmode=require
LIFELINK_AUTH_REQUIRED=true
DB_POOL_SIZE=3
DB_MAX_OVERFLOW=5
```

Render Free services sleep after 15 minutes without inbound traffic and take about a minute to wake. They also have monthly free instance-hour and bandwidth limits, so this is suitable for an MVP or testing, not guaranteed production availability. See the [official Render free-service limits](https://render.com/docs/free).

The free public architecture is:

```text
Android app → Render Free FastAPI → Supabase Free PostgreSQL
```

Outbound push notifications are enabled by setting `FIREBASE_SERVICE_ACCOUNT_JSON` in the Render dashboard to the complete JSON service-account key for the Firebase project used by the Android app. The API registers Android tokens at `PUT /v1/push-token` and sends notifications for donor matches, requester contact selections, and donor responses. Delivery is skipped safely when the secret is absent, so local development remains usable.

## PostgreSQL package

- `sql/001_initial_schema.sql` — PostgreSQL/PostGIS DDL, enums, constraints, indexes, geography triggers, and tables.
- `app/db_models.py` — SQLAlchemy 2.0 typed models.
- `app/db.py` — async engine and session dependency.
- `app/repositories.py` — PostgreSQL donor and request repositories.
- `app/main_postgres.py` — async FastAPI route adapter using PostgreSQL.

## Endpoint

```text
POST /v1/emergency-requests
GET  /v1/emergency-requests/{request_id}
POST /v1/emergency-requests/{request_id}/manual-broadcast
POST /v1/emergency-requests/{request_id}/cancel
POST /v1/emergency-requests/{request_id}/contact
PUT  /v1/donors/{donor_id}
PATCH /v1/donors/{donor_id}/availability
GET  /v1/donors/{donor_id}/requests
POST /v1/donors/{donor_id}/requests/{request_id}/response
```

The endpoint:

1. Validates the request payload.
2. Enforces idempotency using `idempotency_key`.
3. Filters donors by rule-based ABO/Rh compatibility.
4. Scores eligible donors by distance, estimated travel time, availability freshness, verification, response likelihood, and urgency.
5. Returns a ranked list with a human-readable explanation for every score.
6. Returns a manual-fallback response if blood type is unknown or automatic matching is unavailable.

Blood-type compatibility is deliberately rule-based. The prioritization score is not a medical eligibility decision and must be reviewed against local blood-bank policy before production use.

The status endpoint is used by the Android active-request screen for polling. Cancellation changes the request to the terminal `cancelled` state and should be protected by authenticated coordinator identity in production.

Donor devices use the donor endpoints to register a profile, set availability, view only their eligible request matches, and accept, decline, or mark arrival. The demo app identifies callers by path IDs; production must enforce Firebase or equivalent authentication and ownership checks.

Set `LIFELINK_AUTH_REQUIRED=true` in a deployed environment to reject anonymous mutation requests. The current dependency validates the Bearer shape as a development seam; replace it with Firebase/JWT signature verification and subject-to-resource ownership checks before production.

## Existing demo route

`app/main.py` remains a self-contained in-memory version for fast tests and demos. The PostgreSQL-backed application is `app/main_postgres.py`.

## Production integration points

Add Firebase Authentication/JWT verification for requester identity, a queue/worker for high-volume push notifications, an audit log for request lifecycle changes, and a real routing provider for travel-time estimates. Run schema changes through Alembic after the initial migration rather than calling `Base.metadata.create_all()` in production.
