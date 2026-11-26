from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.security import Principal, get_active_postgres_principal


class _Result:
    def __init__(self, value):
        self.value = value

    def scalar_one_or_none(self):
        return self.value


class _Session:
    def __init__(self, result_value="session-1"):
        self.result_value = result_value
        self.statement = ""
        self.params = {}
        self.committed = False
        self.rolled_back = False

    async def execute(self, statement, params):
        self.statement = str(statement)
        self.params = params
        return _Result(self.result_value)

    async def commit(self):
        self.committed = True

    async def rollback(self):
        self.rolled_back = True


def _request():
    return SimpleNamespace(
        headers={"user-agent": "LifeLink test client"},
        client=SimpleNamespace(host="127.0.0.1"),
    )


def test_active_principal_registers_session_before_request():
    session = _Session()
    principal = Principal(
        subject="user-1",
        session_id="session-1",
        session_expires_at=datetime(2026, 12, 1, tzinfo=timezone.utc),
    )

    result = asyncio.run(
        get_active_postgres_principal(_request(), principal, session)
    )

    assert result is principal
    assert "INSERT INTO lifelink_user_sessions" in session.statement
    assert "FROM auth.sessions AS supabase_session" in session.statement
    assert "supabase_session.id::varchar = CAST(:session_id AS varchar)" in session.statement
    assert "supabase_session.user_id::varchar = CAST(:user_id AS varchar)" in session.statement
    assert "ON CONFLICT (session_id) DO UPDATE" in session.statement
    assert "lifelink_user_sessions.revoked_at IS NULL" in session.statement
    assert "lifelink_user_sessions.status <> 'revoked'" in session.statement
    assert session.params["user_id"] == "user-1"
    assert session.params["session_id"] == "session-1"
    assert session.committed is True
    assert session.rolled_back is False


def test_active_principal_rejects_revoked_or_mismatched_session():
    session = _Session(result_value=None)
    principal = Principal(subject="user-1", session_id="revoked-session")

    with pytest.raises(HTTPException) as error:
        asyncio.run(get_active_postgres_principal(_request(), principal, session))

    assert error.value.status_code == 401
    assert session.rolled_back is True
    assert session.committed is False


def test_active_principal_rejects_missing_session_identifier():
    session = _Session()
    principal = Principal(subject="user-1")

    with pytest.raises(HTTPException) as error:
        asyncio.run(get_active_postgres_principal(_request(), principal, session))

    assert error.value.status_code == 401
    assert session.statement == ""
    assert session.committed is False


def test_development_principal_bypasses_registry_only_for_local_mode():
    session = _Session()
    principal = Principal(subject="development-user")

    result = asyncio.run(
        get_active_postgres_principal(_request(), principal, session)
    )

    assert result is principal
    assert session.statement == ""
    assert session.committed is False
