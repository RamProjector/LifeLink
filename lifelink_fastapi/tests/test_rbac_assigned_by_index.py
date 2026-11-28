from __future__ import annotations

import asyncio
from pathlib import Path

from app import db

ROOT = Path(__file__).resolve().parents[1]


def test_rbac_assigned_by_foreign_key_has_idempotent_migration() -> None:
    migration = (ROOT / "sql" / "023_rbac_assigned_by_index.sql").read_text(encoding="utf-8")
    assert "CREATE INDEX IF NOT EXISTS ix_lifelink_user_roles_assigned_by" in migration
    assert "ON public.lifelink_user_roles (assigned_by)" in migration


class FakeConnection:
    def __init__(self, relation_exists: bool):
        self.relation_exists = relation_exists
        self.statements: list[str] = []

    async def scalar(self, statement):
        self.statements.append(str(statement))
        return self.relation_exists

    async def execute(self, statement):
        self.statements.append(str(statement))


def test_rbac_assigned_by_index_is_skipped_when_registry_table_is_absent() -> None:
    connection = FakeConnection(relation_exists=False)
    asyncio.run(db._ensure_rbac_assigned_by_index(connection))
    assert any("to_regclass('public.lifelink_user_roles')" in sql for sql in connection.statements)
    assert not any("CREATE INDEX" in sql for sql in connection.statements)


def test_rbac_assigned_by_index_is_created_when_registry_table_exists() -> None:
    connection = FakeConnection(relation_exists=True)
    asyncio.run(db._ensure_rbac_assigned_by_index(connection))
    assert any("CREATE INDEX IF NOT EXISTS ix_lifelink_user_roles_assigned_by" in sql for sql in connection.statements)
    assert any("ON public.lifelink_user_roles (assigned_by)" in sql for sql in connection.statements)
