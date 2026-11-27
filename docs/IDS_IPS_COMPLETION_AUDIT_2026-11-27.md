# LifeLink IDS/IPS completion audit

**Review date:** 2026-11-27  
**Base:** `main` at the time of review  
**Purpose:** Compare the existing LifeLink implementation with the project IDS/IPS criteria and define the smallest safe completion sequence.  
**Scope:** FastAPI PostgreSQL backend, SQL migrations, RBAC/security documentation, and read-only Supabase schema/data inspection. Android client source was inventoried, but a complete Android action-to-endpoint trace has not yet been completed.  
**Safety:** No production data, grants, policies, Auth settings, migrations, or deployments were changed.

## Executive finding

LifeLink already has a substantial IDS/IPS foundation. This is a completion and integration effort, not a greenfield implementation.

Existing components include:

- Audit-event storage with the required IDS/IPS fields.
- A shared audit writer and authenticated personal audit-history endpoint.
- An application session registry, Supabase-session validation, and administrator session-revocation controls.
- Security-alert storage, deduplication, severity/status lifecycle, and admin read/update endpoints.
- Three narrow active admin intrusion rules: IDS-03, IDS-04, and IDS-05.
- A role/permission registry, least-privilege seed mappings, role-applicability checks, and RBAC-01/RBAC-02 discrepancy alerts.
- Admin UI/API surfaces for viewing alerts, sessions, audit records, users, and role assignments.

The largest gaps are not missing tables. They are incomplete permission enforcement, incomplete and partly demand-driven detection, no verified end-to-end alert delivery, incomplete identity/role registry reconciliation, and lack of a complete evidence-backed acceptance run across Android and web actions.

## Criteria-to-implementation matrix

| Criteria | Existing implementation | Status | Required completion |
|---|---|---|---|
| IDS/IPS linked to the same application database | FastAPI uses the LifeLink PostgreSQL/Supabase database; IDS/IPS tables are in that database | Foundation present | Verify Android actions reach the same backend/database and document the end-to-end path |
| Identify all users | Admin user-list endpoint exists | Partial | Prove complete user inventory, pagination/total count, account-state coverage, and role/capability joins |
| Identify roles and permissions per user | Seven role definitions, 19 permissions, 19 role-permission mappings; role-read and user-role APIs exist | Partial | Reconcile legacy profile capabilities/admin allowlists with the new RBAC registry; provision assignments through a controlled process |
| Record user sessions | `lifelink_user_sessions`, authenticated-session validation, session inventory/revocation | Partial | Clearly label this as observed application sessions; reconcile Auth session lifecycle and test all clients |
| Record ADD/EDIT/DELETE operations | Audit schema and action normalization exist; numerous sensitive writes have atomic audit events | Partial | Finish remaining mutation coverage, confirm classification and actor attribution, and test all client-triggered mutations |
| Provide IDS/IPS flowchart | Not verified in the reviewed implementation/docs | Missing evidence | Add a flowchart matching actual event collection, validation, detection, prevention, alerting, and resolution |
| Seven required log fields | `session_id`, `log_id`, `actions`, `description`, `ip_address`, `timestamp`, `device_used` exist in the audit envelope | Implemented in schema | Validate populated/non-null behavior where applicable, safe device/IP attribution, and display/export |
| Every applicable action logged | Authenticated API middleware plus explicit transaction-bound events for sensitive operations | Partial | Route-by-route and client-to-route verification; close legacy/uncovered operations |
| Individual logs per user | `GET /v1/audit/me` filters by verified token subject | Implemented in API | Verify UI access and cross-account isolation with multiple real test users |
| Detect inappropriate role/permission assignments | RBAC applicability validation and RBAC-01/RBAC-02 alerts | Partial | Continuous/independent detection, role-source reconciliation, and notification delivery |
| Warn and notify administrator | Alert records and admin alert queue/lifecycle exist | Partial | Prove an actual administrator notification channel, delivery/retry state, unread indication, and end-to-end delivery |

## Existing repository implementation

### Database migrations

The repository includes:

- `005_audit_events.sql`
- `017_ids_ips_audit_foundation.sql`
- `018_user_session_registry.sql`
- `019_security_alerts.sql`
- `020_rbac_role_permission_registry.sql`

Migration 020 creates:

- `lifelink_roles`
- `lifelink_permissions`
- `lifelink_user_roles`
- `lifelink_role_permissions`

It seeds seven role definitions and 19 permission definitions. The initial migration deliberately does not grant elevated roles to accounts.

### Audit and user activity

- `GET /v1/audit/me` derives the actor from the verified Supabase token; a caller cannot request another user's history by supplying an actor ID.
- Admin audit endpoints expose audit records without providing API routes to edit/delete them.
- The audit schema includes the seven criteria fields and legacy-compatible fields.
- The existing audit coverage review records transaction-bound audit work for profile updates, emergency-request creation, contact lifecycle, donor profile operations, donor presence, exact-location disclosure, messages, saved messages, map visibility, contact sharing, and moderation.
- The same review identifies remaining legacy and lower-priority candidates, including legacy donor presence/availability and push-token registration. These must be reconciled against current source before marking complete.
- Exact-location reads are treated as sensitive disclosure events; coordinates are excluded from audit metadata.
- General API-boundary logging is a fallback, not a substitute for atomic audit writes on critical mutations.

