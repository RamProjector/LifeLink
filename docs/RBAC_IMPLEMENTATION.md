# LifeLink RBAC implementation notes

## Schema

Migration `020_rbac_role_permission_registry.sql` adds a many-to-many role and permission registry:

- `lifelink_roles`
- `lifelink_permissions`
- `lifelink_user_roles`
- `lifelink_role_permissions`

It seeds requester/donor and four administrative role types plus a super-administrator role. It does not assign any elevated role to an account. Existing profile role/capability fields remain intact for compatibility.

## API

- `GET /v1/admin/roles` lists role definitions and mapped permissions.
- `GET /v1/admin/users/{user_id}/roles` lists current role assignments.
- `PUT /v1/admin/users/{user_id}/roles` replaces a user's active role assignments.
  - Supply one or more known `role_keys` to replace the assignment set.
  - Supply an empty `role_keys` array to revoke all RBAC assignments; this is intentionally distinct from omitting the field.
  - Requests are limited to eight roles, reject duplicates and unknown keys, and cannot modify the manager's own assignments.

Role assignment requires both ordinary administrator authentication and membership in the separate server-side `LIFELINK_RBAC_MANAGER_USER_IDS` allowlist. Do not populate this variable from user-editable profile metadata or public signup. Use verified Supabase Auth UUIDs and keep the list in the backend's private environment configuration.

The server-managed admin allowlist and MFA checks remain the identity/bootstrap gate. Operational admin routes now declare permission-specific dependencies. The rollout is deliberately staged by the backend-only `LIFELINK_RBAC_ENFORCEMENT_MODE` setting: `observe` (the default) preserves current allowlist + MFA behavior while assignments are provisioned; `enforce` requires an active, non-expired administrative role assignment containing the exact permission for each route. Unknown mode values fail closed with HTTP 503. Requester/donor application authorization continues to use its existing policy.

## Personal activity history

- `GET /v1/audit/me` returns a paginated activity history for the authenticated account.
- The actor filter is derived exclusively from the verified Supabase token subject; callers cannot supply another account ID.
- Sensitive metadata is redacted using the same bounded audit-metadata sanitizer used by the Admin Console.
- Reading personal history is itself recorded as `audit.self.read`.
- The authenticated `/v1` API activity middleware records route-level reads and successful/denied mutation attempts for non-admin application endpoints. It stores the verified token subject, session ID when present, server-observed peer IP, user-agent, timestamp, normalized action, safe route template, and status code; it never stores request bodies, message text, blood type, or precise coordinates.
- The middleware is an API-boundary audit fallback, not an atomic database trigger. A completed business transaction can therefore outlive a failed audit write; failures are surfaced to server logs. Critical mutation paths should continue moving their audit write into the same database transaction.


## Role applicability and discrepancy detection

Role assignments are many-to-many, but that does not mean every role is valid for every account. The RBAC manager endpoint now checks applicability before replacing assignments:

- `requester` requires the account profile's `can_request` capability.
- `donor` requires `can_donate` or an existing donor profile/legacy donor record.
- Administrative roles require the target account to be in the server-managed `LIFELINK_ADMIN_USER_IDS` allowlist.
- `super_admin` additionally requires the target account to be in `LIFELINK_RBAC_MANAGER_USER_IDS`.
- Unknown role keys and roles without an explicit applicability policy are rejected.

The RBAC assignment API validates applicability before accepting changes. The independent background scanner periodically checks active assignments for applicability mismatches (`RBAC-01`) and checks each role's exact administrative-permission policy for missing or unexpected grants (`RBAC-02`). The four operational administrative roles have their reviewed least-privilege matrices; `requester`, `donor`, and `super_admin` have explicit zero administrative permission grants. Requester/donor application actions continue to use the existing capability, resource-ownership, and conversation-participant checks. `super_admin` uses a separate dual allowlist gate and does not receive wildcard or seeded permission rows. RBAC-03 checks all seven role definitions independently of user assignments. Findings are recorded as deduplicated alerts; role-policy findings use the dedicated role-scoped findings table. Invalid new assignments are blocked and audited rather than silently accepted.

Requester and donor remain independently assignable, so one account may legitimately hold both roles when both capabilities are enabled. The legacy `lifelink_profiles.role` field remains for compatibility and is not itself a grant of administrative authority.

## Rollout safety

