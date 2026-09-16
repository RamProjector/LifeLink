# LifeLink FastAPI on Render Free

This guide deploys the API publicly while Supabase hosts PostgreSQL:

```text
Android app → Render Free FastAPI → Supabase Free PostgreSQL
```

## Prepare the repository

Push the contents of the `LifeLink-Cloud` package to a GitHub repository. The repository should contain these paths:

```text
lifelink_fastapi/
  Dockerfile
  render.yaml
  requirements.txt
  app/
  sql/
```

## Create the Render service

1. Open [Render](https://render.com/) and select **New → Web Service**.
2. Connect the GitHub repository.
3. Set **Root Directory** to `lifelink_fastapi`.
4. Set the runtime to **Docker**. Render should detect `Dockerfile` automatically.
5. Select the **Free** instance type.
6. Set the health check path to `/health` if Render does not detect it.
7. Add these environment variables:

```text
LIFELINK_DATABASE_URL=postgresql://USER:PASSWORD@HOST:5432/postgres?sslmode=require
LIFELINK_AUTH_REQUIRED=true
DB_POOL_SIZE=3
DB_MAX_OVERFLOW=5
```

8. Deploy the service.
9. Test the generated URL:

```bash
curl https://YOUR-RENDER-SERVICE.onrender.com/health
```

Expected response:

```json
{"status":"ok","service":"lifelink-matching-postgres"}
```

## Configure Android

Set the Android app’s API base URL to:

```text
https://YOUR-RENDER-SERVICE.onrender.com/
```

Do not use the Supabase URL in the Android app. Supabase is the database; Render is the API host.

## Free-tier behavior

Render Free services sleep after 15 minutes without inbound traffic and may take approximately one minute to wake. They have monthly free instance-hour and bandwidth limits. They can be restarted or suspended, so this option is suitable for an MVP and testing rather than guaranteed production uptime.

Do not store application data on Render’s local filesystem. It is ephemeral. LifeLink data belongs in Supabase PostgreSQL.

## Security checklist

- Keep `LIFELINK_DATABASE_URL` only in Render environment variables.
- Never commit the real database password.
- Keep `LIFELINK_AUTH_REQUIRED=true` for the public service.
- Replace the current development bearer-token seam with verified JWT/Firebase/Supabase Auth before real-world deployment.
- Use HTTPS only in the Android app configuration.
