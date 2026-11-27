# LifeLink Admin Console

The admin console is an additional, restricted interface for the existing FastAPI + Supabase + Android architecture. It does not create administrator accounts and does not grant admin access during public signup.

## Security model

- **Provisioning:** configure LIFELINK_ADMIN_USER_IDS on the API host with comma-separated Supabase Auth user UUIDs. Keep this server-side setting private to trusted operators. Never use an email, public profile field, Android preference, request body, or user_metadata claim as proof of administrator status.
- **Authentication:** the web console signs in with Supabase Auth using the project's publishable/anon key and passes the access token to the API. The Android console uses the existing signed-in session. The API verifies the token and then checks the configured UUID allowlist for every admin endpoint.
- **No service-role key:** never enter a Supabase service-role key in the browser or Android app. The UI only requires the public publishable/anon key.
- **User management:** the current interface manages requester/donor capabilities only. It cannot assign admin status, delete accounts, or disable authentication identities.
- **Session registry:** verified JWT session IDs are tracked in `lifelink_user_sessions` when a signed-in user reaches the protected admin API, including denied attempts. Admins can inspect and revoke recorded sessions. Revocation is checked on subsequent admin API requests; it does not claim to revoke Supabase refresh tokens or invalidate sessions on non-admin API routes yet.
- **Audit trail:** admin session verification, dashboard views, user-list reads, user capability changes, session inventory/revocation, audit-log reads, error-log reads, denied admin requests, and unhandled API/database errors are written to `audit_events`. The IDS/IPS audit envelope adds `session_id`, `log_id`, `actions`, `description`, `ip_address`, `timestamp`, and `device_used`, while retaining legacy columns for compatibility. Event details deliberately omit passwords, bearer tokens, request bodies, blood type, and precise locations.
- **IDS alerting:** repeated unauthorized admin access (five or more attempts in ten minutes) creates a deduplicated high-severity IDS-03 alert. Reuse of a revoked admin session creates an IDS-05 alert immediately. Repeated AAL1/unknown-assurance admin access creates IDS-06 alerts; repeated AAL2 access attempts with fewer than two verified TOTP factors create IDS-07 alerts. Alerts can be acknowledged, investigated, resolved, or marked false positive in the admin console.
- **Read-only audit interface:** there are no API endpoints for editing or deleting audit events. Database administrators must also restrict direct database mutation privileges on this table. The admin security-activity view displays the new audit context alongside the existing event metadata.

## Database migration

The API startup path applies idempotent, additive column/index changes for the existing audit table. The IDS/IPS foundation is also captured in `lifelink_fastapi/sql/017_ids_ips_audit_foundation.sql`. It backfills a stable `log_id` for existing events and retains the legacy `id`, `action`, and `created_at` columns. It does not delete existing audit events. Review the SQL and take the normal database backup before any separately managed production rollout.

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

Serve admin-web/index.html over HTTPS using the configured static host. The deployed console uses preconfigured public client settings and exposes only email/password fields. These public settings are not secrets; never include a service-role key. The API origin is fixed in the console, and access/refresh tokens remain in page memory and are cleared on sign-out. The console requests Supabase Auth sign-out; if the network request fails, local tokens are still cleared and the UI warns that server-side sign-out could not be confirmed. Configure LIFELINK_CORS_ORIGINS on the API host with only exact trusted web origins. The API rejects wildcard entries and does not enable credentialed CORS. Do not add broad origins.


## Admin MFA rollout status

The admin TOTP implementation and backend AAL2 authorization are being developed in draft PR #225. Web and Android enrollment/challenge flows are implemented in this branch, but CI, real-device, and non-production Supabase verification remain release blockers. This is **not approval to deploy or enable production enforcement**.

### Current draft sign-in sequence