1. Reconcile the live database migration ledger before applying migration 020.
2. Review RLS and the backend-only table exemption registry before deployment.
3. Apply the migration only through the reviewed deployment process.
4. Configure `LIFELINK_RBAC_MANAGER_USER_IDS` privately before enabling role assignment.
5. Provision at least two trusted RBAC managers out of band; do not self-assign roles.
6. Verify assignment, removal, unknown-role rejection, and audit records in a non-production environment first.

No live database changes are performed by adding this migration to the repository.


## 2026-11-27 completion audit clarification

A read-only inspection of the connected LifeLink database found 7 role definitions, 19 permissions, 19 role-permission mappings, and **0 user-role assignments**. Existing access therefore still depends on the legacy server-managed administrator allowlist and application capability policies; the registry is not yet the universal source of effective authorization.

The RBAC discrepancy scan runs as a bounded FastAPI lifespan task at a configurable finite interval (default 300 seconds, minimum 60 seconds, maximum 86,400 seconds), independently of administrator alert-page reads. A PostgreSQL transaction-scoped advisory lock prevents overlapping scans across API instances, and each scan transaction has a 15-second statement timeout. The scanner now persists last start/completion/success/failure timestamps, finding count, duration, consecutive failures, and a sanitized error code in `lifelink_security_scanner_runs`. `GET /v1/admin/security/scanner` exposes this record to the web Admin Console. This remains in-process monitoring, not an external always-on service: host sleep/restarts can interrupt it, so the last successful timestamp is the freshness signal.

The audit did not verify background push/email or out-of-band delivery of RBAC/IDS alerts. The web Admin Console now offers opt-in foreground browser notifications while the authenticated console remains open, but that is not proof of delivery after sign-out or console closure. Persisting an alert row and displaying it in the Admin Console are not proof that an administrator received an out-of-band notification. Do not claim background delivery until a supported channel, recipient resolution, retry state, and end-to-end receipt are tested.

These findings are repository/live-schema observations only. No user-role assignments, production grants, policies, migrations, or deployment settings were changed.


## Scanner scope and policy boundaries (2026-11-27)

- Migration 020 seeds permission mappings for the four administrative roles with reviewed least-privilege matrices. The scanner now also has explicit empty administrative-permission matrices for `requester`, `donor`, and `super_admin`; unexpected administrative grants to any of these roles are detected rather than silently ignored.
- Requester/donor application access is not represented by the 19 administrative permission keys. It continues to be governed by capability, resource ownership, and participant checks in the application API. Their zero administrative grant matrix does not disable ordinary requester/donor functionality.
- `super_admin` is a deliberately separate privileged path: in enforce mode it satisfies operational route permissions only when the signed-in subject is in both the ordinary administrator allowlist and the separate `LIFELINK_RBAC_MANAGER_USER_IDS` allowlist. It has no ordinary permission-map grants, and role assignment retains its independent manager check. RBAC-01 detects a super-admin assignment when the target is removed from either allowlist.
- RBAC-02 checks active user-role assignments for applicability and unexpected/missing grants. RBAC-03 independently checks all seven approved role policies even when no users hold those roles, using the dedicated role-scoped findings table introduced by migration 021.
- The live read-only snapshot found zero user-role assignments. This no longer prevents RBAC-03 from detecting drift in the four reviewed role-permission mappings.
- CI on head `2c38d2ba58cdf66751719614be7991e26cb41075` passed backend tests (including PostgreSQL integration tests), Ruff, mypy, Docker/admin-site validation, Android quality gate, Gitleaks, and Python plus Java/Kotlin CodeQL. This verifies the tested code on that commit, not deployed runtime behavior.


## Admin route authorization inventory (implementation update, 2026-11-27)

Operational admin routes now use permission-specific dependency guards. The guards always retain the existing server-managed allowlist, verified identity, active-session, AAL2, and two-verified-TOTP checks. Enforcement is staged by `LIFELINK_RBAC_ENFORCEMENT_MODE`: default `observe` preserves current access while the live registry has zero user-role assignments; `enforce` checks the exact active, non-expired permission grant and denies missing grants. Invalid mode values fail closed with HTTP 503.

| Route | Permission in enforce mode |
|---|---|
| `GET /v1/admin/me`, dashboard | `dashboard.read` |
| `GET /v1/admin/users` | `users.read` (includes the effective RBAC role keys and mapped administrative-permission summary in the directory; full role registry and per-user assignment details remain `roles.read`) |

