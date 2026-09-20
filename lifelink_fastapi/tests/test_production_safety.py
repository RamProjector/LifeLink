from fastapi import HTTPException

from app.rate_limit import enforce_rate_limit


def test_rate_limit_rejects_after_threshold():
    key = "test-production-safety"
    for _ in range(2):
        enforce_rate_limit(key, 2, 300)
    try:
        enforce_rate_limit(key, 2, 300)
    except HTTPException as exc:
        assert exc.status_code == 429
    else:
        raise AssertionError("Expected the third request to be rate limited")
