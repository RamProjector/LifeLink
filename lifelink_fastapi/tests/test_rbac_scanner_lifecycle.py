from __future__ import annotations

import asyncio
import pytest


class ScannerSession:
    def __init__(self, lock_acquired: bool):
        self.lock_acquired = lock_acquired
        self.statements: list[str] = []
        self.committed = False
        self.rolled_back = False

    async def scalar(self, statement):
        self.statements.append(str(statement))
        return self.lock_acquired

    async def execute(self, statement):
        self.statements.append(str(statement))

    async def commit(self):
        self.committed = True

    async def rollback(self):
        self.rolled_back = True


class ScannerSessionContext:
    def __init__(self, session: ScannerSession):
        self.session = session

    async def __aenter__(self):
        return self.session

    async def __aexit__(self, exc_type, exc, tb):
        return False


@pytest.mark.parametrize("lock_acquired", [False, True])
def test_background_rbac_scanner_respects_advisory_lock_and_cancels_cleanly(
    monkeypatch, lock_acquired
):
    from app import admin_api, db, main_postgres

    session = ScannerSession(lock_acquired)
    monkeypatch.setattr(db, "AsyncSessionLocal", lambda: ScannerSessionContext(session))
    calls = []

    async def scan(session_arg, principal, request):
        calls.append((session_arg, principal.subject, request))
        return 2

    monkeypatch.setattr(admin_api, "_scan_rbac_discrepancies", scan)

    async def cancel_on_interval(_delay):
        raise asyncio.CancelledError

    monkeypatch.setattr(main_postgres.asyncio, "sleep", cancel_on_interval)
    monkeypatch.setenv("LIFELINK_RBAC_SCAN_SECONDS", "60")

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(main_postgres._scan_rbac_discrepancies_forever())

    assert "pg_try_advisory_xact_lock(728194620114)" in session.statements[0]
    if lock_acquired:
        assert len(calls) == 1
        assert calls[0][0] is session
        assert calls[0][1:] == ("system:rbac-scanner", None)
        assert "SET LOCAL statement_timeout = '15000ms'" in session.statements
        assert session.committed is True
        assert session.rolled_back is False
    else:
        assert calls == []
        assert session.rolled_back is True
        assert session.committed is False
