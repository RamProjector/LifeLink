from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app import db
from app.admin_api import (
    AdminMfaAuditEvent,
    _configured_admin_ids,
    _correlation_id,
    admin_identity_preflight,
    require_admin,
    require_admin_identity,
)
from app.security import Principal


class FakeSession:
    def __init__(self, scalar_values=None):
        self.scalar_values = list(scalar_values or [0])
        self.statements = []
        self.scalar_statements = []
        self.scalar_parameters = []

    async def execute(self, statement, params=None):
        self.statements.append(str(statement))
        return None

    async def scalar(self, statement, params=None):
        self.scalar_statements.append(str(statement))
        self.scalar_parameters.append(params or {})
        return self.scalar_values.pop(0) if self.scalar_values else 0

    async def commit(self):
        return None

    async def rollback(self):
        return None


def _request_context():
    return SimpleNamespace(
        url=SimpleNamespace(path="/v1/admin/test"),
        method="GET",
        client=None,
        headers={},
    )



def test_admin_identity_preflight_returns_no_operational_data_and_audits():
    async def check():
        session = FakeSession()
        result = await admin_identity_preflight(
            request=_request_context(),
            session=session,
            principal=Principal(subject="admin-1", email_verified=True, session_id="session-1"),
            x_correlation_id="mfa-enrollment-check",
        )
        assert result == {"authorized": True}
        assert len(session.statements) == 2
        assert all("INSERT INTO" in statement for statement in session.statements)

    asyncio.run(check())


def test_admin_allowlist_is_server_configured_not_user_claim(monkeypatch):
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1, admin-2")
    assert _configured_admin_ids() == {"admin-1", "admin-2"}

    async def check():
        with pytest.raises(HTTPException) as error:
            await require_admin(
                principal=Principal(subject="regular-user", role="admin", email_verified=True, assurance_level="aal2"),
                session=FakeSession(),
                request=_request_context(),
            )
        assert error.value.status_code == 403

    asyncio.run(check())


def test_admin_requires_real_authenticated_subject(monkeypatch):
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "development-user")

    async def check():
        with pytest.raises(HTTPException) as error:
            await require_admin(
                principal=Principal(subject="development-user", email_verified=True),
                session=FakeSession(),
                request=_request_context(),
            )
        assert error.value.status_code == 401

    asyncio.run(check())


def test_admin_allowlist_grants_only_configured_subject(monkeypatch):
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")

    async def check():
        principal = await require_admin(
            principal=Principal(subject="admin-1", email_verified=True, session_id="session-1", assurance_level="aal2"),
            session=FakeSession([0, 2]),
            request=_request_context(),
        )
        assert principal.subject == "admin-1"

    asyncio.run(check())


def test_admin_rejects_revoked_session(monkeypatch):
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")

    async def check():
        with pytest.raises(HTTPException) as error:
            await require_admin(
                principal=Principal(subject="admin-1", email_verified=True, session_id="session-revoked", assurance_level="aal2"),
                session=FakeSession([1, 0]),
                request=_request_context(),
            )
        assert error.value.status_code == 401

    asyncio.run(check())


def test_admin_requires_session_context(monkeypatch):
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")

    async def check():
        with pytest.raises(HTTPException) as error:
            await require_admin(
                principal=Principal(subject="admin-1", email_verified=True),
                session=FakeSession(),
                request=_request_context(),
            )
        assert error.value.status_code == 401
        assert "Session context" in error.value.detail

    asyncio.run(check())


@pytest.mark.parametrize("assurance_level", ["aal1", "unknown", "aal3"])
def test_admin_rejects_sessions_without_verified_aal2_and_audits(monkeypatch, assurance_level):
    from app import admin_api

    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")
    recorded = {}

    async def capture_denial(session, principal, request, **kwargs):
        recorded.update(kwargs)

    monkeypatch.setattr(admin_api, "_record_denied_admin_attempt", capture_denial)

    async def check():
        with pytest.raises(HTTPException) as error:
            await require_admin(
                principal=Principal(
                    subject="admin-1",
                    email_verified=True,
                    session_id="session-1",
                    assurance_level=assurance_level,
                ),
                session=FakeSession([0]),
                request=_request_context(),
            )
        assert error.value.status_code == 403
        assert "multi-factor" in error.value.detail.lower()
        assert recorded["rule_id"] == "IDS-06"
        assert recorded["action"] == "security.admin_mfa.assurance_denied"
        assert recorded["severity"] == "high"

    asyncio.run(check())


def test_admin_allows_aal2_after_identity_and_revocation_checks(monkeypatch):
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")

    async def check():
        principal = await require_admin(
            principal=Principal(
                subject="admin-1",
                email_verified=True,
                session_id="session-1",
                assurance_level="aal2",
            ),
            session=FakeSession([0, 2]),
            request=_request_context(),
        )
        assert principal.assurance_level == "aal2"

    asyncio.run(check())


def test_admin_rejects_aal2_with_only_one_verified_totp_factor(monkeypatch):
    from app import admin_api

    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")
    recorded = {}

    async def capture_denial(session, principal, request, **kwargs):
        recorded.update(kwargs)

    monkeypatch.setattr(admin_api, "_record_denied_admin_attempt", capture_denial)

    async def check():
        session = FakeSession([0, 1])
        with pytest.raises(HTTPException) as error:
            await require_admin(
                principal=Principal(
                    subject="admin-1",
                    email_verified=True,
                    session_id="session-1",
                    assurance_level="aal2",
                ),
                session=session,
                request=_request_context(),
            )
        assert error.value.status_code == 403
        assert "two verified totp" in error.value.detail.lower()
        assert recorded["rule_id"] == "IDS-07"
        assert recorded["action"] == "security.admin_mfa.factor_count_denied"
        factor_query = session.scalar_statements[-1]
        assert "FROM auth.mfa_factors" in factor_query
        assert "user_id::varchar = CAST(:user_id AS varchar)" in factor_query
        assert "factor_type = 'totp'" in factor_query
        assert "status = 'verified'" in factor_query

    asyncio.run(check())


def test_mfa_audit_dependency_allows_first_factor_receipt_after_aal2(monkeypatch):
    from app.admin_api import require_admin_mfa_event

    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")

    async def check():
        principal = await require_admin_mfa_event(
            principal=Principal(
                subject="admin-1",
                email_verified=True,
                session_id="session-1",
                assurance_level="aal2",
            ),
            session=FakeSession([0]),
            request=_request_context(),
        )
        assert principal.subject == "admin-1"

    asyncio.run(check())


def test_identity_preflight_allows_allowlisted_aal1_without_operational_access(monkeypatch):
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")

    async def check():
        principal = await require_admin_identity(
            principal=Principal(
                subject="admin-1",
                email_verified=True,
                session_id="session-1",
                assurance_level="aal1",
            ),
            session=FakeSession([0]),
            request=_request_context(),
        )
        assert principal.subject == "admin-1"
        assert principal.assurance_level == "aal1"

    asyncio.run(check())