1. The web console signs in through Supabase Auth using the publishable key.
2. Before asking the account to enroll, it calls `GET /v1/admin/preflight`. This minimal response confirms the signed-in identity, verified-email policy, server-managed UUID allowlist, active session, and revocation state, writes an audit event, and returns only `{ authorized: true }`; it exposes no operational admin data.
3. The console lists the account's TOTP factors. An AAL1 session with a verified factor is challenged; an account without a verified factor is prompted to enroll a primary factor.
4. After the first factor is verified, the console requires a second factor and asks the operator to attest that it is stored separately (different device or separately protected authenticator vault).
5. The web and Android consoles are shown only when the current JWT reports AAL2 and at least two TOTP factors are verified. Both clients call `/v1/admin/me` only after completing this gate.

The backend requires AAL2 plus at least two server-confirmed verified TOTP factors for operational admin endpoints, including `/me`, dashboard, users, audit, sessions, errors, and roles. It queries Supabase's `auth.mfa_factors` for rows matching the signed-in user, `factor_type = 'totp'`, and `status = 'verified'`; a missing or inaccessible inventory fails closed. The narrowly scoped `/v1/admin/preflight` is the only AAL1 exception and returns only `{ authorized: true }` after signed-token, verified-email, UUID allowlist, active-session, and revocation checks. `POST /v1/admin/mfa/events` has a separate AAL2-only dependency so the first verified factor can be audited without granting operational access. Each lifecycle receipt now independently queries the same server-managed factor inventory and enforces the event-specific minimum (one for initial enrollment, two for backup/replacement/recovery, and three before a removal request); client-reported counts are retained only as comparison metadata. A read-only inspection of the connected LifeLink Supabase project confirmed the table, UUID `user_id`, `factor_type` and `status` enums, and SELECT access for the `postgres` role; this does not prove which role the Render API connection uses, so verify the actual API connection role before rollout. Do not treat this draft as release-ready until CI, manual, and non-production verification pass. The Android gate currently supports enrollment and sign-in challenge; factor replacement/removal management remains in the web console.

### Session and secret handling

- Access and refresh tokens remain in page memory; the web console does not persist them in localStorage or sessionStorage.
- The refresh token is rotated through Supabase Auth before protected API calls when the access token is near expiry.
- The TOTP setup secret is displayed only during enrollment and cleared from the page after verification or sign-out. Never log, copy into tickets, or commit the QR payload, secret, OTP, access token, or refresh token.
- Supabase documents that verifying a factor promotes the current session to AAL2 and signs out the user's other active sessions. Operators must be warned before completing enrollment.
- LifeLink does not currently enable Supabase's experimental recovery-code feature, which requires an Auth-server opt-in and separate validation. The supported backup path remains a second TOTP factor held separately from the primary factor. Losing every verified factor can permanently prevent sign-in unless the approved operator recovery procedure is completed.

### Operator-assisted recovery runbook (documented; not yet operationally tested)

Supabase's privileged Admin MFA API can list and delete a user's factors. `admin.mfa.deleteFactor({ userId, id })` requires a server-side secret/service-role key and downgrades the user's sessions to AAL1 when a verified factor is deleted. Never call this API from the browser or Android app.

1. Open an incident ticket and verify the requester through a pre-established independent channel. Confirm the exact Supabase Auth user UUID against the approved administrator inventory. An email/password reset or access to the account's email inbox alone is insufficient.
2. Require a second, separately authorized operator to approve the reset. Record the ticket, reason, approvers, executing operator, target UUID, and UTC timestamps before changing factors.
3. If the user still controls one verified factor, do **not** reset all factors. Have them sign in using that factor, add and verify a replacement, confirm that two verified factors remain, then remove only the lost factor through the normal AAL2-protected flow.
4. If every factor is lost, the two operators may use the Supabase Admin MFA API from an approved operator environment to inspect and remove only the verified target user's factors. Keep the service-role key in the approved secret manager/operator environment; never commit it, send it to the user, or place it in the app.
5. Supabase invalidates active sessions and the next sign-in is AAL1. The user can access only the identity-only `/v1/admin/preflight` endpoint; all operational admin endpoints remain denied. They must enroll and verify a primary factor, enroll and verify a separately stored backup factor, and obtain a fresh AAL2 session before `/v1/admin/me` or any operational endpoint succeeds.
6. After two factors are verified, the user opens the web console's authenticator management section and submits the incident ticket reference using **Record approved recovery**. This sends `recovery_reenrollment_verified` to `POST /v1/admin/mfa/events`. **The API verifies the current factor count against Supabase's server-managed inventory and validates the ticket reference's format, but it does not query a ticketing system, verify operator identities, or enforce two-person approval.** The client-reported count is retained only for comparison. The two operators must independently reconcile the receipt against the Supabase Auth audit log and the incident record outside this endpoint. Never record factor secrets, QR payloads, OTPs, access/refresh tokens, or the service-role key.

