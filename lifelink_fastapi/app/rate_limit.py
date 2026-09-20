from __future__ import annotations

from collections import defaultdict, deque
from time import monotonic

from fastapi import HTTPException

_WINDOWS: dict[str, deque[float]] = defaultdict(deque)


def enforce_rate_limit(key: str, limit: int, window_seconds: int) -> None:
    now = monotonic()
    bucket = _WINDOWS[key]
    cutoff = now - window_seconds
    while bucket and bucket[0] <= cutoff:
        bucket.popleft()
    if len(bucket) >= limit:
        raise HTTPException(status_code=429, detail="Too many requests. Please wait and try again.")
    bucket.append(now)