def test_every_admin_route_uses_aal2_guard_except_identity_preflight():
    from app.admin_api import router

    protected = []
    for route in router.routes:
        if not hasattr(route, "dependant"):
            continue
        dependency_names = {dependency.call.__name__ for dependency in route.dependant.dependencies}
        if route.path == "/v1/admin/preflight":
            assert "require_admin_identity" in dependency_names
            assert "require_admin" not in dependency_names
        elif route.path == "/v1/admin/mfa/events":
            assert "require_admin_mfa_event" in dependency_names
            assert "require_admin" not in dependency_names
            protected.append(route.path)
        else:
            assert "require_admin" in dependency_names, route.path
            protected.append(route.path)
    assert protected


def test_mfa_lifecycle_audit_records_only_bounded_non_secret_metadata(monkeypatch):
    from app import admin_api

    recorded = {}

    async def capture_audit(session, actor_id, action, **kwargs):
        recorded.update(actor_id=actor_id, action=action, **kwargs)

    monkeypatch.setattr(admin_api, "_write_audit", capture_audit)

    async def check():
        session = FakeSession([2])
        result = await admin_api.admin_mfa_event(
            payload=admin_api.AdminMfaAuditEvent(
                event_type="recovery_reenrollment_verified",
                verified_factor_count=2,
                ticket_reference="SEC-2026-104",
            ),
            request=_request_context(),
            session=session,
            principal=Principal(
                subject="admin-1",
                email_verified=True,
                session_id="session-1",
                assurance_level="aal2",
            ),
            x_correlation_id="web:mfa-recovery",
        )
        assert result == {"recorded": True}
        assert recorded["action"] == "admin.mfa.recovery_reenrollment_verified"
        assert recorded["target_type"] == "admin_mfa"
        assert recorded["metadata"] == {
            "event_type": "recovery_reenrollment_verified",
            "verified_factor_count": 2,
            "client_reported_factor_count": 2,
            "evidence_source": "server_confirmed_supabase_auth_factor_inventory",
            "ticket_reference": "SEC-2026-104",
        }
        assert "code" not in recorded["metadata"]
        assert "secret" not in recorded["metadata"]
        assert "token" not in recorded["metadata"]
        assert session.statements == []
        assert "FROM auth.mfa_factors" in session.scalar_statements[0]
        assert "user_id::varchar = CAST(:user_id AS varchar)" in session.scalar_statements[0]

    asyncio.run(check())


@pytest.mark.parametrize(
    ("event_type", "client_count", "server_count", "ticket", "expected_status", "expected_detail"),
    [
        ("factor_enrollment_verified", 1, 0, None, 409, "Supabase's verified authenticator inventory"),
        ("backup_factor_verified", 2, 1, None, 409, "Supabase's verified authenticator inventory"),
        ("factor_replacement_verified", 2, 1, None, 409, "Supabase's verified authenticator inventory"),
        ("factor_removal_requested", 3, 2, None, 409, "Supabase's verified authenticator inventory"),
        ("recovery_reenrollment_verified", 2, 0, "SEC-2026-104", 409, "Supabase's verified authenticator inventory"),
        ("recovery_reenrollment_verified", 2, 2, None, 422, "ticket reference"),
    ],
)
def test_mfa_lifecycle_audit_rejects_unverified_inventory_or_missing_ticket(
    event_type, client_count, server_count, ticket, expected_status, expected_detail
):
    from app import admin_api

    async def check():
        with pytest.raises(HTTPException) as error:
            await admin_api.admin_mfa_event(
                payload=admin_api.AdminMfaAuditEvent(
                    event_type=event_type,
                    verified_factor_count=client_count,
                    ticket_reference=ticket,
                ),
                request=_request_context(),
                session=FakeSession([server_count]),
                principal=Principal(
                    subject="admin-1",
                    email_verified=True,
                    session_id="session-1",
                    assurance_level="aal2",
                ),
            )
        assert error.value.status_code == expected_status
        assert expected_detail.lower() in error.value.detail.lower()

    asyncio.run(check())


def test_mfa_lifecycle_audit_ignores_forged_client_factor_count():
    from app import admin_api

    async def check():
        session = FakeSession([1])
        with pytest.raises(HTTPException) as error:
            await admin_api.admin_mfa_event(
                payload=admin_api.AdminMfaAuditEvent(
                    event_type="backup_factor_verified",
                    verified_factor_count=10,
                ),
                request=_request_context(),
                session=session,
                principal=Principal(
                    subject="admin-1",
                    email_verified=True,
                    session_id="session-1",
                    assurance_level="aal2",
                ),
            )
        assert error.value.status_code == 409
        assert "Supabase's verified authenticator inventory" in error.value.detail
        assert "FROM auth.mfa_factors" in session.scalar_statements[0]

    asyncio.run(check())

def test_mfa_recovery_ticket_reference_is_strictly_bounded():
    with pytest.raises(ValidationError):
        AdminMfaAuditEvent(
            event_type="recovery_reenrollment_verified",
            verified_factor_count=2,
            ticket_reference="ticket with spaces and unbounded context",
        )


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

    async def scalar(self, statement, *args, **kwargs):
        self.statements.append(str(statement))
        # This unit fixture models an already-migrated schema. Dedicated
        # PostgreSQL tests cover the first-start backfill path.
        return True


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
    for column in (
        "outcome", "target_type", "target_id", "correlation_id", "log_id",
        "actions", "description", "session_id", "ip_address", "timestamp", "device_used",
    ):
        assert f"ADD COLUMN IF NOT EXISTS {column}" in statements
    assert "ix_audit_events_created_at" in statements
    assert "ix_audit_events_action_created_at" in statements
    assert "ux_audit_events_log_id" in statements
    assert "CREATE TABLE IF NOT EXISTS lifelink_user_sessions" in statements
    assert "ix_lifelink_user_sessions_user_seen" in statements
    assert "CREATE TABLE IF NOT EXISTS lifelink_security_alerts" in statements
    assert "ux_lifelink_security_alerts_open_rule_user" in statements
    assert "CREATE TABLE IF NOT EXISTS lifelink_role_security_findings" in statements
    assert "ux_lifelink_role_security_findings_open_rule_role" in statements
    assert "ALTER TABLE lifelink_role_security_findings ENABLE ROW LEVEL SECURITY" in statements
    assert "CREATE TABLE IF NOT EXISTS lifelink_security_scanner_runs" in statements
    assert "ALTER TABLE lifelink_security_scanner_runs ENABLE ROW LEVEL SECURITY" in statements
    assert "REVOKE ALL ON TABLE lifelink_security_scanner_runs FROM PUBLIC" in statements
    assert "REVOKE ALL ON TABLE lifelink_security_scanner_runs FROM anon" in statements
    assert "REVOKE ALL ON TABLE lifelink_security_scanner_runs FROM authenticated" in statements


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
        ("GET", "/v1/admin/sessions", None),
        ("GET", "/v1/admin/security/alerts", None),
        ("GET", "/v1/admin/security/role-integrity", None),
        ("GET", "/v1/admin/security/scanner", None),
        ("GET", "/v1/admin/security/notifications", None),
        ("PATCH", "/v1/admin/security/alerts/00000000-0000-0000-0000-000000000001", {"status": "resolved"}),
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
    from app.security import get_active_postgres_principal, get_postgres_principal

    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")

    async def fake_session():
        yield FakeSession()

    app.dependency_overrides[get_db_session] = fake_session
    app.dependency_overrides[get_postgres_principal] = lambda: Principal(
        subject="regular-user", email="regular@example.test", email_verified=True
    )
    app.dependency_overrides[get_active_postgres_principal] = lambda: Principal(
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




@pytest.mark.parametrize(
    ("action", "expected"),
    [
        ("user.create", "ADD"),
        ("admin.user.update", "EDIT"),
        ("account.delete", "DELETE"),
        ("admin.dashboard.view", "OTHER"),
        ("admin.audit.read", "OTHER"),
    ],
)
def test_audit_action_kind_normalizes_crud_actions(action, expected):
    from app.admin_api import _audit_action_kind

    assert _audit_action_kind(action) == expected




def test_repeated_denied_admin_access_creates_deduplicated_alert():
    from app.admin_api import _record_denied_admin_attempt

    session = FakeSession([5])
    request = _request_context()

    async def check():
        await _record_denied_admin_attempt(
            session,
            Principal(subject="regular-user", email_verified=True),
            request,
            rule_id="IDS-03",
            action="security.admin_access.denied",
            severity="high",
            threshold=5,
            summary="Repeated unauthorized admin access",
        )

    asyncio.run(check())
    statements = "\n".join(session.statements)
    assert "INSERT INTO audit_events" in statements
    assert "INSERT INTO lifelink_security_alerts" in statements


def test_rbac_manager_uses_separate_server_allowlist(monkeypatch):
    from app.admin_api import _configured_rbac_manager_ids, _require_rbac_manager

    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "ordinary-admin")
    monkeypatch.setenv("LIFELINK_RBAC_MANAGER_USER_IDS", "security-owner, security-backup")
    assert _configured_rbac_manager_ids() == {"security-owner", "security-backup"}

    with pytest.raises(HTTPException) as error:
        _require_rbac_manager(Principal(subject="ordinary-admin"))
    assert error.value.status_code == 403

    _require_rbac_manager(Principal(subject="security-owner"))


