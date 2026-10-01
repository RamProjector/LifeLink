"""Fast, deterministic tests for expiry batches and their background lifecycle."""

import asyncio
from contextlib import nullcontext
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, call

import pytest
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app import db, main_postgres, repositories
from app.db_models import RequestStatusEnum
from app.repositories import MAX_SWEEP_LIMIT, SqlAlchemyRequestStore

NOW = datetime(2026, 10, 1, 12, tzinfo=UTC)


@pytest.fixture
def frozen_clock(monkeypatch):
    """Freeze the repository clock so deadline-boundary assertions are deterministic."""
    class FrozenDateTime(datetime):
        @classmethod
        def now(cls, tz=None):
            """Return the fixed instant in the requested zone, or as a naive UTC datetime."""
            return NOW.astimezone(tz) if tz else NOW.replace(tzinfo=None)

    monkeypatch.setattr(repositories, "datetime", FrozenDateTime)


@pytest.fixture
def session():
    """Provide an async session double with an empty scalar-query result by default."""
    session = AsyncMock(spec=AsyncSession)
    session.scalars.return_value = Mock()
    session.scalars.return_value.all.return_value = []
    return session


@pytest.mark.parametrize("status", ["matching", "awaiting_responses", "partially_fulfilled", "manual_broadcast"])
@pytest.mark.parametrize("plain_status", [False, True], ids=["enum", "string"])
def test_batch_expires_each_active_status_at_the_deadline(session, frozen_clock, status, plain_status):
    """Expire every active status at the exact deadline, accepting both enum and string values."""
    row = SimpleNamespace(
        status=status if plain_status else RequestStatusEnum(status),
        response_deadline=NOW,
        updated_at=NOW - timedelta(days=1),
    )
    session.scalars.return_value.all.return_value = [row]

    assert asyncio.run(SqlAlchemyRequestStore(session).expire_timed_out_requests_async()) == 1

    assert row.status is RequestStatusEnum.EXPIRED
    assert row.updated_at == NOW
    session.commit.assert_awaited_once_with()


def test_batch_counts_only_expired_rows_and_commits_once(session, frozen_clock):
    """Count and timestamp only eligible transitions, then commit the batch once."""
    previous_update = NOW - timedelta(days=1)
    rows = [
        SimpleNamespace(status=status, response_deadline=deadline, updated_at=previous_update)
        for status, deadline in [
            ("matching", NOW - timedelta(seconds=1)),
            ("awaiting_responses", NOW.replace(tzinfo=None)),
            ("manual_broadcast", NOW + timedelta(microseconds=1)),
            ("cancelled", NOW - timedelta(days=1)),
            ("fulfilled", NOW - timedelta(days=1)),
            ("expired", NOW - timedelta(days=1)),
            ("draft", NOW - timedelta(days=1)),
        ]
    ]
    original_statuses = [row.status for row in rows]
    session.scalars.return_value.all.return_value = rows

    assert asyncio.run(SqlAlchemyRequestStore(session).expire_timed_out_requests_async()) == 2

    assert [row.status for row in rows[:2]] == [RequestStatusEnum.EXPIRED] * 2
    assert [row.updated_at for row in rows[:2]] == [NOW, NOW]
    assert [row.status for row in rows[2:]] == original_statuses[2:]
    assert all(row.updated_at == previous_update for row in rows[2:])
    session.commit.assert_awaited_once_with()


@pytest.mark.parametrize("has_row", [False, True], ids=["empty-query", "no-expiry-transition"])
def test_batch_does_not_commit_without_a_transition(session, frozen_clock, has_row):
    """Avoid committing when the query is empty or its rows require no expiry transition."""
    if has_row:
        session.scalars.return_value.all.return_value = [
            SimpleNamespace(status="fulfilled", response_deadline=NOW, updated_at=NOW)
        ]

    assert asyncio.run(SqlAlchemyRequestStore(session).expire_timed_out_requests_async()) == 0

    session.commit.assert_not_awaited()


