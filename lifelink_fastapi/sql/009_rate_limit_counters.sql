-- Shared fixed-window rate-limit counters.
--
-- The API previously rate-limited with an in-process dict, which is per-worker
-- and resets on every restart, so limits were effectively multiplied by the
-- worker count and lost on deploy. This table makes the counter shared across
-- all workers and durable across restarts. See app/rate_limit.py.

CREATE TABLE IF NOT EXISTS rate_limit_counters (
    bucket_key   VARCHAR(256) NOT NULL,
    window_start BIGINT       NOT NULL,
    hits         INTEGER      NOT NULL DEFAULT 0,
    PRIMARY KEY (bucket_key, window_start)
);

-- Supports the opportunistic cleanup of expired windows.
CREATE INDEX IF NOT EXISTS ix_rate_limit_counters_window_start
    ON rate_limit_counters (window_start);