This runbook does not create an AAL1 bypass or a public recovery endpoint. It is a controlled operator procedure, not an automated recovery service. The two-operator control currently exists as a documented operational requirement, not an application-enforced approval workflow. It must be exercised in a non-production project and reviewed by two authorized operators before being declared operational.

### Assurance level versus backup-factor count

Supabase's `aal2` claim proves that the current session completed at least one second-factor verification; it does not prove that two separate TOTP factors are registered. The two-factor enrollment rule is a recovery/resilience policy in addition to AAL2. Operational backend authorization independently counts verified TOTP factors from `auth.mfa_factors`, and lifecycle receipts enforce their event-specific minimum against that same server-managed inventory. The client-reported count is retained only as comparison metadata and never substitutes for the server count. Both authorization and receipt checks fail closed when the inventory cannot be read.

### Backend enforcement compatibility requirement

Both web and Android clients call `GET /v1/admin/preflight` before factor discovery. The endpoint runs signed-JWT, verified-email, server-managed admin UUID allowlist, active-session, and revocation checks, records an audit event, and returns only `{"authorized": true}`—no operational data. Each client requests the full `GET /v1/admin/me` response only after its AAL2 and two-factor gate.

The backend MFA boundary is now implemented in this branch. `/v1/admin/preflight` is the only AAL1 exception and returns identity eligibility only. `/v1/admin/mfa/events` requires allowlisted identity plus AAL2 so first-factor enrollment can be audited, but it exposes no operational data. All other admin endpoints require AAL2 and at least two server-confirmed verified TOTP factors from `auth.mfa_factors`. The test suite asserts that all registered admin routes use the correct dependency.

### Required before rollout

- The web and Android clients now include TOTP enrollment/challenge gates and require two verified factors before showing the console; the web factor manager requires a verified replacement before removal and preserves at least two verified factors.
- Backend AAL2 enforcement is implemented for every admin endpoint except identity-only preflight; the MFA audit-receipt route has a narrow AAL2-only enrollment dependency. All operational routes also require at least two server-confirmed verified TOTP factors from `auth.mfa_factors`; verify database permissions and direct API denial with one factor in non-production.
- MFA lifecycle audit receipts cover verified enrollment, replacement, removal requests, recovery re-enrollment, and denied AAL1/unknown-assurance requests. Their verified factor count is queried from Supabase's server-managed inventory; the client-reported count is stored separately for discrepancy review. Receipts contain no secrets, OTPs, or tokens.
- The two-operator recovery runbook is documented but has not yet been exercised. Test it in a non-production Supabase project and verify the Supabase Auth audit record plus the LifeLink recovery receipt.
- Add/complete backend tests for AAL1/AAL2, malformed/missing/stale/downgraded claims, revoked sessions, and non-allowlisted accounts; run the full suite on the latest head.
- Test primary enrollment, backup enrollment on a separate device, sign-in challenge, incorrect/expired codes, refresh rotation, factor loss, and recovery against a non-production Supabase account on both web and Android.
- Complete manual browser and Android-device verification. Automated syntax, unit, and transport tests do not replace real authenticator testing.


