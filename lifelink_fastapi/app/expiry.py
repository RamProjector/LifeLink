from __future__ import annotations

from datetime import datetime, timezone

ACTIVE_REQUEST_STATUSES = frozenset({
    "matching",
    "awaiting_responses",
    "partially_fulfilled",
    "manual_broadcast",
})


def is_request_expired(status: str, deadline: datetime, now: datetime | None = None) -> bool:
    """Return true only for open requests whose response deadline has passed."""
    current = now or datetime.now(timezone.utc)
    if deadline.tzinfo is None:
        deadline = deadline.replace(tzinfo=timezone.utc)
    return status in ACTIVE_REQUEST_STATUSES and deadline <= current