def test_rbac_manager_allowlist_defaults_to_empty(monkeypatch):
    from app.admin_api import _configured_rbac_manager_ids

    monkeypatch.delenv("LIFELINK_RBAC_MANAGER_USER_IDS", raising=False)
    assert _configured_rbac_manager_ids() == set()



def test_rbac_role_update_allows_explicit_revoke_all():
    from app.admin_api import AdminUserRolesUpdate

    payload = AdminUserRolesUpdate(role_keys=[])
    assert payload.role_keys == []


def test_rbac_role_update_limits_assignment_count():
    from pydantic import ValidationError

    from app.admin_api import AdminUserRolesUpdate

    with pytest.raises(ValidationError):
        AdminUserRolesUpdate(role_keys=[f"role-{index}" for index in range(9)])



def test_ordinary_admin_cannot_assign_roles_through_http(monkeypatch):
    import httpx

    from app.db import get_db_session
    from app.main_postgres import app
    from app.security import get_postgres_principal

    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "ordinary-admin")
    monkeypatch.delenv("LIFELINK_RBAC_MANAGER_USER_IDS", raising=False)

    async def fake_session():
        yield FakeSession([0, 2])

    app.dependency_overrides[get_db_session] = fake_session
    app.dependency_overrides[get_postgres_principal] = lambda: Principal(
        subject="ordinary-admin",
        email_verified=True,
        session_id="ordinary-admin-session",
        assurance_level="aal2",
    )
    from app.security import get_active_postgres_principal
    app.dependency_overrides[get_active_postgres_principal] = lambda: Principal(
        subject="ordinary-admin",
        email_verified=True,
        session_id="ordinary-admin-session",
        assurance_level="aal2",
    )

    async def make_request():
        transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.put(
                "/v1/admin/users/00000000-0000-0000-0000-000000000001/roles",
                json={"role_keys": ["auditor"]},
            )

    try:
        response = asyncio.run(make_request())
        assert response.status_code == 403
        assert "RBAC manager" in response.json()["detail"]
    finally:
        app.dependency_overrides.clear()


def test_rbac_policy_rejects_roles_not_applicable_to_user(monkeypatch):
    from app.admin_api import _role_mismatch_reason
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "approved-admin")
    monkeypatch.setenv("LIFELINK_RBAC_MANAGER_USER_IDS", "security-owner")
    profile = {"can_request": True, "can_donate": False, "has_donor_profile": False}
    assert _role_mismatch_reason("requester", "regular-user", profile) is None
    assert _role_mismatch_reason("donor", "regular-user", profile) == "donor_capability_missing"
    assert _role_mismatch_reason("security_admin", "regular-user", profile) == "account_not_in_admin_allowlist"
    assert _role_mismatch_reason("security_admin", "approved-admin", profile) is None
    assert _role_mismatch_reason("super_admin", "approved-admin", profile) == "super_admin_requires_rbac_manager_allowlist"
    assert _role_mismatch_reason("super_admin", "security-owner", profile) == "account_not_in_admin_allowlist"


def test_audit_action_kind_classifies_application_crud_verbs():
    from app.admin_api import _audit_action_kind
    assert _audit_action_kind("api.added.emergency_request") == "ADD"
    assert _audit_action_kind("api.updated.donor_profile") == "EDIT"
    assert _audit_action_kind("api.deleted.donor_profile") == "DELETE"
    assert _audit_action_kind("admin.dashboard.view") == "OTHER"



def test_api_activity_methods_map_to_crud_categories():
    from app.main_postgres import _operation_audit_kind
    assert _operation_audit_kind("POST") == ("ADD", "added")
    assert _operation_audit_kind("PUT") == ("EDIT", "updated")
    assert _operation_audit_kind("PATCH") == ("EDIT", "updated")
    assert _operation_audit_kind("DELETE") == ("DELETE", "deleted")
    assert _operation_audit_kind("GET") == ("OTHER", "viewed")


def test_admin_audit_query_explicitly_types_nullable_action_filter():
    from app.admin_api import admin_audit_logs

    class EmptyRows:
        def mappings(self):
            return self

        def all(self):
            return []

    class AuditQuerySession(FakeSession):
        async def execute(self, statement, params=None):
            sql = str(statement)
            self.statements.append(sql)
            if "FROM audit_events WHERE" in sql:
                assert params["action"] is None
                assert params["actor_id"] == "user-42"
                assert params["correlation_id"] is None
                return EmptyRows()
            return None

    session = AuditQuerySession()

    async def check():
        await admin_audit_logs(
            request=_request_context(),
            session=session,
            principal=Principal(subject="admin-1", email_verified=True, session_id="session-1"),
            limit=50,
            offset=0,
            action=None,
            actor_id="user-42",
            correlation_id=None,
            x_correlation_id=None,
        )

    asyncio.run(check())
    audit_query = next(statement for statement in session.statements if "FROM audit_events WHERE" in statement)
    assert "CAST(:action AS varchar) IS NULL" in audit_query
    assert "action = CAST(:action AS varchar)" in audit_query
    assert "CAST(:actor_id AS varchar) IS NULL" in audit_query
    assert "actor_id = CAST(:actor_id AS varchar)" in audit_query
    assert "CAST(:correlation_id AS varchar) IS NULL" in audit_query
    assert "correlation_id = CAST(:correlation_id AS varchar)" in audit_query