@pytest.mark.parametrize("limit, expected", [(None, 500), (-10, 1), (0, 1), (1, 1), (23, 23), (5000, 5000), (10**9, MAX_SWEEP_LIMIT)])
def test_batch_bounds_the_database_query_before_loading_rows(session, limit, expected):
    """Verify the normalized limit is applied in SQL before any backlog is materialized."""
    kwargs = {} if limit is None else {"limit": limit}
    asyncio.run(SqlAlchemyRequestStore(session).expire_timed_out_requests_async(**kwargs))

    statement = session.scalars.await_args.args[0]
    # Check the bound sent to SQL, not just the returned count: limiting after
    # materializing rows would still load an unbounded backlog into memory.
    query = str(statement.compile(compile_kwargs={"literal_binds": True}))
    assert query.endswith(f"LIMIT {expected}")


@pytest.mark.parametrize("operation", ["scalars", "commit"])
def test_batch_propagates_database_failures_to_the_retry_loop(session, frozen_clock, operation):
    """Let query and commit failures escape the store so the background loop can retry."""
    session.scalars.return_value.all.return_value = [
        SimpleNamespace(status="matching", response_deadline=NOW, updated_at=NOW)
    ]
    getattr(session, operation).side_effect = SQLAlchemyError("database unavailable")

    with pytest.raises(SQLAlchemyError, match="database unavailable"):
        asyncio.run(SqlAlchemyRequestStore(session).expire_timed_out_requests_async())
    if operation == "scalars":
        session.commit.assert_not_awaited()


@pytest.fixture
def sweep_dependencies(monkeypatch):
    """Replace sessions, the store, and sleep with doubles that cancel after one sweep by default."""
    monkeypatch.delenv("LIFELINK_REQUEST_SWEEP_SECONDS", raising=False)
    monkeypatch.delenv("LIFELINK_REQUEST_SWEEP_LIMIT", raising=False)
    context = AsyncMock()
    factory = Mock(return_value=context)
    sweep = AsyncMock(return_value=0)
    store = Mock(return_value=SimpleNamespace(expire_timed_out_requests_async=sweep))
    sleep = AsyncMock(side_effect=asyncio.CancelledError)
    monkeypatch.setattr(db, "AsyncSessionLocal", factory)
    monkeypatch.setattr(main_postgres, "SqlAlchemyRequestStore", store)
    monkeypatch.setattr(main_postgres.asyncio, "sleep", sleep)
    return SimpleNamespace(factory=factory, context=context, store=store, sweep=sweep, sleep=sleep)


@pytest.mark.parametrize("configured, expected", [(None, 500), ("17", 17), ("invalid", 500), ("1.5", 500), ("0", 1), ("-9", 1)])
def test_sweeper_sweeps_immediately_and_normalizes_batch_configuration(monkeypatch, sweep_dependencies, configured, expected):
    """Check immediate execution, batch-setting normalization, and the default wait interval."""
    deps = sweep_dependencies
    if configured is not None:
        monkeypatch.setenv("LIFELINK_REQUEST_SWEEP_LIMIT", configured)

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(main_postgres._expire_timed_out_requests_forever())

    deps.sweep.assert_awaited_once_with(limit=expected)
    deps.store.assert_called_once_with(deps.context.__aenter__.return_value)
    deps.context.__aexit__.assert_awaited_once_with(None, None, None)
    deps.sleep.assert_awaited_once_with(300.0)


def test_sweeper_uses_configured_interval_and_a_fresh_session_each_tick(monkeypatch, sweep_dependencies):
    """Run two ticks to verify each creates a session and waits the configured interval."""
    deps = sweep_dependencies
    monkeypatch.setenv("LIFELINK_REQUEST_SWEEP_SECONDS", "2.5")
    deps.sleep.side_effect = [None, asyncio.CancelledError]

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(main_postgres._expire_timed_out_requests_forever())

    assert deps.factory.call_count == 2
    assert deps.context.__aexit__.await_count == 2
    assert deps.sweep.await_count == 2
    assert deps.sleep.await_args_list == [call(2.5), call(2.5)]