Reference: `docs/SECURITY_AUDIT_COVERAGE_REVIEW_2026-11-22.md`.

### Sessions

- Supabase JWTs are verified by the backend.
- `get_active_postgres_principal` checks the supplied session ID against `auth.sessions` and registers the observed session in `lifelink_user_sessions`.
- Protected application API requests check session validity and the application registry.
- Administrators can inspect and revoke observed application sessions.
- Session revocation is an application-side control. Do not describe it as revoking Supabase refresh tokens unless a separate Auth revocation operation is implemented and tested.
- A session only appears in the application registry after it has been observed by the API; it is not automatically a complete inventory of all Auth sessions.

### Intrusion detection and alert lifecycle

Confirmed narrow rules:

- IDS-03: repeated unauthorized administrator access; threshold is five attempts within ten minutes.
- IDS-04: missing administrator session context.
- IDS-05: reuse of a revoked administrator session.

The security-alert API also calls the RBAC discrepancy scanner when a normal unfiltered alert-list request is made. A correlation-filtered request intentionally skips the scan to preserve correlation-based filtering.

This means the RBAC discrepancy scan is currently demand-driven by the alert-list endpoint, not demonstrated as a scheduled or independently running monitor. If no administrator opens the normal alert list, this path does not establish continuous detection.

The alert table supports open, acknowledged, investigating, resolved, and false-positive states. The API can update alert state and deduplicate active incidents.

**Not yet verified:** delivery to an administrator through push notification, email, or another actual notification channel. A row in `lifelink_security_alerts` is not proof that an administrator was notified.

### RBAC and authorization

The RBAC registry has seven roles and 19 permissions. The connected Supabase project currently contains 19 role-permission mappings.

The read-only live database inspection returned:

| Registry | Live rows |
|---|---:|
| Roles | 7 |
| Permissions | 19 |
| Role-permission mappings | 19 |
| User-role assignments | 0 |
| Security alerts | 8 |
| Observed sessions | 24 |

The zero user-role assignment count is significant: the registry currently has no assigned users, even though the legacy server-side administrator allowlist and existing profile capabilities continue to govern application access.

The current admin API still uses the server-side admin allowlist through `require_admin` for its routes. The new permission keys are defined and mapped, but the existing admin endpoints have not all been migrated to per-permission authorization checks. Role-assignment changes are separately protected by the RBAC-manager allowlist and applicability checks.

Consequently, the registry is currently a policy foundation and management surface, not yet the universal enforcement authority for all administrative routes.

## Highest-priority updates

### P0 — Make authorization enforceable

1. Define one server-side permission dependency that reads effective, non-expired RBAC assignments and role-permission mappings.
2. Map every admin endpoint to a permission key:
   - dashboard: `dashboard.read`
   - user listing: `users.read`
   - user capability changes: `users.update`
   - alert reads: `security.alerts.read`
   - alert lifecycle updates: `security.alerts.manage`
   - session listing: `security.sessions.read`
   - session revocation: `security.sessions.revoke`
   - audit/error reads: `audit.read` (or a separately defined permission if error-log access must be narrower)
   - role registry/user-role reads: `roles.read`
   - role assignment: `roles.assign` plus the existing manager safeguard
3. Preserve a secure bootstrap path so the first trusted security/RBAC administrators can be provisioned out of band. Never infer elevated access from public signup or client-editable profile metadata.
4. Fail closed when the registry is unavailable or a permission is absent.
5. Add tests proving each role can perform only its intended endpoints and that expired assignments confer no access.

### P0 — Complete role reconciliation and discrepancy detection

1. Define how the legacy admin allowlist and profile capability fields map to the RBAC registry.
2. Provision initial assignments through an explicitly approved, audited, out-of-band process.
3. Detect missing, expired, unexpected, and conflicting assignments.
4. Run discrepancy checks independently of an administrator opening the alerts page. Use a bounded scheduled/background mechanism or another independently invoked server-side trigger with deduplication and health visibility.
5. Keep invalid assignments blocked at write time and detected if introduced through an out-of-band path.

### P0 — Prove administrator notifications

1. Identify the existing notification provider and supported channels already configured for LifeLink.
2. Define which severities/events require immediate notification.
3. Add delivery state, retry/backoff, deduplication, and failure visibility.
4. Add an unread security-alert indicator in the admin console.
5. Test an induced RBAC discrepancy from detection through actual delivery and acknowledgement.

Do not introduce a paid provider or new external service without approval.

### P1 — Finish audit coverage

