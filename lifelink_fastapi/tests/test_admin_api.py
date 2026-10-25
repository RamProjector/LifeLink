from __future__ import annotations

import pytest
from fastapi import HTTPException

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

def test_startup_admin_audit_schema_statements_are_idempotent():
    from pathlib import Path

    db_source = Path(__file__).parents[1].joinpath("app", "db.py").read_text()
    for column in ("outcome", "target_type", "target_id", "correlation_id"):
        assert f"ADD COLUMN IF NOT EXISTS {column}" in db_source
    assert "ix_audit_events_created_at" in db_source
    assert "ix_audit_events_action_created_at" in db_source

