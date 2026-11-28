-- Normalize the product RBAC registry to exactly three roles:
-- requester, donor, and admin. Administrative work areas are permissions,
-- not separately assignable administrator roles.
-- This migration intentionally refuses to discard legacy assignments.
BEGIN;

DO $guard$
DECLARE
    legacy_assignments BIGINT;
BEGIN
    SELECT count(*) INTO legacy_assignments
    FROM public.lifelink_user_roles ur
    JOIN public.lifelink_roles r ON r.id = ur.role_id
    WHERE r.role_key NOT IN ('requester', 'donor', 'admin');

    IF legacy_assignments > 0 THEN
        RAISE EXCEPTION
            'LifeLink three-role migration blocked: % legacy role assignments require reviewed mapping',
            legacy_assignments;
    END IF;
END;
$guard$;

INSERT INTO public.lifelink_roles (role_key, display_name, description, is_system)
VALUES
    ('requester', 'Requester', 'Can create and manage own blood requests', TRUE),
    ('donor', 'Blood Donor', 'Can manage own donor participation and availability', TRUE),
    ('admin', 'Admin', 'Can access approved administrative capabilities', TRUE)
ON CONFLICT (role_key) DO UPDATE
SET display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    is_system = TRUE;

-- Normalize the single Admin permission set. Keep the reviewed operational,
-- user-management, security, audit, and reporting capabilities; do not grant
-- permissions.manage or settings.update by implication.
DELETE FROM public.lifelink_role_permissions rp
USING public.lifelink_roles r
WHERE rp.role_id = r.id
  AND r.role_key = 'admin'
  AND rp.permission_id IN (
      SELECT p.id FROM public.lifelink_permissions p
      WHERE p.permission_key NOT IN (
          'dashboard.read', 'users.read', 'users.update', 'users.suspend',
          'requests.read', 'operations.read', 'communications.metadata.read',
          'audit.read', 'security.alerts.read', 'security.alerts.manage',
          'security.sessions.read', 'security.sessions.revoke', 'roles.read',
          'reports.read'
      )
  );

INSERT INTO public.lifelink_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.lifelink_roles r
JOIN public.lifelink_permissions p
  ON p.permission_key IN (
      'dashboard.read', 'users.read', 'users.update', 'users.suspend',
      'requests.read', 'operations.read', 'communications.metadata.read',
      'audit.read', 'security.alerts.read', 'security.alerts.manage',
      'security.sessions.read', 'security.sessions.revoke', 'roles.read',
      'reports.read'
  )
WHERE r.role_key = 'admin'
ON CONFLICT DO NOTHING;

-- Remove obsolete administrator subroles and any unapproved custom role
-- definitions only after the guard proves that none has an assignment.
DELETE FROM public.lifelink_roles
WHERE role_key NOT IN ('requester', 'donor', 'admin');

COMMIT;
