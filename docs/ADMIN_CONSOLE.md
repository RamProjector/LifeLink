# LifeLink Admin Console

The admin console is an additional, restricted interface for the existing FastAPI + Supabase + Android architecture. It does not create administrator accounts and does not grant admin access during public signup.

## Security model

- **Provisioning:** configure LIFELINK_ADMIN_USER_IDS on the API host with comma-separated Supabase Auth user UUIDs. Keep this server-side setting private to trusted operators. Never use an email, public profile field, Android preference, request body, or user_metadata claim as proof of administrator status.
- **Authentication:** the web console signs in with Supabase Auth using the project's publishable/anon key and passes the access token to the API. The Android console uses the existing signed-in session. The API verifies the token and then checks the configured UUID allowlist for every admin endpoint.
- **No service-role key:** never enter a Supabase service-role key in the browser or Android app. The UI only requires the public publishable/anon key.
- **User management:** the current interface manages requester/donor capabilities only. It cannot assign admin status, delete accounts, or disable authentication identities.
- **Audit trail:** admin session verification, dashboard views, user-list reads, user capability changes, audit-log reads, error-log reads, denied admin requests, and unhandled API/database errors are written to audit_events. Event details deliberately omit passwords, bearer tokens, request bodies, blood type, and precise locations.
- **Read-only audit interface:** there are no API endpoints for editing or deleting audit events. Database administrators must also restrict direct database mutation privileges on this table.

## Database migration

The API startup path also applies the same idempotent, additive column/index changes as `lifelink_fastapi/sql/011_admin_audit_metadata.sql`, because this service already performs schema upgrades during startup. The standalone SQL migration remains the canonical script for separately managed databases and manual rollout. It does not delete or rewrite existing audit events. Review the SQL and take the normal database backup before any separately managed production rollout.

The API uses the existing audit_events table. Ensure the application database identity can insert and select audit records, and restrict direct update/delete access as appropriate for your database roles and existing deployment. Do not make broad RLS or role changes without reviewing the current production policies.

## Provisioning an administrator

1. Sign in to Supabase Dashboard with an authorized project operator account.
2. Locate the intended account in Authentication → Users and copy its user UUID. Do not create a password or share one through source control.
3. Set LIFELINK_ADMIN_USER_IDS in the API host's private environment configuration to the approved UUID (multiple UUIDs may be comma-separated).
4. Restart/redeploy the API, then sign in using that account's existing verified credentials.
5. Verify GET /v1/admin/me returns is_admin: true. A normal account must receive HTTP 403; an unauthenticated request must receive HTTP 401.
6. Remove a UUID from the server-side allowlist to revoke administrator access. Existing sessions will be denied on their next admin API request.

No administrator UUID or credentials are configured by this change. Do not add the variable to a committed .env file.

## Web console

Serve admin-web/index.html over HTTPS using the configured static host. The deployed console uses preconfigured public client settings and exposes only email/password fields. These public settings are not secrets; never include a service-role key. The API origin is fixed in the console, and the access token remains in page memory and is cleared on sign-out. Configure LIFELINK_CORS_ORIGINS on the API host with only exact trusted web origins. The API rejects wildcard entries and does not enable credentialed CORS. Do not add broad origins.

## Android console

A verified session whose user UUID is on the server-side allowlist is routed to the native admin screen after GET /v1/admin/me succeeds. Other users continue to the normal requester/donor experience. A network failure does not grant admin access.

## API endpoints

- GET /v1/admin/me — verify admin authorization.
- GET /v1/admin/dashboard — profile, donor and request counts.
- GET /v1/admin/users?limit=50&offset=0 — paginated profile list.
- PATCH /v1/admin/users/{user_id} — update requester/donor capability fields; records before/after values.
- GET /v1/admin/audit?limit=50&offset=0 — recent audit events.
- GET /v1/admin/errors?limit=50&offset=0 — recorded API errors and denied admin requests.

All admin endpoints require a valid Supabase access token and an allowlisted user UUID. Admin authorization is not based on the role field returned by the normal profile endpoint.

## Verification checklist

- [ ] Apply migration in a test database first.
- [ ] Set an allowlisted test user UUID only in test/development server configuration.
- [ ] Confirm unauthenticated requests return 401 and ordinary users return 403.
- [ ] Confirm a profile's editable role/user metadata cannot grant admin access.
- [ ] Confirm dashboard, user-list, update, audit-read and error-read events are persisted.
- [ ] Verify user, audit, and error views can navigate through records beyond the first page without duplicating or skipping entries.
- [ ] Confirm a denied admin request and a simulated unhandled API error appear in the error view.
- [ ] Confirm no access tokens, passwords, exact locations, or request bodies are present in audit metadata.
- [ ] Configure exact-origin CORS and HTTPS before exposing the web console.