@pytest.mark.parametrize("failure_point", ["session-entry", "sweep"])
def test_sweeper_retries_after_database_failure(sweep_dependencies, caplog, failure_point):
    """Verify session-entry and sweep failures are logged and retried on the next tick."""
    deps = sweep_dependencies
    if failure_point == "session-entry":
        deps.context.__aenter__.side_effect = [SQLAlchemyError("offline"), Mock()]
    else:
        deps.sweep.side_effect = [SQLAlchemyError("offline"), 1]
    deps.sleep.side_effect = [None, asyncio.CancelledError]

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(main_postgres._expire_timed_out_requests_forever())

    assert deps.factory.call_count == 2
    assert deps.sweep.await_count == (1 if failure_point == "session-entry" else 2)
    assert deps.sleep.await_args_list == [call(300.0), call(300.0)]
    assert "Request-expiry sweep failed" in caplog.text


def test_sweeper_cancellation_closes_session_without_retrying(sweep_dependencies, caplog):
    """Propagate cancellation after closing the session, without logging a failure or waiting."""
    deps = sweep_dependencies
    deps.sweep.side_effect = asyncio.CancelledError

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(main_postgres._expire_timed_out_requests_forever())

    deps.factory.assert_called_once_with()
    deps.context.__aexit__.assert_awaited_once()
    assert deps.context.__aexit__.await_args.args[0] is asyncio.CancelledError
    deps.sleep.assert_not_awaited()
    assert "Request-expiry sweep failed" not in caplog.text


@pytest.mark.parametrize("exceptional_exit", [False, True])
def test_lifespan_initializes_then_starts_and_awaits_both_sweepers(monkeypatch, exceptional_exit):
    """Verify initialization precedes both workers and all cleanup is awaited on either exit path."""
    async def scenario():
        """Exercise lifespan startup and cancellation within a single event loop."""
        initialized = False
        started = [asyncio.Event(), asyncio.Event()]
        stopped = [False, False]
        tasks = []

        async def initialize():
            """Record successful table initialization before either background worker starts."""
            nonlocal initialized
            initialized = True

        async def worker(index):
            """Signal startup and defer shutdown completion until asynchronous cleanup has run."""
            assert initialized
            tasks.append(asyncio.current_task())
            started[index].set()
            try:
                await asyncio.Future()
            finally:
                # Cleanup deliberately yields: cancel() alone is insufficient.
                await asyncio.sleep(0)
                stopped[index] = True

        monkeypatch.setattr(main_postgres, "create_all_tables", initialize)
        monkeypatch.setattr(main_postgres, "_expire_stale_location_shares_forever", lambda: worker(0))
        monkeypatch.setattr(main_postgres, "_expire_timed_out_requests_forever", lambda: worker(1))
        expected_exit = pytest.raises(RuntimeError, match="application failure") if exceptional_exit else nullcontext()
        with expected_exit:
            async with main_postgres.lifespan(main_postgres.app):
                await asyncio.wait_for(asyncio.gather(*(event.wait() for event in started)), timeout=1)
                assert stopped == [False, False]
                if exceptional_exit:
                    raise RuntimeError("application failure")
        assert stopped == [True, True]
        assert len(tasks) == 2
        assert all(task.done() and task.cancelled() for task in tasks)

    asyncio.run(scenario())


def test_lifespan_does_not_start_sweepers_when_table_creation_fails(monkeypatch):
    """Prevent both background workers from starting if database initialization fails."""
    location_sweep = AsyncMock()
    request_sweep = AsyncMock()
    monkeypatch.setattr(main_postgres, "create_all_tables", AsyncMock(side_effect=SQLAlchemyError("setup failed")))
    monkeypatch.setattr(main_postgres, "_expire_stale_location_shares_forever", location_sweep)
    monkeypatch.setattr(main_postgres, "_expire_timed_out_requests_forever", request_sweep)

    async def scenario():
        """Enter the lifespan context and fail if table initialization unexpectedly succeeds."""
        async with main_postgres.lifespan(main_postgres.app):
            pytest.fail("The application must not start without its tables")

    with pytest.raises(SQLAlchemyError, match="setup failed"):
        asyncio.run(scenario())
    location_sweep.assert_not_called()
    request_sweep.assert_not_called()
