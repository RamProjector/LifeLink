from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app import db
from app.admin_api import _configured_admin_ids, _correlation_id, require_admin
from app.security import Principal


class FakeSession:
    def __init__(self, scalar_values=None):
        self.scalar_values = list(scalar_values or [0])
        self.statements = []

    async def execute(self, statement, params=None):
        self.statements.append(str(statement))
        return None

    async def scalar(self, statement, params=None):
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


def test_admin_allowlist_is_server_configured_not_user_claim(monkeypatch):
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1, admin-2")
    assert _configured_admin_ids() == {"admin-1", "admin-2"}

    async def check():
        with pytest.raises(HTTPException) as error:
            await require_admin(
                principal=Principal(subject="regular-user", role="admin", email_verified=True),
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
            principal=Principal(subject="admin-1", email_verified=True, session_id="session-1"),
            session=FakeSession([0]),
            request=_request_context(),
        )
        assert principal.subject == "admin-1"

    asyncio.run(check())


def test_admin_rejects_revoked_session(monkeypatch):
    monkeypatch.setenv("LIFELINK_ADMIN_USER_IDS", "admin-1")

    async def check():
        with pytest.raises(HTTPException) as error:
            await require_admin(
                principal=Principal(subject="admin-1", email_verified=True, session_id="session-revoked"),
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
        yield FakeSession([0])

    app.dependency_overrides[get_db_session] = fake_session
    app.dependency_overrides[get_postgres_principal] = lambda: Principal(
        subject="ordinary-admin",
        email_verified=True,
        session_id="ordinary-admin-session",
    )
    from app.security import get_active_postgres_principal
    app.dependency_overrides[get_active_postgres_principal] = lambda: Principal(
        subject="ordinary-admin",
        email_verified=True,
        session_id="ordinary-admin-session",
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
            x_correlation_id=None,
        )

    asyncio.run(check())
    audit_query = next(statement for statement in session.statements if "FROM audit_events WHERE" in statement)
    assert "CAST(:action AS varchar) IS NULL" in audit_query
    assert "action = CAST(:action AS varchar)" in audit_query
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

def test_security_alert_correlation_filter_does_not_mutate_alerts(monkeypatch):
    """Filtering must not run the RBAC scan that refreshes active alert correlations."""
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

        # An explicitly empty filter is still a filtered read: it must not
        # trigger a mutating RBAC scan before the SQL filter returns no matches.
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
        assert scan_calls == 1
        assert audit_filter_flags == [True, True, False]

    asyncio.run(check())



@pytest.mark.parametrize(
    ("aal_claim", "expected"),
    [
        (None, "aal1"),
        ("aal1", "aal1"),
        ("aal2", "aal2"),
        ("aal3", "unknown"),
        ("", "unknown"),
        (False, "unknown"),
        (2, "unknown"),
    ],
)
def test_verified_supabase_principal_preserves_authenticator_assurance(monkeypatch, aal_claim, expected):
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
    if aal_claim is not None:
        claims["aal"] = aal_claim
    monkeypatch.setattr(security.jwt, "decode", lambda *args, **kwargs: claims)

    principal = security._verify_supabase_token("signed-test-token")

    assert principal.assurance_level == expected
