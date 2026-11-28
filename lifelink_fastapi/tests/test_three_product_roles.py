from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_three_product_role_migration_is_guarded_and_idempotent() -> None:
    migration = (ROOT / "sql" / "024_three_product_roles.sql").read_text(encoding="utf-8")
    assert "BEGIN;" in migration and "COMMIT;" in migration
    assert "WHERE r.role_key NOT IN ('requester', 'donor', 'admin')" in migration
    assert "IF legacy_assignments > 0 THEN" in migration
    assert "RAISE EXCEPTION" in migration
    assert "('requester', 'Requester'" in migration
    assert "('donor', 'Blood Donor'" in migration
    assert "('admin', 'Admin'" in migration
    assert "WHERE r.role_key = 'admin'" in migration
    assert "'permissions.manage'" not in migration
    assert "'settings.update'" not in migration
    assert "DELETE FROM public.lifelink_roles" in migration
    assert "ON CONFLICT (role_key) DO UPDATE" in migration


def test_three_product_role_migration_preserves_only_approved_role_definitions() -> None:
    migration = (ROOT / "sql" / "024_three_product_roles.sql").read_text(encoding="utf-8")
    assert "DELETE FROM public.lifelink_roles" in migration
    assert "WHERE role_key NOT IN ('requester', 'donor', 'admin')" in migration
    assert "lifelink_user_roles" in migration
    assert "legacy role assignments require reviewed mapping" in migration
