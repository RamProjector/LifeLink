from __future__ import annotations

import asyncio

import pytest
from fastapi import HTTPException

from app import db
from app.admin_api import _configured_admin_ids, _correlation_id, require_admin
from app.security import Principal


def test_admin_allowlist_is_server_configured_not_user_claim(monkeypatch):
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1, admin-2")
    assert _configured_admin_ids() == {"admin-1", "admin-2"}
    with pytest.raises(HTTPException) as error:
        require_admin(Principal(subject="regular-user", role="admin", email_verified=True))
    assert error.value.status_code == 403


def test_admin_requires_real_authenticated_subject(monkeypatch):
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "development-user")
    with pytest.raises(HTTPException) as error:
        require_admin(Principal(subject="development-user", email_verified=True))
    assert error.value.status_code == 401


def test_admin_allowlist_grants_only_configured_subject(monkeypatch):
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")
    assert require_admin(Principal(subject="admin-1", email_verified=True)).subject == "admin-1"


def test_correlation_id_is_bounded_and_sanitized():
    assert _correlation_id("web:request-1") == "web:request-1"
    generated = _correlation_id("bad\\nforged")
    assert generated != "bad\\nforged"
    assert len(generated) <= 36


class _FakeConnection:
    def __init__(self):
        self.statements: list[str] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        return False

    async def run_sync(self, callback):
        # Schema creation itself is covered by database integration tests.
        # This unit test focuses on additive SQL issued during startup.
        return None

    async def execute(self, statement, *args, **kwargs):
        self.statements.append(str(statement))


class _FakeEngine:
    def __init__(self, connection):
        self.connection = connection

    def begin(self):
        return self.connection


def test_startup_adds_admin_audit_columns_and_indexes(monkeypatch):
    connection = _FakeConnection()
    monkeypatch.setattr(db, "engine", _FakeEngine(connection))

    asyncio.run(db.create_all_tables())

    statements = "\\n".join(connection.statements)
    for column in ("outcome", "target_type", "target_id", "correlation_id"):
        assert f"ADD COLUMN IF NOT EXISTS {column}" in statements
    assert "ix_audit_events_created_at" in statements
    assert "ix_audit_events_action_created_at" in statements