@pytest.mark.parametrize(
    ("alert_status", "correlation_id"),
    [(None, None), ("open", "web:alert-123")],
)
def test_security_alert_query_explicitly_types_nullable_filters(monkeypatch, alert_status, correlation_id):
    from app import admin_api

    class EmptyRows:
        def mappings(self):
            return self

        def all(self):
            return []

    class AlertQuerySession(FakeSession):
        async def execute(self, statement, params=None):
            sql = str(statement)
            self.statements.append(sql)
            if "FROM lifelink_security_alerts" in sql:
                assert params["status"] == alert_status
                assert params["correlation_id"] == correlation_id
                return EmptyRows()
            return None

    async def no_rbac_scan(*args, **kwargs):
        return 0

    async def no_audit(*args, **kwargs):
        return None

    monkeypatch.setattr(admin_api, "_scan_rbac_discrepancies", no_rbac_scan)
    monkeypatch.setattr(admin_api, "_write_audit", no_audit)
    session = AlertQuerySession()

    async def check():
        response = await admin_api.admin_security_alerts(
            request=_request_context(),
            session=session,
            principal=Principal(subject="admin-1", email_verified=True, session_id="session-1"),
            limit=50,
            offset=0,
            alert_status=alert_status,
            correlation_id=correlation_id,
            x_correlation_id="web:alerts-list",
        )
        assert response["items"] == []

    asyncio.run(check())
    alert_query = next(statement for statement in session.statements if "FROM lifelink_security_alerts" in statement)
    assert "CAST(:status AS varchar) IS NULL" in alert_query
    assert "status = CAST(:status AS varchar)" in alert_query
    assert "CAST(:correlation_id AS varchar) IS NULL" in alert_query
    assert "correlation_id = CAST(:correlation_id AS varchar)" in alert_query


@pytest.mark.parametrize("correlation_id", [None, "web:error-123"])
def test_admin_errors_query_explicitly_types_nullable_correlation_filter(monkeypatch, correlation_id):
    from app import admin_api

    class EmptyRows:
        def mappings(self):
            return self

        def all(self):
            return []

    class ErrorQuerySession(FakeSession):
        async def execute(self, statement, params=None):
            sql = str(statement)
            self.statements.append(sql)
            if "FROM audit_events WHERE (action LIKE 'error.%'" in sql:
                assert params["correlation_id"] == correlation_id
                return EmptyRows()
            return None

    async def no_audit(*args, **kwargs):
        return None

    monkeypatch.setattr(admin_api, "_write_audit", no_audit)
    session = ErrorQuerySession()

    async def check():
        response = await admin_api.admin_errors(
            request=_request_context(),
            session=session,
            principal=Principal(subject="admin-1", email_verified=True, session_id="session-1"),
            limit=50,
            offset=0,
            correlation_id=correlation_id,
        )
        assert response["items"] == []

    asyncio.run(check())
    error_query = next(
        statement for statement in session.statements
        if "FROM audit_events WHERE (action LIKE 'error.%'" in statement
    )
    assert "CAST(:correlation_id AS varchar) IS NULL" in error_query
    assert "correlation_id = CAST(:correlation_id AS varchar)" in error_query


@pytest.mark.parametrize("correlation_id", [None, "web:session-123"])
def test_admin_sessions_query_exposes_and_filters_related_correlation(monkeypatch, correlation_id):
    from app import admin_api

    class EmptyRows:
        def mappings(self):
            return self

        def all(self):
            return []

    class SessionQuerySession(FakeSession):
        async def execute(self, statement, params=None):
            sql = str(statement)
            self.statements.append(sql)
            if "FROM lifelink_user_sessions s" in sql:
                assert params["correlation_id"] == correlation_id
                return EmptyRows()
            return None

    async def no_audit(*args, **kwargs):
        return None

    monkeypatch.setattr(admin_api, "_write_audit", no_audit)
    session = SessionQuerySession()

    async def check():
        response = await admin_api.admin_list_sessions(
            request=_request_context(),
            session=session,
            principal=Principal(subject="admin-1", email_verified=True, session_id="session-1"),
            limit=50,
            offset=0,
            correlation_id=correlation_id,
            x_correlation_id="web:sessions-list",
        )
        assert response["items"] == []

    asyncio.run(check())
    query = next(statement for statement in session.statements if "FROM lifelink_user_sessions s" in statement)
    assert "COALESCE(matched.correlation_id, latest.correlation_id) AS last_correlation_id" in query
    assert "LEFT JOIN LATERAL" in query
    assert "CAST(:correlation_id AS varchar) IS NULL" in query
    assert "ae.correlation_id = CAST(:correlation_id AS varchar)" in query
    assert "ae.target_type = 'user_session'" in query
    assert "ae.target_id = CAST(s.session_id AS varchar)" in query
    assert "matched.correlation_id IS NOT NULL" in query
    assert "COALESCE(matched.correlation_id, latest.correlation_id)" in query


def test_security_alert_status_change_audit_uses_request_correlation(monkeypatch):
    from app import admin_api

    class UpdatedRow:
        def mappings(self):
            return self

        def first(self):
            return {"id": "alert-1", "rule_id": "IDS-03", "status": "resolved"}

    class AlertUpdateSession(FakeSession):
        async def execute(self, statement, params=None):
            self.statements.append(str(statement))
            return UpdatedRow()

    recorded = {}

    async def capture_audit(*args, **kwargs):
        recorded.update(kwargs)

    monkeypatch.setattr(admin_api, "_write_audit", capture_audit)
    session = AlertUpdateSession()

    async def check():
        response = await admin_api.admin_update_security_alert(
            alert_id="alert-1",
            payload=admin_api.AdminAlertUpdate(status="resolved"),
            request=_request_context(),
            session=session,
            principal=Principal(subject="admin-1", email_verified=True, session_id="session-1"),
            x_correlation_id="web:alert-close",
        )
        assert response == {"id": "alert-1", "rule_id": "IDS-03", "status": "resolved"}

    asyncio.run(check())
    assert recorded["correlation_id"] == "web:alert-close"
    assert recorded["target_id"] == "alert-1"

