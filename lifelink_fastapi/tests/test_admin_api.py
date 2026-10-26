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
    generated = _correlation_id("bad\nforged")
    assert generated != "bad\nforged"
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

    statements = "\n".join(connection.statements)
    for column in ("outcome", "target_type", "target_id", "correlation_id"):
        assert f"ADD COLUMN IF NOT EXISTS {column}" in statements
    assert "ix_audit_events_created_at" in statements
    assert "ix_audit_events_action_created_at" in statements


from app.admin_api import _safe_audit_metadata


def test_admin_audit_metadata_redacts_sensitive_fields_recursively():
    metadata = {
        "operation": "admin.user.update",
        "nested": {"email": "private@example.test", "authorization": "Bearer secret", "count": 2},
        "location": {"latitude": 14.0, "longitude": 121.0},
    }

    safe = _safe_audit_metadata(metadata)

    assert safe["operation"] == "admin.user.update"
    assert safe["nested"]["email"] == "[REDACTED]"
    assert safe["nested"]["authorization"] == "[REDACTED]"
    assert safe["nested"]["count"] == 2
    assert safe["location"] == "[REDACTED]"


def test_admin_audit_metadata_is_bounded():
    safe = _safe_audit_metadata({"items": list(range(60)), "details": "x" * 1200})

    assert len(safe["items"]) == 51
    assert safe["items"][-1] == "[TRUNCATED]"
    assert len(safe["details"]) == 1000

@pytest.mark.parametrize(
    ("method", "path", "payload"),
    [
        ("GET", "/v1/admin/me", None),
        ("GET", "/v1/admin/dashboard", None),
        ("GET", "/v1/admin/users", None),
        ("GET", "/v1/admin/audit", None),
        ("GET", "/v1/admin/errors", None),
        ("PATCH", "/v1/admin/users/another-user", {
            "role": "donor",
            "can_request": True,
            "can_donate": True,
        }),
    ],
)
def test_non_admin_is_denied_by_every_admin_endpoint(monkeypatch, method, path, payload):
    """Exercise the HTTP boundary, not just the authorization helper."""
    import httpx

    from app.db import get_db_session
    from app.main_postgres import app
    from app.security import get_postgres_principal

    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")

    async def fake_session():
        yield object()

    app.dependency_overrides[get_db_session] = fake_session
    app.dependency_overrides[get_postgres_principal] = lambda: Principal(
        subject="regular-user", email="regular@example.test", email_verified=True
    )

    async def make_request():
        transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            if method == "PATCH":
                return await client.patch(path, json=payload)
            return await client.get(path)

    try:
        response = asyncio.run(make_request())
        assert response.status_code == 403, f"{method} {path}: {response.text}"
    finally:
        app.dependency_overrides.clear()