1. Reconcile all current FastAPI routes with the existing audit coverage document.
2. Trace Android UI actions to API routes so that the criteria's "every action" requirement is interpreted as every applicable security-relevant action, not indiscriminate logging of every screen render.
3. Close remaining high-impact gaps, including legacy donor mutation routes and push-token credential registration/removal.
4. Decide whether donor response, location reads, and other sensitive read operations require explicit access events.
5. Keep critical mutation and audit insertion in one database transaction.
6. Do not log tokens, message content, contact values, blood type, precise GPS coordinates, or raw request payloads.
7. Add tests for action normalization, event attribution, metadata privacy, idempotency, and rollback.

### P1 — Session and device/IP quality

1. Define "device_used": sanitized user-agent/device class rather than a raw or client-asserted device identity.
2. Define trusted-proxy configuration before using forwarded IP headers. The current server peer address can be the proxy address.
3. Define retention and cleanup for audit/session data.
4. Verify sign-out, refresh, expired Auth session, administrator revocation, and cross-client behavior.
5. Make clear that observed sessions are not necessarily every Supabase Auth session.

### P2 — Admin console and academic evidence

1. Show users with legacy capabilities, effective RBAC roles, permissions, and assignment expiry together.
2. Show each user's own audit history and session history under access controls.
3. Show alert severity, rule, correlation, occurrence count, first/last seen, status, and notification delivery state.
4. Add the requested IDS/IPS flowchart and role/permission matrix.
5. Prepare a criteria-based manual test record with screenshots/log IDs and expected/actual outcomes.

## Route and operation inventory to finish

The PostgreSQL API route inventory includes:

- Account/profile: profile read/update and push-token registration.
- Emergency requests: create, contact, contact listing/status, report/block, manual broadcast, history/detail, cancel, fulfill.
- Donor profile: create/update/read, presence, availability, delete.
- Donor operations: legacy donor read/update/presence/availability, inbox, response, map, visibility.
- Location sharing: grant, revoke, exact-location read.
- Conversations: list, message list/send, saved-message list/add/remove, contact-share list/create, report/block.
- Admin: dashboard, user listing/update, alert list/update, session list/revoke, audit/errors, roles, user-role read/assignment.
- Personal audit: own activity history.

For each mutating or sensitive-read route, the next implementation pass must record: Android/web entry point, HTTP route, authenticated actor source, authorization rule, audit event, required seven fields, transaction boundary, detection rule, prevention response, and notification behavior.

## Live-database inspection notes

Read-only SQL inspection on 2026-11-27 confirmed the seven public IDS/RBAC tables exist and reported the row counts above. The inspected tables had RLS enabled. Client roles do not receive direct access to these backend-only registries; the API uses its backend database identity.

This inspection did not read user-identifying audit evidence, modify records, change grants, or apply migrations.

## Acceptance gate

Do not declare IDS/IPS complete until all of the following are demonstrated:

- Every required criterion has a mapped implementation and a passing evidence item.
- Every admin endpoint enforces its mapped permission server-side.
- RBAC assignments are reconciled with the existing LifeLink identity/capability model.
- RBAC discrepancy detection runs independently of a dashboard read.
- An actual administrator notification is delivered and its outcome recorded.
- All required audit fields are populated with trustworthy, privacy-safe values.
- Each user's audit history is isolated from other users.
- High-impact operations and their audit events commit or roll back together.
- Session revocation and expiry are enforced on protected application routes.
- Android and web workflows have been traced and manually validated.
- The flowchart, role/permission matrix, and criteria test evidence are complete.

No production migration, permission grant, role assignment, deployment, or merge is authorized by this audit.

## Android client-to-backend trace

The Android Retrofit interface at `LifeLinkAndroid/app/src/main/java/com/lifelink/app/data/remote/LifeLinkApi.kt` calls the same PostgreSQL API for the following groups:

| Android feature/API group | Backend route families | Audit/security follow-up |
|---|---|---|
| Admin | `/v1/admin/me`, dashboard, users, user update, audit, errors | Add alert/session/role APIs to Android only if the app actually exposes those admin workflows; enforce permission on every server route regardless of client |
| Account | profile read/update, push-token registration | Verify profile event and push-token registration/removal event; token must never be stored in audit metadata |
| Requester | emergency request create/detail/history/cancel/fulfill, contact selected donors, contact status/report/block, manual broadcast | Retain atomic request/contact events; confirm all failed/denied operations are safely observable |
| Donor | donor profile create/read/update, presence, availability, delete, map, visibility, inbox, response | Reconcile legacy donor routes with newer donor-profile routes; ensure response and legacy availability/presence are audited |
| Location sharing | activate/revoke share, matched donor exact-location read, open conversation | Preserve atomic grant/revoke and exact-location disclosure audit; never include coordinates |
| Messaging | conversation list/messages, send, saved-message add/remove, contact-share list/create, report/block | Preserve content-free message/bookmark/contact-share audit and moderation audit; never store message bodies, attachments, or disclosed contact values |

This is an interface-level trace, not proof that every UI action has been manually exercised. The remaining client verification must map each screen/view-model action to the Retrofit method and confirm the authenticated user ID, expected server authorization, audit event, and user-visible result. The app's push-token registration endpoint is present in the interface; no corresponding alert-delivery linkage was found in the security-alert implementation search.