def test_security_alert_reads_do_not_trigger_rbac_scans(monkeypatch):
    """Alert retrieval is read-only; the independent lifespan task owns detection."""
    from app.admin_api import admin_security_alerts

    class EmptyRows:
        def mappings(self):
            return self

        def all(self):
            return []

    class Session:
        async def execute(self, statement, params=None):
            return EmptyRows()

        async def commit(self):
            return None

    scan_calls = 0

    async def scan(session, principal, request):
        nonlocal scan_calls
        scan_calls += 1
        return 0

    audit_filter_flags = []

    async def write_audit(*args, **kwargs):
        audit_filter_flags.append(kwargs["metadata"]["correlation_filtered"])

    monkeypatch.setattr("app.admin_api._scan_rbac_discrepancies", scan)
    monkeypatch.setattr("app.admin_api._write_audit", write_audit)

    async def check():
        request = _request_context()
        principal = Principal(subject="admin-1", session_id="admin-session")
        filtered = await admin_security_alerts(
            request=request,
            session=Session(),
            principal=principal,
            limit=50,
            offset=0,
            alert_status=None,
            correlation_id="previous-alert-correlation",
            x_correlation_id="filter-request-correlation",
        )
        assert filtered["items"] == []
        assert scan_calls == 0
        assert audit_filter_flags == [True]

        # An explicitly empty filter also remains read-only.
        empty_filtered = await admin_security_alerts(
            request=request,
            session=Session(),
            principal=principal,
            limit=50,
            offset=0,
            alert_status=None,
            correlation_id="",
            x_correlation_id="empty-filter-request-correlation",
        )
        assert empty_filtered["items"] == []
        assert scan_calls == 0
        assert audit_filter_flags == [True, True]

        await admin_security_alerts(
            request=request,
            session=Session(),
            principal=principal,
            limit=50,
            offset=0,
            alert_status=None,
            correlation_id=None,
            x_correlation_id="unfiltered-request-correlation",
        )
        assert scan_calls == 0
        assert audit_filter_flags == [True, True, False]

    asyncio.run(check())


@pytest.mark.parametrize(
    ("include_aal", "aal_claim", "expected"),
    [
        (False, None, "aal1"),
        (True, None, "unknown"),
        (True, "aal1", "aal1"),
        (True, "aal2", "aal2"),
        (True, "aal3", "unknown"),
        (True, "", "unknown"),
        (True, False, "unknown"),
        (True, 2, "unknown"),
    ],
)
def test_verified_supabase_principal_preserves_authenticator_assurance(
    monkeypatch, include_aal, aal_claim, expected
):
    """Preserve only recognized Supabase AAL values; absent AAL is AAL1."""
    from app import security

    monkeypatch.setenv("SUPABASE_URL", "https://example.supabase.co")
    monkeypatch.setattr(
        security,
        "_jwks_client",
        lambda: SimpleNamespace(
            get_signing_key_from_jwt=lambda token: SimpleNamespace(key="test-signing-key")
        ),
    )
    claims = {
        "sub": "admin-1",
        "email": "admin@example.test",
        "email_verified": True,
        "session_id": "session-1",
        "exp": 2_000_000_000,
        "iat": 1_900_000_000,
        "iss": "https://example.supabase.co/auth/v1",
        "aud": "authenticated",
    }
    if include_aal:
        claims["aal"] = aal_claim
    monkeypatch.setattr(security.jwt, "decode", lambda *args, **kwargs: claims)

    principal = security._verify_supabase_token("signed-test-token")

    assert principal.assurance_level == expected


def test_background_rbac_finding_uses_system_actor_without_request_context():
    from app.admin_api import _record_rbac_mismatch

    class CaptureSession(FakeSession):
        def __init__(self):
            super().__init__()
            self.executed = []

        async def execute(self, statement, params=None):
            self.executed.append((str(statement), params or {}))
            return await super().execute(statement, params)

    session = CaptureSession()
    principal = Principal(subject="system:rbac-scanner", email_verified=True)

    asyncio.run(
        _record_rbac_mismatch(
            session,
            principal,
            None,
            "00000000-0000-0000-0000-000000000001",
            "donor",
            "donor_capability_missing",
        )
    )

    audit_insert = next(statement for statement in session.statements if "INSERT INTO audit_events" in statement)
    assert "(id, log_id, actor_id, action, actions, description, session_id" in audit_insert
    alert_insert = next(statement for statement in session.statements if "INSERT INTO lifelink_security_alerts" in statement)
    audit_params = next(params for statement, params in session.executed if "INSERT INTO audit_events" in statement)
    assert audit_params["actor_id"] == "system:rbac-scanner"
    assert audit_params["session_id"] is None
    assert "CASE WHEN lifelink_security_alerts.evidence IS DISTINCT FROM EXCLUDED.evidence THEN 1 ELSE 0 END" in alert_insert


def test_rbac_alert_occurrences_do_not_increase_for_identical_evidence():
    from app.admin_api import _record_rbac_mismatch

    session = FakeSession()
    principal = Principal(subject="system:rbac-scanner", email_verified=True)

    asyncio.run(
        _record_rbac_mismatch(
            session,
            principal,
            None,
            "00000000-0000-0000-0000-000000000001",
            "donor",
            "donor_capability_missing",
        )
    )

    alert_insert = next(statement for statement in session.statements if "INSERT INTO lifelink_security_alerts" in statement)
    assert "IS DISTINCT FROM EXCLUDED.evidence" in alert_insert
    assert "ELSE 0 END" in alert_insert


def test_rbac_scanner_runs_without_http_request_context():
    from app.admin_api import _scan_rbac_discrepancies

    class Rows:
        def __init__(self, rows):
            self.rows = rows

        def mappings(self):
            return self

        def all(self):
            return self.rows

    class ScanSession(FakeSession):
        async def execute(self, statement, params=None):
            sql = str(statement)
            self.statements.append(sql)
            if "FROM lifelink_roles r" in sql:
                return Rows([
                    {"role_key": "operations_admin", "permissions": ["dashboard.read", "users.read", "requests.read", "operations.read", "reports.read"]},
                    {"role_key": "user_admin", "permissions": ["dashboard.read", "users.read", "users.update", "users.suspend", "roles.read"]},
                    {"role_key": "security_admin", "permissions": ["dashboard.read", "audit.read", "security.alerts.read", "security.alerts.manage", "security.sessions.read", "security.sessions.revoke"]},
                    {"role_key": "auditor", "permissions": ["dashboard.read", "audit.read", "reports.read"]},
                    {"role_key": "requester", "permissions": []},
                    {"role_key": "donor", "permissions": []},
                    {"role_key": "super_admin", "permissions": []},
                ])
            return Rows([])

    session = ScanSession()
    principal = Principal(subject="system:rbac-scanner", email_verified=True)

    findings = asyncio.run(_scan_rbac_discrepancies(session, principal, None))

    assert findings == 0
    assert "FROM lifelink_user_roles ur" in session.statements[0]

def test_rbac_permission_policy_detects_missing_and_unexpected_grants():
    from app.admin_api import _rbac_permission_mismatch

    actual = {"dashboard.read", "users.read", "unexpected.permission"}
    assert _rbac_permission_mismatch("operations_admin", actual) == (
        ["operations.read", "reports.read", "requests.read"],
        ["unexpected.permission"],
    )
    assert _rbac_permission_mismatch("auditor", {"dashboard.read", "audit.read", "reports.read"}) == ([], [])
    # End-user roles hold no administrative permission keys; their application
    # access remains governed by capability, ownership, and participant checks.
    assert _rbac_permission_mismatch("requester", set()) == ([], [])
    assert _rbac_permission_mismatch("donor", set()) == ([], [])
    # super_admin is a separately allowlisted privileged path, not a wildcard grant.
    assert _rbac_permission_mismatch("super_admin", set()) == ([], [])
    assert _rbac_permission_mismatch("requester", {"users.read"}) == ([], ["users.read"])
    assert _rbac_permission_mismatch("donor", {"security.alerts.manage"}) == ([], ["security.alerts.manage"])
    assert _rbac_permission_mismatch("super_admin", {"permissions.manage"}) == ([], ["permissions.manage"])


