# LifeLink RBAC implementation

## Product roles

LifeLink has exactly three product roles:

- **requester** — creates and manages the authenticated user's own blood requests.
- **donor** — the Blood Donor role; manages the authenticated user's donor profile and availability and responds to eligible requests.
- **admin** — accesses the web-only Admin Console through the server-managed administrator allowlist, verified identity, active session, AAL2, and required verified TOTP factors.

The Android application supports requester and donor. Admin operations remain web-only. Do not introduce admin subroles such as security_admin, operations_admin, user_admin, auditor, or super_admin. Those labels are not product roles.

## Permission model

Administrative functional areas are represented as permissions attached to the single admin role. The approved permission set is:

- Dashboard: dashboard.read
- User administration: users.read, users.update, users.suspend
- Operational visibility: requests.read, operations.read
- Audit and security: audit.read, security.alerts.read, security.alerts.manage, security.sessions.read, security.sessions.revoke
- Role visibility and reporting: roles.read, reports.read

The admin role does not receive permissions.manage or settings.update by default. Any future grant requires a separate reviewed policy change. Requester and donor have no administrative permission grants; their application authorization remains governed by capabilities, resource ownership, contact consent, and conversation-participant checks.


### User-directory disclosure boundary

The approved route matrix intentionally separates a **directory summary** from **role administration**:

- `GET /v1/admin/users` is protected by `users.read` and may return each account's effective, non-expired product-role keys plus the mapped administrative-permission summary. This is part of the user-directory read contract used to review account posture; it does not expose assignment attribution, expiry metadata, the role registry, or grant-management controls.
- `GET /v1/admin/roles` and `GET /v1/admin/users/{user_id}/roles` remain protected by `roles.read`. They expose the role registry or individual assignment details, including assignment timestamps and expiry.
- `PUT /v1/admin/users/{user_id}/roles` remains separately protected by the RBAC-manager allowlist and the server-side target-account rules.

Therefore `users.read` explicitly includes the limited summary disclosure shown in the directory, while `roles.read` governs registry and assignment-detail disclosure. This same boundary is implemented in the API and covered by the user-inventory response test. Do not treat the directory summary as permission to read or modify assignment records.

## Registry migration

Migration 020 originally seeded multiple administrator subroles. Migration 024 normalizes the registry to exactly requester, donor, and admin. It:

- Ensures the three canonical role definitions exist.
- Reconciles the admin permission set to the reviewed union of administrative capabilities.
- Refuses to remove any legacy or unapproved role definition while it still has an assignment.
- Removes obsolete administrator subroles and unapproved unused role definitions after the guard passes.

Migration 024 is forward-only. Do not apply it to production until the live assignment count has been rechecked read-only and the migration has been reviewed. If any legacy role assignments exist, the migration intentionally raises an exception rather than guessing how to merge their expiry and attribution semantics. Prepare a separately reviewed mapping before retrying.

## Assignment API

- GET /v1/admin/roles lists the three role definitions and mapped permissions.
- GET /v1/admin/users/{user_id}/roles lists current role assignments.
- PUT /v1/admin/users/{user_id}/roles replaces the user's active role assignments.
- The assignment payload accepts at most three unique role keys.
- Requester requires the account's can_request capability.
- Donor requires can_donate or an existing donor profile/legacy donor record.
- Admin requires the target account to be in the server-managed LIFELINK_ADMIN_USER_IDS allowlist.
- Role assignment remains separately protected by LIFELINK_RBAC_MANAGER_USER_IDS; a manager cannot modify their own assignments.

Never derive admin access from user-editable profile metadata, public signup fields, Android local storage, or a caller-supplied role value. Role assignments are not self-service.

## Route enforcement and rollout

Operational Admin API routes retain the existing administrator identity, verified email, active-session, AAL2, and two-verified-TOTP checks. LIFELINK_RBAC_ENFORCEMENT_MODE defaults to observe while the migration and assignments are reviewed. In enforce mode, each route requires an active, non-expired assignment to the single admin role with the exact route permission.

Keep observe mode until migration 024 has been applied and verified in non-production, administrator assignments have been provisioned through the protected manager process, and the route matrix passes acceptance tests. Do not enable enforcement or change production assignments as part of this PR.

## Scanner policy

RBAC-01 checks whether an active role assignment is applicable to the target account and, for admin, whether the account remains in the server-managed admin allowlist.

RBAC-02 checks each active assignment against its role applicability and permission policy.

RBAC-03 checks the exact three-role registry, including missing requester/donor/admin definitions, unexpected administrative grants on requester/donor, missing or unexpected grants on admin, and any unapproved extra role definitions. Findings remain deduplicated and are resolved only when the actual registry returns to the approved state.

## Personal activity history

- GET /v1/audit/me derives the actor exclusively from the verified Supabase token subject.
- Sensitive metadata is redacted using the shared bounded audit-metadata sanitizer.
- Reading personal history is itself audited.
- API activity middleware is an audit fallback, not an atomic database trigger. Critical mutations should retain transaction-bound audit writes.

## Rollout safety

1. Reconcile the live migration ledger and inspect role definitions/assignments read-only.
2. Confirm there are no legacy role assignments before applying migration 024; otherwise stop and prepare a reviewed assignment-mapping migration.
3. Review RLS and backend-only table exemptions before deployment.
4. Configure LIFELINK_RBAC_MANAGER_USER_IDS privately and verify at least two trusted managers.
5. Keep LIFELINK_RBAC_ENFORCEMENT_MODE=observe until non-production migration, assignment, and route acceptance are complete.
6. Verify assignment, removal, unknown-role rejection, privilege boundaries, and audit records in non-production.

No production migration, grant, role assignment, Auth configuration change, or deployment is authorized by repository changes alone.
