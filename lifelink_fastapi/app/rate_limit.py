from __future__ import annotations

import os
from collections import defaultdict, deque
from time import monotonic

from fastapi import HTTPException

# In-process fallback. Used only when no shared store is configured (local
# development, unit tests). It is per-worker and resets on restart, so it is
# NOT production-safe on its own.
_WINDOWS: dict[str, deque[float]] = defaultdict(deque)


def _shared_store_configured() -> bool:
    return bool(
        os.getenv("LIFELINK_RATE_LIMIT_DATABASE_URL")
        or os.getenv("LIFELINK_DATABASE_URL")
        or os.getenv("DATABASE_URL")
    )


def _enforce_in_process(key: str, limit: int, window_seconds: int) -> None:
    now = monotonic()
    bucket = _WINDOWS[key]
    cutoff = now - window_seconds
    while bucket and bucket[0] <= cutoff:
        bucket.popleft()
    if len(bucket) >= limit:
        raise HTTPException(status_code=429, detail="Too many requests. Please wait and try again.")
    bucket.append(now)


async def _enforce_shared(key: str, limit: int, window_seconds: int) -> None:
    """Fixed-window counter in PostgreSQL, shared across every worker.

    A single atomic upsert increments the counter for the current window and
    returns the new value, so concurrent workers cannot each keep their own
    tally. The row is keyed by (key, window_start) and old windows are pruned
    opportunistically.
    """
    from sqlalchemy import text

    from .db import engine

    window_start = int(monotonic() // window_seconds) * window_seconds
    statement = text(
        """
        INSERT INTO rate_limit_counters (bucket_key, window_start, hits)
        VALUES (:key, :window_start, 1)
        ON CONFLICT (bucket_key, window_start)
        DO UPDATE SET hits = rate_limit_counters.hits + 1
        RETURNING hits
        """
    )
    async with engine.connect() as connection:
        hits = (
            await connection.execute(statement, {"key": key, "window_start": window_start})
        ).scalar_one()
        # Opportunistic cleanup keeps the table small without a background job.
        await connection.execute(
            text("DELETE FROM rate_limit_counters WHERE window_start < :cutoff"),
            {"cutoff": window_start - (window_seconds * 4)},
        )
        await connection.commit()
    if hits > limit:
        raise HTTPException(status_code=429, detail="Too many requests. Please wait and try again.")


async def enforce_rate_limit(key: str, limit: int, window_seconds: int) -> None:
    if _shared_store_configured():
        try:
            await _enforce_shared(key, limit, window_seconds)
            return
        except HTTPException:
            raise
        except Exception:
            # A rate-limit store outage must not take the API down. Fall back to
            # the in-process limiter so requests still get some protection.
            _enforce_in_process(key, limit, window_seconds)
            return
    _enforce_in_process(key, limit, window_seconds)