def test_role_policy_integrity_scans_seeded_roles_without_user_assignments():
    from app.admin_api import _scan_rbac_role_policy_integrity

    class Rows:
        def mappings(self):
            return self

        def all(self):
            return [
                {"role_key": "operations_admin", "permissions": ["dashboard.read", "users.read"]},
                {"role_key": "user_admin", "permissions": ["dashboard.read", "users.read", "users.update", "users.suspend", "roles.read"]},
                {"role_key": "security_admin", "permissions": ["dashboard.read", "audit.read", "security.alerts.read", "security.alerts.manage", "security.sessions.read", "security.sessions.revoke"]},
                {"role_key": "auditor", "permissions": ["dashboard.read", "audit.read", "reports.read"]},
                {"role_key": "requester", "permissions": []},
                {"role_key": "donor", "permissions": []},
                {"role_key": "super_admin", "permissions": []},
            ]

    class Session:
        def __init__(self):
            self.statements = []

        async def execute(self, statement, params=None):
            self.statements.append((str(statement), params or {}))
            return Rows()

    session = Session()
    principal = Principal(subject="system:rbac-scanner", email_verified=True)
    findings = asyncio.run(_scan_rbac_role_policy_integrity(session, principal, None))

    assert findings == 1
    assert "FROM lifelink_roles r" in session.statements[0][0]
    assert "lifelink_user_roles" not in session.statements[0][0]
    insert_sql, params = session.statements[4]
    assert "INSERT INTO lifelink_role_security_findings" in insert_sql
    assert "ON CONFLICT (rule_id, role_key)" in insert_sql
    assert params["role_key"] == "operations_admin"
    assert '"missing_permission_keys": ["operations.read", "reports.read", "requests.read"]' in params["evidence"]
    assert session.statements[5][1]["role_key"] == "user_admin"
    assert "UPDATE lifelink_role_security_findings" in session.statements[5][0]


def test_role_policy_integrity_detects_missing_approved_role_definition():
    from app.admin_api import _scan_rbac_role_policy_integrity

    class Rows:
        def mappings(self):
            return self

        def all(self):
            # security_admin is deliberately absent from the registry result.
            return [
                {"role_key": "operations_admin", "permissions": ["dashboard.read", "users.read", "requests.read", "operations.read", "reports.read"]},
                {"role_key": "user_admin", "permissions": ["dashboard.read", "users.read", "users.update", "users.suspend", "roles.read"]},
                {"role_key": "requester", "permissions": []},
                {"role_key": "donor", "permissions": []},
                {"role_key": "super_admin", "permissions": []},
                {"role_key": "auditor", "permissions": ["dashboard.read", "audit.read", "reports.read"]},
            ]

    class Session:
        def __init__(self):
            self.statements = []

        async def execute(self, statement, params=None):
            self.statements.append((str(statement), params or {}))
            return Rows()

    session = Session()
    principal = Principal(subject="system:rbac-scanner", email_verified=True)
    findings = asyncio.run(_scan_rbac_role_policy_integrity(session, principal, None))

    assert findings == 1
    insert_sql, params = session.statements[6]
    assert "INSERT INTO lifelink_role_security_findings" in insert_sql
    assert params["role_key"] == "security_admin"
    assert '"role_definition_missing": true' in params["evidence"]


def test_role_policy_integrity_enforces_explicit_zero_admin_grants_for_requester_donor_and_super_admin():
    from app.admin_api import _scan_rbac_role_policy_integrity

    class Rows:
        def mappings(self):
            return self

        def all(self):
            return [
                {"role_key": "operations_admin", "permissions": ["dashboard.read", "users.read", "requests.read", "operations.read", "reports.read"]},
                {"role_key": "user_admin", "permissions": ["dashboard.read", "users.read", "users.update", "users.suspend", "roles.read"]},
                {"role_key": "security_admin", "permissions": ["dashboard.read", "audit.read", "security.alerts.read", "security.alerts.manage", "security.sessions.read", "security.sessions.revoke"]},
                {"role_key": "auditor", "permissions": ["dashboard.read", "audit.read", "reports.read"]},
                {"role_key": "requester", "permissions": []},
                {"role_key": "donor", "permissions": []},
                {"role_key": "super_admin", "permissions": ["settings.update"]},
            ]

    class Session:
        def __init__(self):
            self.statements = []

        async def execute(self, statement, params=None):
            self.statements.append((str(statement), params or {}))
            return Rows()

    session = Session()
    principal = Principal(subject="system:rbac-scanner", email_verified=True)
    findings = asyncio.run(_scan_rbac_role_policy_integrity(session, principal, None))

    assert findings == 1
    insert_sql, params = session.statements[3]
    assert "INSERT INTO lifelink_role_security_findings" in insert_sql
    assert params["role_key"] == "super_admin"
    assert '"unexpected_permission_keys": ["settings.update"]' in params["evidence"]
    assert len(session.statements) == 9



def test_admin_permission_guard_defaults_to_legacy_compatible_observe_mode(monkeypatch):
    from app import admin_api

    require_admin_permission = admin_api.require_admin_permission

    monkeypatch.delenv("LIFELINK_RBAC_ENFORCEMENT_MODE", raising=False)
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")
    session = FakeSession([0, 2])

    async def check():
        request = _request_context()
        principal = Principal(
            subject="admin-1", email_verified=True, session_id="session-1", assurance_level="aal2"
        )
        await admin_api.require_admin(request=request, principal=principal, session=session)
        principal = await require_admin_permission("users.read")(
            request=request, principal=principal, session=session
        )
        assert principal.subject == "admin-1"

    asyncio.run(check())
    assert len(session.scalar_statements) == 2
    assert all("lifelink_user_roles" not in statement for statement in session.scalar_statements)


def test_admin_permission_guard_enforces_exact_active_role_permission(monkeypatch):
    from app import admin_api

    require_admin_permission = admin_api.require_admin_permission
    monkeypatch.setenv("LIFELINK_RBAC_ENFORCEMENT_MODE", "enforce")
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")
    session = FakeSession([0, 2, 1])

    async def check():
        request = _request_context()
        principal = Principal(
            subject="admin-1", email_verified=True, session_id="session-1", assurance_level="aal2"
        )
        await admin_api.require_admin(request=request, principal=principal, session=session)
        principal = await require_admin_permission("users.read")(
            request=request, principal=principal, session=session
        )
        assert principal.subject == "admin-1"

    asyncio.run(check())
    permission_query = session.scalar_statements[-1]
    assert "FROM lifelink_user_roles ur" in permission_query
    assert "LEFT JOIN lifelink_role_permissions rp" in permission_query
    assert "LEFT JOIN lifelink_permissions p" in permission_query
    assert "p.permission_key = :permission_key" in permission_query
    assert "ur.expires_at > now()" in permission_query


