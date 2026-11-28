-- Cover the assigned_by foreign key used by RBAC assignment audit attribution.
-- This is additive and safe to apply more than once.
BEGIN;

CREATE INDEX IF NOT EXISTS ix_lifelink_user_roles_assigned_by
    ON public.lifelink_user_roles (assigned_by);

COMMIT;