The approved disclosure scope for `users.read` includes the account/profile fields plus a read-only summary of each account’s active RBAC role keys and mapped administrative permission keys in the user directory. It does not permit listing the full role registry or retrieving assignment timestamps/expiry details; those remain behind `roles.read`. This makes the `/users` summary an explicit part of the `users.read` permission contract rather than an accidental bypass of the dedicated role-detail route.
| `PATCH /v1/admin/users/{user_id}` | `users.update` |
| `GET /v1/admin/security/role-integrity`, audit, errors | `audit.read` |
| `GET /v1/admin/security/alerts`, scanner health, browser notification feed | `security.alerts.read` |
| `PATCH /v1/admin/security/alerts/{alert_id}` | `security.alerts.manage` |
| `GET /v1/admin/sessions` | `security.sessions.read` |
| `POST /v1/admin/sessions/{session_id}/revoke` | `security.sessions.revoke` |
| `GET /v1/admin/roles`, user-role reads | `roles.read` |
| `PUT /v1/admin/users/{user_id}/roles` | Separate `LIFELINK_RBAC_MANAGER_USER_IDS` allowlist and applicability checks |

Do not change the backend mode to `enforce` until all current administrators have approved role assignments and the exact role-to-route matrix has passed non-production verification. The current seed has no dedicated manager-role grant for `roles.assign`, so the separate RBAC-manager safeguard remains authoritative for role assignment.

This is an implementation and unit-test update, not a claim of production deployment or live permission enforcement. No user-role assignments or production grants were added.


## Role-level policy integrity detector (2026-11-27)

Migration `021_role_policy_integrity_findings.sql` adds `lifelink_role_security_findings`, a role-targeted finding store with its own `(rule_id, role_key)` active-finding uniqueness key. It is intentionally separate from `lifelink_security_alerts`; role identifiers are never written into the user-scoped `user_id` field.

The periodic scanner now evaluates the four explicitly approved role policies directly from `lifelink_roles` and their permission mappings, independent of `lifelink_user_roles`. It raises RBAC-03 for missing role definitions, missing grants, or unexpected grants; increments occurrences only when evidence changes; and automatically resolves active RBAC-03 findings when the approved mapping is restored. Roles without an approved exact permission matrix (`requester`, `donor`, and `super_admin`) are not treated as empty-permission roles. Requester/donor have application capability policies; super-admin uses the separately allowlisted privileged path described above.

The new read-only admin endpoint is `GET /v1/admin/security/role-integrity` (server-side `require_admin` remains in force). The API startup schema routine now creates the role-finding table, indexes, and RLS setting idempotently, matching migration 021. The SQL migration remains available for separately managed schema rollouts. No production migration or deployment was performed as part of this PR.


## Continuation implementation update (2026-11-27)

- Added route-specific RBAC permission dependencies and four focused tests for observe compatibility, exact active permission grants, denied permissions, and invalid mode configuration.
- Added migration `022_security_scanner_health.sql`, idempotent API startup schema creation, scanner success/failure persistence, and the protected `GET /v1/admin/security/scanner` endpoint.
- Added a Security alerts page panel showing persisted scanner status, last success/failure, last finding count, consecutive failures, and sanitized error code.
- Android administrator screens, the native MFA gate, admin Retrofit endpoints/DTOs, and the Android-only TOTP client methods/tests were removed. The admin console is web-only; the Android app remains requester/donor only.
- No admin RBAC assignments were provisioned and `LIFELINK_RBAC_ENFORCEMENT_MODE` remains in its safe default `observe` mode. Do not enable enforcement until a reviewed out-of-band provisioning plan has assigned real administrator UUIDs.
- A live LifeLink database cleanup removed only explicitly marked demo security alerts, audit rows with demo/example/fixture metadata, and five synthetic session rows. Remaining operational audit/session records and the RBAC role/permission registry were preserved.
- Administrator alert delivery through a background push/email channel is still not implemented or verified. The Android app no longer contains admin support, and no service-worker-based web-push subscription/delivery channel currently exists. The opt-in foreground browser notification feature is documented separately below. The existing Admin Console alert queue remains available, but it must not be described as out-of-band notification delivery.


### Administrator notification channel status

The web Admin Console now supports opt-in browser notifications while the authenticated console remains open. It polls a permission-protected, cursor-based feed of new security alert and role-policy event metadata every 60 seconds, displays a generic notification without evidence or personal data, and stops on sign-out or authorization failure. This is an in-console browser notification only; no service worker, background web push, email delivery, or notification delivery while the console is closed is implemented or claimed.