def test_admin_permission_guard_denies_missing_permission_and_records_ids08(monkeypatch):
    from app import admin_api

    monkeypatch.setenv("LIFELINK_RBAC_ENFORCEMENT_MODE", "enforce")
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")
    recorded = {}

    async def capture_denial(session, principal, request, **kwargs):
        recorded.update(kwargs)

    monkeypatch.setattr(admin_api, "_record_denied_admin_attempt", capture_denial)
    session = FakeSession([0, 2, 0])

    async def check():
        request = _request_context()
        principal = Principal(
            subject="admin-1", email_verified=True, session_id="session-1", assurance_level="aal2"
        )
        await admin_api.require_admin(request=request, principal=principal, session=session)
        with pytest.raises(HTTPException) as error:
            await admin_api.require_admin_permission("security.sessions.revoke")(
                request=request, principal=principal, session=session
            )
        assert error.value.status_code == 403
        assert "does not grant" in error.value.detail

    asyncio.run(check())
    assert recorded["rule_id"] == "IDS-08"
    assert recorded["action"] == "security.admin_permission.denied"
    assert recorded["severity"] == "medium"


def test_admin_permission_guard_fails_closed_on_invalid_mode(monkeypatch):
    from app import admin_api

    require_admin_permission = admin_api.require_admin_permission

    monkeypatch.setenv("LIFELINK_RBAC_ENFORCEMENT_MODE", "typo")
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")

    async def check():
        request = _request_context()
        principal = Principal(
            subject="admin-1", email_verified=True, session_id="session-1", assurance_level="aal2"
        )
        session = FakeSession([0, 2])
        await admin_api.require_admin(request=request, principal=principal, session=session)
        with pytest.raises(HTTPException) as error:
            await require_admin_permission("audit.read")(
                request=request, principal=principal, session=session
            )
        assert error.value.status_code == 503

    asyncio.run(check())



def test_admin_scanner_health_returns_persisted_status_without_error_details(monkeypatch):
    from app import admin_api
    monkeypatch.setenv("LIFELINK_RBAC_SCAN_SECONDS", "inf")

    class Rows:
        def mappings(self):
            return self

        def first(self):
            return {
                "scanner_key": "rbac",
                "status": "failed",
                "last_findings": 3,
                "last_duration_ms": 121,
                "consecutive_failures": 2,
                "last_error_code": "scan_failed",
            }

    class Session(FakeSession):
        async def execute(self, statement, params=None):
            self.statements.append(str(statement))
            return Rows()

    async def no_audit(*args, **kwargs):
        return None

    monkeypatch.setattr(admin_api, "_write_audit", no_audit)
    session = Session()

    async def check():
        result = await admin_api.admin_security_scanner_health(
            request=_request_context(),
            session=session,
            principal=Principal(subject="admin-1", email_verified=True, session_id="session-1"),
        )
        assert result["status"] == "failed"
        assert result["last_findings"] == 3
        assert result["last_duration_ms"] == 121
        assert result["scan_interval_seconds"] == 300
        assert result["consecutive_failures"] == 2
        assert result["last_error_code"] == "scan_failed"
        assert "exception" not in result
        assert "SELECT scanner_key, status" in session.statements[0]

    asyncio.run(check())


def test_admin_scanner_health_reports_never_run_when_no_status_exists(monkeypatch):
    monkeypatch.delenv("LIFELINK_RBAC_SCAN_SECONDS", raising=False)
    from app import admin_api

    class Rows:
        def mappings(self):
            return self

        def first(self):
            return None

    class Session(FakeSession):
        async def execute(self, statement, params=None):
            self.statements.append(str(statement))
            return Rows()

    async def no_audit(*args, **kwargs):
        return None

    monkeypatch.setattr(admin_api, "_write_audit", no_audit)

    async def check():
        result = await admin_api.admin_security_scanner_health(
            request=_request_context(),
            session=Session(),
            principal=Principal(subject="admin-1", email_verified=True, session_id="session-1"),
        )
        assert result == {
            "scanner_key": "rbac", "status": "never_run", "last_findings": 0,
            "consecutive_failures": 0, "last_error_code": None, "scan_interval_seconds": 300,
        }

    asyncio.run(check())



def test_admin_routes_declare_the_expected_permission_matrix():
    from app.admin_api import router

    expected = {
        ("GET", "/v1/admin/me"): "dashboard.read",
        ("GET", "/v1/admin/dashboard"): "dashboard.read",
        ("GET", "/v1/admin/users"): "users.read",
        ("PATCH", "/v1/admin/users/{user_id}"): "users.update",
        ("GET", "/v1/admin/security/role-integrity"): "audit.read",
        ("GET", "/v1/admin/security/scanner"): "security.alerts.read",
        ("GET", "/v1/admin/security/notifications"): "security.alerts.read",
        ("GET", "/v1/admin/security/alerts"): "security.alerts.read",
        ("PATCH", "/v1/admin/security/alerts/{alert_id}"): "security.alerts.manage",
        ("GET", "/v1/admin/sessions"): "security.sessions.read",
        ("POST", "/v1/admin/sessions/{session_id}/revoke"): "security.sessions.revoke",
        ("GET", "/v1/admin/audit"): "audit.read",
        ("GET", "/v1/admin/errors"): "audit.read",
        ("GET", "/v1/admin/roles"): "roles.read",
        ("GET", "/v1/admin/users/{user_id}/roles"): "roles.read",
    }
    actual = {}
    for route in router.routes:
        route_keys = {(method, route.path) for method in getattr(route, "methods", set())}
        for route_key in route_keys.intersection(expected):
            guard = next(
                dependency.call for dependency in route.dependant.dependencies
                if dependency.call.__name__ == "require_admin"
            )
            actual[route_key] = getattr(guard, "permission_key", None)
    assert actual == expected



def test_admin_security_notification_feed_returns_only_safe_event_metadata(monkeypatch):
    from datetime import datetime, timezone

    from app import admin_api

    event_time = datetime(2026, 11, 27, 13, 0, tzinfo=timezone.utc)

    class Rows:
        def mappings(self):
            return self

        def all(self):
            return [{
                "id": "00000000-0000-0000-0000-000000000001",
                "rule_id": "IDS-03",
                "severity": "high",
                "created_at": event_time,
                "source": "alert",
            }]

    class Session(FakeSession):
        async def execute(self, statement, params=None):
            self.statements.append(str(statement))
            return Rows()

    async def no_audit(*args, **kwargs):
        return None

    monkeypatch.setattr(admin_api, "_write_audit", no_audit)
    session = Session()

    async def check():
        result = await admin_api.admin_security_notifications(
            request=_request_context(),
            after=datetime(2026, 11, 27, 12, 59, tzinfo=timezone.utc),
            after_id="00000000-0000-0000-0000-000000000000",
            limit=25,
            session=session,
            principal=Principal(subject="admin-1", email_verified=True, session_id="session-1"),
        )
        assert result["items"] == [{
            "id": "00000000-0000-0000-0000-000000000001",
            "rule_id": "IDS-03",
            "severity": "high",
            "created_at": event_time,
            "source": "alert",
        }]
        assert result["cursor"] == {
            "created_at": event_time, "id": "00000000-0000-0000-0000-000000000001"
        }
        assert "lifelink_security_alerts" in session.statements[0]
        assert "lifelink_role_security_findings" in session.statements[0]
        assert "evidence" not in result["items"][0]
        assert "summary" not in result["items"][0]

    asyncio.run(check())



