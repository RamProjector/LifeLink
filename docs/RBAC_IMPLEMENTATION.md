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

Role assignment requires both ordinary administrator authentication and membership in the separate server-side `LIFELINK_RBAC_MANAGER_USER_IDS` allowlist. Do not populate this variable from user-editable profile metadata or public signup. Use verified Supabase Auth UUIDs and keep the list in the backend's private environment configuration.

The current admin allowlist remains the bootstrap gate for the existing console. This is intentionally not yet a complete permission-enforcement migration: existing admin routes have not all been converted to granular permission dependencies, and requester/donor application authorization still uses its existing policy.

## Rollout safety

1. Reconcile the live database migration ledger before applying migration 020.
2. Review RLS and the backend-only table exemption registry before deployment.
3. Apply the migration only through the reviewed deployment process.
4. Configure `LIFELINK_RBAC_MANAGER_USER_IDS` privately before enabling role assignment.
5. Provision at least two trusted RBAC managers out of band; do not self-assign roles.
6. Verify assignment, removal, unknown-role rejection, and audit records in a non-production environment first.

No live database changes are performed by adding this migration to the repository.
