from __future__ import annotations

import asyncio
from datetime import datetime, timezone

import httpx

from app.db import get_db_session
from app.main_postgres import app
from app.security import Principal, get_postgres_principal


class _Rows:
    def mappings(self):
        return self

    def all(self):
        return [{
            "log_id": "log-1",
            "action": "request.create",
            "actions": "ADD",
            "description": "request.create",
            "session_id": "session-1",
            "ip_address": "127.0.0.1",
            "timestamp": datetime(2026, 11, 23, tzinfo=timezone.utc),
            "device_used": "test-agent",
            "metadata": {"email": "private@example.test", "safe_count": 1},
            "outcome": "success",
        }]


class _Session:
    def __init__(self):
        self.statements = []
        self.params = []

    async def execute(self, statement, params=None):
        self.statements.append(str(statement))
        self.params.append(params or {})
        return _Rows()

    async def commit(self):
        return None


def test_personal_audit_api_returns_only_callers_events_and_redacts_metadata():
    session = _Session()

    async def fake_session():
        yield session

    app.dependency_overrides[get_db_session] = fake_session
    app.dependency_overrides[get_postgres_principal] = lambda: Principal(
        subject="user-123",
        email_verified=True,
        session_id="session-1",
    )

    async def make_request():
        transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get("/v1/audit/me?limit=10")

    try:
        response = asyncio.run(make_request())
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["items"][0]["log_id"] == "log-1"
        assert body["items"][0]["metadata"] == {
            "email": "[REDACTED]",
            "safe_count": 1,
        }
        query = session.statements[0]
        assert "WHERE actor_id = :actor_id" in query
        assert session.params[0]["actor_id"] == "user-123"
    finally:
        app.dependency_overrides.clear()


def test_personal_audit_api_rejects_development_identity():
    app.dependency_overrides[get_postgres_principal] = lambda: Principal(
        subject="development-user"
    )

    async def make_request():
        transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get("/v1/audit/me")

    try:
        response = asyncio.run(make_request())
        assert response.status_code == 401
    finally:
        app.dependency_overrides.clear()