def test_admin_security_notification_feed_rejects_timezone_naive_cursor():
    from datetime import datetime

    from app import admin_api

    async def check():
        with pytest.raises(HTTPException) as error:
            await admin_api.admin_security_notifications(
                request=_request_context(),
                after=datetime(2026, 11, 27, 13, 0),
                after_id="00000000-0000-0000-0000-000000000000",
                limit=25,
                session=FakeSession(),
                principal=Principal(subject="admin-1", email_verified=True, session_id="session-1"),
            )
        assert error.value.status_code == 422

    asyncio.run(check())


def test_super_admin_permission_requires_separate_rbac_manager_allowlist(monkeypatch):
    from app import admin_api

    monkeypatch.setenv("LIFELINK_RBAC_ENFORCEMENT_MODE", "enforce")
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")
    monkeypatch.setenv("LIFELINK_RBAC_MANAGER_USER_IDS", "admin-1")
    session = FakeSession([0, 2, 1])

    async def check():
        request = _request_context()
        principal = Principal(
            subject="admin-1", email_verified=True, session_id="session-1", assurance_level="aal2"
        )
        await admin_api.require_admin(request=request, principal=principal, session=session)
        principal = await admin_api.require_admin_permission("settings.update")(
            request=request, principal=principal, session=session
        )
        assert principal.subject == "admin-1"

    asyncio.run(check())
    query = session.scalar_statements[-1]
    params = session.scalar_parameters[-1]
    assert "r.role_key = 'super_admin' AND :is_rbac_manager" in query
    assert "p.permission_key = :permission_key" in query
    assert params["is_rbac_manager"] is True


def test_super_admin_permission_is_not_granted_without_rbac_manager_allowlist(monkeypatch):
    from app import admin_api

    monkeypatch.setenv("LIFELINK_RBAC_ENFORCEMENT_MODE", "enforce")
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")
    monkeypatch.delenv("LIFELINK_RBAC_MANAGER_USER_IDS", raising=False)
    session = FakeSession([0, 2, 0])
    recorded = {}

    async def capture_denial(session, principal, request, **kwargs):
        recorded.update(kwargs)

    monkeypatch.setattr(admin_api, "_record_denied_admin_attempt", capture_denial)

    async def check():
        request = _request_context()
        principal = Principal(
            subject="admin-1", email_verified=True, session_id="session-1", assurance_level="aal2"
        )
        await admin_api.require_admin(request=request, principal=principal, session=session)
        with pytest.raises(HTTPException) as error:
            await admin_api.require_admin_permission("settings.update")(
                request=request, principal=principal, session=session
            )
        assert error.value.status_code == 403

    asyncio.run(check())
    assert session.scalar_parameters[-1]["is_rbac_manager"] is False
    assert recorded["rule_id"] == "IDS-08"


def test_admin_user_inventory_includes_auth_accounts_and_effective_rbac_permissions():
    from datetime import datetime, timezone

    from app.admin_api import admin_list_users

    class Rows:
        def mappings(self):
            return self

        def all(self):
            return [{
                "user_id": "user-1",
                "email": "user@example.test",
                "role": "requester",
                "display_name": None,
                "can_request": True,
                "can_donate": False,
                "created_at": datetime(2026, 1, 1, tzinfo=timezone.utc),
                "has_profile": True,
                "rbac_roles": ["requester"],
                "rbac_permissions": [],
            }]

    class UserInventorySession(FakeSession):
        async def execute(self, statement, params=None):
            sql = str(statement)
            self.statements.append(sql)
            if "FROM auth.users u" in sql:
                return Rows()
            return None

    session = UserInventorySession([42])

    async def check():
        result = await admin_list_users(
            request=_request_context(),
            session=session,
            principal=Principal(subject="admin-1", email_verified=True),
            limit=25,
            offset=0,
            x_correlation_id=None,
        )
        assert result["total"] == 42
        assert result["items"][0]["user_id"] == "user-1"
        assert result["items"][0]["has_profile"] is True
        assert result["items"][0]["rbac_roles"] == ["requester"]
        assert result["items"][0]["rbac_permissions"] == []
        query = next(sql for sql in session.statements if "FROM auth.users u" in sql)
        assert "LEFT JOIN lifelink_profiles" in query
        assert "LEFT JOIN lifelink_user_roles" in query
        assert "LEFT JOIN lifelink_role_permissions" in query
        assert "ur.expires_at > now()" in query
        assert "array_agg(DISTINCT perm.permission_key" in query

    asyncio.run(check())



def test_rbac_role_policy_integrity_flags_unapproved_unassigned_role():
    from app.admin_api import RBAC_APPROVED_PERMISSION_MATRIX, _scan_rbac_role_policy_integrity

    class Rows:
        def __init__(self, values):
            self.values = values

        def mappings(self):
            return self

        def all(self):
            return self.values

    class CaptureSession:
        def __init__(self):
            self.calls = []

        async def execute(self, statement, params=None):
            sql = str(statement)
            values = params or {}
            self.calls.append((sql, values))
            if "FROM lifelink_roles r" in sql:
                roles = [
                    {"role_key": key, "permissions": sorted(permissions)}
                    for key, permissions in RBAC_APPROVED_PERMISSION_MATRIX.items()
                ]
                roles.append({"role_key": "legacy_admin", "permissions": ["users.read"]})
                return Rows(roles)
            if "SELECT role_key FROM lifelink_role_security_findings" in sql:
                return Rows([])
            return Rows([])

    session = CaptureSession()
    principal = Principal(subject="system:rbac-scanner", email_verified=True)

    findings = asyncio.run(_scan_rbac_role_policy_integrity(session, principal, None))

    assert findings == 1
    insert = next(
        (sql, params)
        for sql, params in session.calls
        if "INSERT INTO lifelink_role_security_findings" in sql
    )
    assert insert[1]["role_key"] == "legacy_admin"
    assert '"role_definition_unapproved": true' in insert[1]["evidence"]


def test_rbac_role_policy_integrity_resolves_removed_unapproved_role():
    from app.admin_api import RBAC_APPROVED_PERMISSION_MATRIX, _scan_rbac_role_policy_integrity

    class Rows:
        def __init__(self, values):
            self.values = values

        def mappings(self):
            return self

        def all(self):
            return self.values

    class CaptureSession:
        def __init__(self):
            self.calls = []

        async def execute(self, statement, params=None):
            sql = str(statement)
            values = params or {}
            self.calls.append((sql, values))
            if "FROM lifelink_roles r" in sql:
                roles = [
                    {"role_key": key, "permissions": sorted(permissions)}
                    for key, permissions in RBAC_APPROVED_PERMISSION_MATRIX.items()
                ]
                return Rows(roles)
            if "SELECT role_key FROM lifelink_role_security_findings" in sql:
                return Rows([{"role_key": "legacy_admin"}])
            return Rows([])

    session = CaptureSession()
    principal = Principal(subject="system:rbac-scanner", email_verified=True)

    findings = asyncio.run(_scan_rbac_role_policy_integrity(session, principal, None))

    assert findings == 0
    assert any(
        "UPDATE lifelink_role_security_findings SET status = 'resolved'" in sql
        and params["role_key"] == "legacy_admin"
        for sql, params in session.calls
    )
