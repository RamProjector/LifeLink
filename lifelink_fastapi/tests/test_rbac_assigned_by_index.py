from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_rbac_assigned_by_foreign_key_has_idempotent_migration() -> None:
    migration = (ROOT / "sql" / "023_rbac_assigned_by_index.sql").read_text(encoding="utf-8")
    assert "CREATE INDEX IF NOT EXISTS ix_lifelink_user_roles_assigned_by" in migration
    assert "ON public.lifelink_user_roles (assigned_by)" in migration


def test_rbac_assigned_by_index_is_guarded_in_startup_schema_bootstrap() -> None:
    db_source = (ROOT / "app" / "db.py").read_text(encoding="utf-8")
    assert "to_regclass('public.lifelink_user_roles')" in db_source
    assert "CREATE INDEX IF NOT EXISTS ix_lifelink_user_roles_assigned_by" in db_source
    assert "ON public.lifelink_user_roles (assigned_by)" in db_source
