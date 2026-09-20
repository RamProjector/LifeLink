from datetime import datetime, timedelta, timezone

from app.expiry import is_request_expired


def test_open_request_expires_after_deadline():
    now = datetime(2026, 9, 20, 12, 0, tzinfo=timezone.utc)
    assert is_request_expired("awaiting_responses", now - timedelta(seconds=1), now)


def test_open_request_remains_active_before_deadline():
    now = datetime(2026, 9, 20, 12, 0, tzinfo=timezone.utc)
    assert not is_request_expired("awaiting_responses", now + timedelta(seconds=1), now)


def test_terminal_request_does_not_need_expiry_transition():
    now = datetime(2026, 9, 20, 12, 0, tzinfo=timezone.utc)
    for status in ("cancelled", "fulfilled", "expired"):
        assert not is_request_expired(status, now - timedelta(days=1), now)


def test_naive_database_deadline_is_treated_as_utc():
    now = datetime(2026, 9, 20, 12, 0, tzinfo=timezone.utc)
    assert is_request_expired("matching", datetime(2026, 9, 20, 11, 59), now)
