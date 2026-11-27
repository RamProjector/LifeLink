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

The RBAC assignment API validates applicability before accepting changes. The independent background scanner periodically checks active assignments for applicability mismatches (`RBAC-01`) and checks the configured exact permission matrix for the four seeded administrative roles (`operations_admin`, `user_admin`, `security_admin`, and `auditor`) for missing or unexpected grants (`RBAC-02`). Findings are recorded as deduplicated security alerts. Invalid new assignments are blocked and audited rather than silently accepted.

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

The RBAC discrepancy scan now runs as a bounded FastAPI lifespan task at a configurable interval (default 300 seconds, minimum 60 seconds), independently of administrator alert-page reads. A PostgreSQL transaction-scoped advisory lock prevents overlapping scans across API instances, and each scan transaction has a 15-second statement timeout. Errors are logged and retried on the next interval. This is periodic in-process monitoring, not an external always-on service: host sleep/restarts can interrupt it, and no durable last-success/last-failure health record is currently persisted.

The audit did not verify delivery of RBAC/IDS alerts through push, email, or another notification channel. Persisting an alert row and displaying it in the Admin Console are not proof that an administrator was notified. Do not claim notification delivery until a supported web-admin delivery channel, recipient resolution, retry state, and end-to-end receipt are tested.

These findings are repository/live-schema observations only. No user-role assignments, production grants, policies, migrations, or deployment settings were changed.


## Scanner scope and policy boundaries (2026-11-27)

- The migration explicitly seeds permission mappings for four administrative roles only. The exact scanner policy mirrors those four mappings; it does not infer empty or wildcard permissions for `requester`, `donor`, or `super_admin`.
- RBAC-02 remains assignment-scoped for per-user applicability/permission attribution. RBAC-03 now independently checks the four approved role policies even when no users hold those roles, using the dedicated role-scoped findings table introduced by migration 021.
- The live read-only snapshot found zero user-role assignments. This no longer prevents RBAC-03 from detecting drift in the four reviewed role-permission mappings.
- CI on head `2c38d2ba58cdf66751719614be7991e26cb41075` passed backend tests (including PostgreSQL integration tests), Ruff, mypy, Docker/admin-site validation, Android quality gate, Gitleaks, and Python plus Java/Kotlin CodeQL. This verifies the tested code on that commit, not deployed runtime behavior.


## Admin route authorization inventory (source inspection, 2026-11-27)

The current `admin_api.py` route inventory confirms that these endpoints use the shared `require_admin` dependency (the server-managed administrator allowlist plus verified active principal), not granular role-permission checks:

| Route | Current authorization gate | Intended permission key |
|---|---|---|
| `GET /v1/admin/me` | `require_admin` | `dashboard.read` (or a dedicated admin-self permission) |
| `GET /v1/admin/dashboard` | `require_admin` | `dashboard.read` |
| `GET /v1/admin/users` | `require_admin` | `users.read` |
| `PATCH /v1/admin/users/{user_id}` | `require_admin` | `users.update` |
| `GET /v1/admin/security/alerts` | `require_admin` | `security.alerts.read` |
| `PATCH /v1/admin/security/alerts/{alert_id}` | `require_admin` | `security.alerts.manage` |
| `GET /v1/admin/sessions` | `require_admin` | `security.sessions.read` |
| `POST /v1/admin/sessions/{session_id}/revoke` | `require_admin` | `security.sessions.revoke` |
| `GET /v1/admin/audit` | `require_admin` | `audit.read` |
| `GET /v1/admin/errors` | `require_admin` | Dedicated error-log permission should be considered; current matrix has no specific key |
| `GET /v1/admin/roles` | `require_admin` | `roles.read` |
| `GET /v1/admin/users/{user_id}/roles` | `require_admin` | `roles.read` |
| `PUT /v1/admin/users/{user_id}/roles` | `require_admin` plus `LIFELINK_RBAC_MANAGER_USER_IDS` and applicability checks | `roles.assign` plus manager safeguard |

This is an authorization source inspection, not an endpoint penetration test. The permission column is a target mapping, not a claim that the permissions are currently enforced. Do not replace the allowlist gate until a reviewed role-to-endpoint matrix, trusted bootstrap/provisioning plan, migration/backfill strategy, and role-specific authorization tests exist. In particular, the current live registry has zero user-role assignments, so switching to RBAC-only checks now could lock out all existing administrators.


## Role-level policy integrity detector (2026-11-27)

Migration `021_role_policy_integrity_findings.sql` adds `lifelink_role_security_findings`, a role-targeted finding store with its own `(rule_id, role_key)` active-finding uniqueness key. It is intentionally separate from `lifelink_security_alerts`; role identifiers are never written into the user-scoped `user_id` field.

The periodic scanner now evaluates the four explicitly approved role policies directly from `lifelink_roles` and their permission mappings, independent of `lifelink_user_roles`. It raises RBAC-03 for missing role definitions, missing grants, or unexpected grants; increments occurrences only when evidence changes; and automatically resolves active RBAC-03 findings when the approved mapping is restored. Roles without an approved exact policy (`requester`, `donor`, and `super_admin`) are skipped rather than treated as having an empty permission set.

The new read-only admin endpoint is `GET /v1/admin/security/role-integrity` (server-side `require_admin` remains in force). The API startup schema routine now creates the role-finding table, indexes, and RLS setting idempotently, matching migration 021. The SQL migration remains available for separately managed schema rollouts. No production migration or deployment was performed as part of this PR.
