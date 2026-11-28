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


def test_rbac_scanner_publishes_running_state_before_scan_and_persists_success(monkeypatch):
    session = ScannerSession()
    running_session = ScannerSession()
    sessions = iter((session, running_session))
    monkeypatch.setattr(db, "AsyncSessionLocal", lambda: next(sessions))

    async def scan_after_running_state_is_committed(*_args):
        # This simulates the Admin Console reading from another connection:
        # the running-state transaction must already be committed before work begins.
        assert running_session.committed is True
        assert any("status = 'running'" in statement for statement in running_session.statements)
        return 2

    monkeypatch.setattr(admin_api, "_scan_rbac_discrepancies", scan_after_running_state_is_committed)
    monkeypatch.setattr(main_postgres.asyncio, "sleep", _cancel_after_one_iteration)
    monkeypatch.setenv("LIFELINK_RBAC_SCAN_SECONDS", "60")

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(main_postgres._scan_rbac_discrepancies_forever())

    sql = "\\n".join(session.statements)
    running_sql = "\\n".join(running_session.statements)
    assert "pg_try_advisory_xact_lock(728194620114)" in sql
    assert "SET LOCAL statement_timeout = '15000ms'" in sql
    assert "status = 'succeeded'" in sql
    assert "last_succeeded_at = clock_timestamp()" in sql
    assert "last_finished_at = clock_timestamp()" in sql
    assert "updated_at = clock_timestamp()" in sql
    assert "last_findings = :findings" in sql
    assert "last_duration_ms = :duration_ms" in sql
    assert "INSERT INTO lifelink_security_scanner_runs" in running_sql
    assert "status = 'running'" in running_sql
    assert session.committed is True


async def _two_findings() -> int:
    return 2


def test_rbac_scanner_persists_sanitized_failure_and_retries(monkeypatch):
    scan_session = ScannerSession()
    running_session = ScannerSession()
    failed_session = ScannerSession()
    sessions = iter((scan_session, running_session, failed_session))
    monkeypatch.setattr(db, "AsyncSessionLocal", lambda: next(sessions))

    async def fail_scan(*_args):
        assert running_session.committed is True
        raise RuntimeError("private row details and SQL must not be persisted")

    monkeypatch.setattr(admin_api, "_scan_rbac_discrepancies", fail_scan)
    monkeypatch.setattr(main_postgres.asyncio, "sleep", _cancel_after_one_iteration)
    monkeypatch.setenv("LIFELINK_RBAC_SCAN_SECONDS", "60")

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(main_postgres._scan_rbac_discrepancies_forever())

    sql = "\\n".join(failed_session.statements)
    assert "status = 'running'" in "\\n".join(running_session.statements)
    assert running_session.committed is True
    assert "status = 'failed'" in sql
    assert "last_error_code = 'scan_failed'" in sql
    assert "last_failed_at = clock_timestamp()" in sql
    assert "last_started_at, last_finished_at, last_failed_at" in sql
    assert "clock_timestamp(), clock_timestamp(), clock_timestamp(), 'scan_failed'" in sql
    assert "consecutive_failures = lifelink_security_scanner_runs.consecutive_failures + 1" in sql
    assert "private row details" not in sql
    assert failed_session.committed is True
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

def test_fastapi_lifespan_starts_and_cancels_every_background_worker(monkeypatch):
    async def run_lifespan():
        started: set[str] = set()
        cancelled: set[str] = set()
        all_started = asyncio.Event()
        all_cancelled = asyncio.Event()
        worker_names = (
            "location-share",
            "donor-presence",
            "request-expiry",
            "rbac-scanner",
        )

        async def fake_create_all_tables():
            return None

        async def worker(name: str):
            started.add(name)
            if len(started) == len(worker_names):
                all_started.set()
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.add(name)
                if len(cancelled) == len(worker_names):
                    all_cancelled.set()

        monkeypatch.setattr(main_postgres, "create_all_tables", fake_create_all_tables)
        monkeypatch.setattr(
            main_postgres, "_expire_stale_location_shares_forever",
            lambda: worker("location-share"),
        )
        monkeypatch.setattr(
            main_postgres, "_expire_stale_donor_presence_forever",
            lambda: worker("donor-presence"),
        )
        monkeypatch.setattr(
            main_postgres, "_expire_timed_out_requests_forever",
            lambda: worker("request-expiry"),
        )
        monkeypatch.setattr(
            main_postgres, "_scan_rbac_discrepancies_forever",
            lambda: worker("rbac-scanner"),
        )

        async with main_postgres.lifespan(None):
            await asyncio.wait_for(all_started.wait(), timeout=1)

        await asyncio.wait_for(all_cancelled.wait(), timeout=1)
        assert started == set(worker_names)
        assert cancelled == set(worker_names)

    asyncio.run(run_lifespan())


def test_fastapi_lifespan_does_not_start_workers_when_schema_setup_fails(monkeypatch):
    async def run_lifespan():
        started: list[str] = []

        async def fail_schema_setup():
            raise RuntimeError("schema setup failed")

        async def worker():
            started.append("started")

        monkeypatch.setattr(main_postgres, "create_all_tables", fail_schema_setup)
        monkeypatch.setattr(main_postgres, "_expire_stale_location_shares_forever", worker)
        monkeypatch.setattr(main_postgres, "_expire_stale_donor_presence_forever", worker)
        monkeypatch.setattr(main_postgres, "_expire_timed_out_requests_forever", worker)
        monkeypatch.setattr(main_postgres, "_scan_rbac_discrepancies_forever", worker)

        with pytest.raises(RuntimeError, match="schema setup failed"):
            async with main_postgres.lifespan(None):
                pytest.fail("lifespan must not yield after schema setup failure")

        await asyncio.sleep(0)
        assert started == []

    asyncio.run(run_lifespan())
