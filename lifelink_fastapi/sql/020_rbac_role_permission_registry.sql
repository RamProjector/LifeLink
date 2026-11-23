-- LifeLink many-to-many role and permission registry.
-- Backend-only tables: all access is mediated by FastAPI using the server DB role.
-- No user-facing signup or profile metadata can grant these roles.
BEGIN;

CREATE TABLE IF NOT EXISTS lifelink_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_key VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    is_system BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS lifelink_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    permission_key VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS lifelink_user_roles (
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES lifelink_roles(id) ON DELETE RESTRICT,
    assigned_by UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT ck_lifelink_user_roles_expiry CHECK (expires_at IS NULL OR expires_at > assigned_at)
);

CREATE TABLE IF NOT EXISTS lifelink_role_permissions (
    role_id UUID NOT NULL REFERENCES lifelink_roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES lifelink_permissions(id) ON DELETE CASCADE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX IF NOT EXISTS ix_lifelink_user_roles_role ON lifelink_user_roles(role_id);
CREATE INDEX IF NOT EXISTS ix_lifelink_user_roles_expiry ON lifelink_user_roles(expires_at);
CREATE INDEX IF NOT EXISTS ix_lifelink_role_permissions_permission ON lifelink_role_permissions(permission_id);

INSERT INTO lifelink_roles (role_key, display_name, description) VALUES
 ('requester', 'Requester', 'Can create and manage own emergency requests'),
 ('donor', 'Donor', 'Can manage own donor participation'),
 ('super_admin', 'Super Administrator', 'Full administrative authority; tightly controlled'),
 ('security_admin', 'Security Administrator', 'Manages security alerts, sessions, and investigations'),
 ('operations_admin', 'Operations Administrator', 'Manages operational request workflows'),
 ('user_admin', 'User Administrator', 'Manages user accounts and capabilities'),
 ('auditor', 'Auditor', 'Read-only access to approved audit and reporting views')
ON CONFLICT (role_key) DO NOTHING;

INSERT INTO lifelink_permissions (permission_key, description) VALUES
 ('dashboard.read', 'View the administrative dashboard'),
 ('users.read', 'View user directory and profiles'),
 ('users.update', 'Update approved user capabilities'),
 ('users.suspend', 'Suspend or restore user access'),
 ('requests.read', 'View emergency request records'),
 ('requests.update', 'Perform approved request lifecycle actions'),
 ('operations.read', 'View operational status and permitted location freshness'),
 ('communications.metadata.read', 'View permitted communication delivery metadata'),
 ('audit.read', 'Read audit events'),
 ('security.alerts.read', 'Read security alerts'),
 ('security.alerts.manage', 'Acknowledge and resolve security alerts'),
 ('security.sessions.read', 'View observed application sessions'),
 ('security.sessions.revoke', 'Revoke application sessions'),
 ('roles.read', 'View role and permission assignments'),
 ('roles.assign', 'Assign approved roles to users'),
 ('permissions.manage', 'Change role-permission mappings'),
 ('reports.read', 'View operational and security reports'),
 ('settings.read', 'View system settings'),
 ('settings.update', 'Update approved system settings')
ON CONFLICT (permission_key) DO NOTHING;

-- Seed least-privilege mappings. Elevated roles are intentionally not assigned
-- to any account by this migration; initial provisioning remains out-of-band.
INSERT INTO lifelink_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM lifelink_roles r
JOIN lifelink_permissions p ON p.permission_key IN ('dashboard.read', 'users.read', 'requests.read', 'operations.read', 'reports.read')
WHERE r.role_key = 'operations_admin'
ON CONFLICT DO NOTHING;

INSERT INTO lifelink_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM lifelink_roles r
JOIN lifelink_permissions p ON p.permission_key IN ('dashboard.read', 'users.read', 'users.update', 'users.suspend', 'roles.read')
WHERE r.role_key = 'user_admin'
ON CONFLICT DO NOTHING;

INSERT INTO lifelink_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM lifelink_roles r
JOIN lifelink_permissions p ON p.permission_key IN ('dashboard.read', 'audit.read', 'security.alerts.read', 'security.alerts.manage', 'security.sessions.read', 'security.sessions.revoke')
WHERE r.role_key = 'security_admin'
ON CONFLICT DO NOTHING;

INSERT INTO lifelink_role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM lifelink_roles r
JOIN lifelink_permissions p ON p.permission_key IN ('dashboard.read', 'audit.read', 'reports.read')
WHERE r.role_key = 'auditor'
ON CONFLICT DO NOTHING;

-- Enable RLS; application server access must use its existing trusted DB role.
ALTER TABLE lifelink_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE lifelink_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE lifelink_user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE lifelink_role_permissions ENABLE ROW LEVEL SECURITY;

COMMIT;