### MFA manual verification checklist (not yet executed)

Run this only against a dedicated non-production Supabase Auth project and a test administrator account. Do not use the production administrator account as the first test subject.

- [ ] A non-allowlisted account is rejected by `GET /v1/admin/preflight` before factor discovery or enrollment is shown.
- [ ] An allowlisted account with no verified factors is prompted to enroll a primary TOTP factor.
- [ ] An incorrect enrollment code does not mark the factor verified or open the admin console.
- [ ] Verifying the primary factor produces an AAL2 session and the UI requires a separately acknowledged backup factor on another device or separately protected vault.
- [ ] The console remains hidden until two verified TOTP factors are returned by Supabase and the current token reports AAL2.
- [ ] A returning account with factors but an AAL1 session sees the factor selector and challenge; a valid code upgrades the session before the console is shown.
- [ ] Invalid, expired, malformed, and rate-limited challenges produce safe feedback without exposing Supabase response bodies, secrets, OTPs, or tokens.
- [ ] Refresh-token rotation near expiry updates the in-memory token pair and protected API calls continue with the refreshed access token.
- [ ] With two verified factors, removal is unavailable; after adding and verifying a third factor, removing one of the three preserves two verified factors and forces a fresh sign-in because Supabase invalidates active sessions.
- [ ] Cancelling during enrollment, challenge, or verification does not allow a late response to reopen the console or restore a cancelled session.
- [ ] Sign-out clears the in-memory tokens and sends Supabase Auth sign-out for the current session.
- [ ] Missing JWT `aal` is treated as AAL1; malformed or unsupported values fail closed in the UI and backend.
- [ ] Verify Supabase's documented behavior that factor verification signs out other active sessions, and ensure the operator understands this before completing enrollment.
- [ ] Android: allowlisted AAL1 account reaches the native MFA gate through `/v1/admin/preflight`; ordinary users continue to the normal app without seeing admin setup.
- [ ] Android: no-factor account can enroll primary and separately stored backup TOTP factors; the setup secret is not persisted or logged.
- [ ] Android: returning AAL1 account can challenge a verified factor; AAL2 with two verified factors reaches `/v1/admin/me` and the native admin console.
- [ ] Android: incorrect/expired codes, network loss, cancellation, and audit-receipt failure do not open the admin console.

**Release blockers:** latest-head CI, manual web/Android verification, non-production Supabase tests, and a witnessed two-operator recovery exercise must pass. The AAL2 backend gate and audit receipt endpoints are implemented in this draft branch but are not production-approved. Do not mark this checklist complete until each item has reproducible evidence.

Until these conditions pass review and the rollout is explicitly approved, keep the AAL2 backend gate out of production and leave issue #222 open.

## Android console

An allowlisted Android session first calls `/v1/admin/preflight`, then uses the native TOTP enrollment/challenge gate. The native admin screen is reached only after AAL2, two verified TOTP factors, and a successful `/v1/admin/me` response. Other users continue to the normal requester/donor experience. A network failure or failed MFA verification never grants admin access.

## API endpoints

- GET /v1/admin/preflight — identity-only AAL1 eligibility check; returns no operational data.
- POST /v1/admin/mfa/events — AAL2-protected, bounded MFA lifecycle audit receipt; never accepts OTPs, secrets, or tokens.
- GET /v1/admin/me — verify admin authorization; requires AAL2.
- GET /v1/admin/dashboard — profile, donor and request counts.
- GET /v1/admin/users?limit=50&offset=0 — paginated profile list.
- PATCH /v1/admin/users/{user_id} — update requester/donor capability fields; records before/after values.
- GET /v1/admin/audit?limit=50&offset=0 — recent audit events.
- GET /v1/admin/sessions?limit=50&offset=0 — observed administrator sessions.
- GET /v1/admin/security/alerts?limit=50&offset=0 — security alert queue.
- PATCH /v1/admin/security/alerts/{alert_id} — acknowledge, investigate, resolve, or mark an alert as false positive.
- POST /v1/admin/sessions/{session_id}/revoke — revoke an observed session for subsequent admin API access.
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


