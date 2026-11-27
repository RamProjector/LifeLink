from __future__ import annotations

import asyncio

import pytest

from app import admin_api, db, main_postgres


class ScannerSession:
    def __init__(self, *, lock_acquired: bool = True):
        self.lock_acquired = lock_acquired
        self.statements: list[str] = []
        self.committed = False
        self.rolled_back = False

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        if exc_type is not None:
            self.rolled_back = True
        return False

    async def scalar(self, statement, params=None):
        self.statements.append(str(statement))
        return self.lock_acquired

    async def execute(self, statement, params=None):
        self.statements.append(str(statement))
        return None

    async def commit(self):
        self.committed = True

    async def rollback(self):
        self.rolled_back = True


async def _cancel_after_one_iteration(_seconds: float) -> None:
    raise asyncio.CancelledError


def test_rbac_scanner_persists_success_and_findings(monkeypatch):
    session = ScannerSession()
    monkeypatch.setattr(db, "AsyncSessionLocal", lambda: session)
    monkeypatch.setattr(admin_api, "_scan_rbac_discrepancies", lambda *_args: _two_findings())
    monkeypatch.setattr(main_postgres.asyncio, "sleep", _cancel_after_one_iteration)
    monkeypatch.setenv("LIFELINK_RBAC_SCAN_SECONDS", "60")

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(main_postgres._scan_rbac_discrepancies_forever())

    sql = "\\n".join(session.statements)
    assert "pg_try_advisory_xact_lock(728194620114)" in sql
    assert "SET LOCAL statement_timeout = '15000ms'" in sql
    assert "INSERT INTO lifelink_security_scanner_runs" in sql
    assert "status = 'running'" in sql
    assert "status = 'succeeded'" in sql
    assert "last_succeeded_at = now()" in sql
    assert "last_findings = :findings" in sql
    assert "last_duration_ms = :duration_ms" in sql
    assert session.committed is True


async def _two_findings() -> int:
    return 2


def test_rbac_scanner_persists_sanitized_failure_and_retries(monkeypatch):
    scan_session = ScannerSession()
    health_session = ScannerSession()
    sessions = iter((scan_session, health_session))
    monkeypatch.setattr(db, "AsyncSessionLocal", lambda: next(sessions))

    async def fail_scan(*_args):
        raise RuntimeError("private row details and SQL must not be persisted")

    monkeypatch.setattr(admin_api, "_scan_rbac_discrepancies", fail_scan)
    monkeypatch.setattr(main_postgres.asyncio, "sleep", _cancel_after_one_iteration)
    monkeypatch.setenv("LIFELINK_RBAC_SCAN_SECONDS", "60")

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(main_postgres._scan_rbac_discrepancies_forever())

    sql = "\\n".join(health_session.statements)
    assert "status = 'failed'" in sql
    assert "last_error_code = 'scan_failed'" in sql
    assert "last_failed_at = now()" in sql
    assert "last_started_at = now()" in sql
    assert "consecutive_failures = lifelink_security_scanner_runs.consecutive_failures + 1" in sql
    assert "private row details" not in sql
    assert health_session.committed is True
    assert scan_session.rolled_back is True


def test_rbac_scanner_does_not_record_a_second_instance_lock_skip(monkeypatch):
    session = ScannerSession(lock_acquired=False)
    monkeypatch.setattr(db, "AsyncSessionLocal", lambda: session)
    monkeypatch.setattr(main_postgres.asyncio, "sleep", _cancel_after_one_iteration)
    monkeypatch.setenv("LIFELINK_RBAC_SCAN_SECONDS", "60")

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(main_postgres._scan_rbac_discrepancies_forever())

    assert session.rolled_back is True
    assert session.committed is False
    assert all("lifelink_security_scanner_runs" not in statement for statement in session.statements)


@pytest.mark.parametrize("configured_interval", ["nan", "inf", "-inf", "1e9999"])
def test_rbac_scanner_uses_safe_default_for_non_finite_intervals(monkeypatch, configured_interval):
    session = ScannerSession()
    monkeypatch.setattr(db, "AsyncSessionLocal", lambda: session)

    async def no_findings(*_args):
        return 0

    async def capture_interval(seconds: float) -> None:
        assert seconds == 300.0
        raise asyncio.CancelledError

    monkeypatch.setattr(admin_api, "_scan_rbac_discrepancies", no_findings)
    monkeypatch.setattr(main_postgres.asyncio, "sleep", capture_interval)
    monkeypatch.setenv("LIFELINK_RBAC_SCAN_SECONDS", configured_interval)

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(main_postgres._scan_rbac_discrepancies_forever())


def test_rbac_scanner_caps_extreme_interval(monkeypatch):
    session = ScannerSession()
    monkeypatch.setattr(db, "AsyncSessionLocal", lambda: session)

    async def no_findings(*_args):
        return 0

    async def capture_interval(seconds: float) -> None:
        assert seconds == 86400.0
        raise asyncio.CancelledError

    monkeypatch.setattr(admin_api, "_scan_rbac_discrepancies", no_findings)
    monkeypatch.setattr(main_postgres.asyncio, "sleep", capture_interval)
    monkeypatch.setenv("LIFELINK_RBAC_SCAN_SECONDS", "999999")

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(main_postgres._scan_rbac_discrepancies_forever())