## IDS/IPS audit foundation status

The first IDS/IPS implementation slice adds the required seven-field audit envelope to the existing audit table and exposes those fields in the admin security-activity view.

- `session_id` is taken from the verified Supabase JWT claim when present.
- `log_id` is a generated UUID with a unique index.
- `actions` classifies CRUD changes as ADD, EDIT, or DELETE; non-CRUD activity is OTHER.
- `description` records the bounded event code, not a request body.
- `ip_address` uses the ASGI peer address. Forwarded IP headers are intentionally not trusted until Render's trusted-proxy behavior is verified.
- `timestamp` is generated server-side in UTC.
- `device_used` is a bounded User-Agent value; it is not a device fingerprint.

This is an audit foundation, not a complete IDS/IPS. Role/permission assignment, cross-platform operation coverage, session revocation enforcement, detection rules, alert workflows, and the full Security Center remain subsequent implementation work. Do not treat a missing session claim or unavailable peer IP as verified identity evidence.


The current session registry records administrator sessions observed by the admin API. It is not yet a complete inventory of all requester/donor Android and web sessions. Extending session observation and revocation enforcement to every authenticated application endpoint is required before treating the registry as a whole-platform session manager.


The first active IDS rules are IDS-03 (repeated denied admin access), IDS-04 (missing session context), and IDS-05 (revoked session reuse). Thresholds are currently deterministic and intentionally narrow; they require tuning against real application traffic before wider automated blocking is enabled. Other planned detection categories are not yet implemented.

## Admin Console navigation and table usability

The existing admin website remains the single LifeLink operations and IDS/IPS console. The navigation is organized as a persistent desktop sidebar with Workspace and Security Center sections; on narrower screens it becomes a horizontally scrollable navigation strip. Existing routes/pages and server-side authorization remain unchanged.

Data views now provide:
- A page-local search field for the currently loaded records and a record-count indicator.
- Audit rows prioritize the event, outcome, actor/target context, and timestamp; internal audit row IDs are not presented as user-facing event data.
- A status/outcome filter when the returned dataset includes those fields.
- Responsive table rows that become labeled record cards on small screens, rather than forcing horizontal scrolling across every column.
- Compact, consistent spacing, sticky table headers on desktop, zebra rows, and clearer status/action grouping.
- Collapsible evidence and metadata blocks so large JSON values do not dominate the table; values remain escaped before display.
- Existing server pagination and alert/session/user actions are preserved. Search and filters operate only on the current server page and do not imply a global search.

The navigation/table redesign does not change API contracts, authorization, IDS/IPS detection rules, or audit data. Verify desktop, tablet, and phone widths; keyboard navigation; empty/loading/error states; and existing alert acknowledge/investigate/resolve and session-revoke actions before production release.


## Safe error feedback

Admin web actions and data loading map authentication, authorization, missing-record, timeout, conflict, rate-limit, server, and network failures to stable user-facing messages. Raw API response details and backend exception text are not displayed in the browser. The correlation ID remains available in the request header for server-side diagnostics.


## Cross-surface interaction standards (carried forward from closed UI-polish work)

- Keep the active navigation destination explicit and keyboard accessible at desktop, tablet, and phone widths; each navigation button identifies its controlled page region, and each region is labelled by the active page title.
- Give every admin action a clear success, failure, or unavailable outcome; announce asynchronous feedback accessibly and keep it near the affected workflow.
- For recoverable failures, preserve the current page and local search/filter context where safe so administrators do not lose their place.
- Display stable, actionable error copy only. Keep raw backend details and correlation context out of the browser UI; retain correlation identifiers in request/server diagnostics.
- Verify loading, empty, error, and permission-denied states alongside normal tables. These criteria do not expand the PR's API or IDS/IPS detection scope.
