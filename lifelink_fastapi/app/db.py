from __future__ import annotations

import os
from collections.abc import AsyncGenerator
from urllib.parse import parse_qsl, quote, unquote, urlencode, urlsplit, urlunsplit

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from .db_models import Base


def async_database_url() -> str:
    # Prefer an explicit LifeLink/PostgreSQL URL. Some hosting environments
    # expose DATABASE_URL for a different database engine, so do not pass a
    # MySQL URL to the asyncpg dialect by accident.
    url = os.getenv("LIFELINK_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not url or not (url.startswith("postgresql://") or url.startswith("postgres://") or url.startswith("postgresql+asyncpg://")):
        url = "postgresql+asyncpg://lifelink:lifelink@localhost:5432/lifelink"
    if url.startswith("postgresql://"):
        url = url.replace("postgresql://", "postgresql+asyncpg://", 1)
    elif url.startswith("postgres://"):
        url = url.replace("postgres://", "postgresql+asyncpg://", 1)

    # Supabase and several hosted PostgreSQL providers document URLs with
    # sslmode=require. asyncpg expects the equivalent ssl=require parameter.
    url = _encode_password(url)
    parts = urlsplit(url)
    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    if query.pop("sslmode", None) == "require":
        query["ssl"] = "require"
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))


def _encode_password(url: str) -> str:
    """Percent-encode a raw password in a connection string.

    Supabase pooler passwords are frequently generated with URL-reserved
    characters such as ``@``, ``#``, ``/``, ``:`` or ``?``. Pasted raw into a
    connection string they break the URL: ``postgresql://postgres.abc:p@ss#1@
    aws-0-region.pooler.supabase.com:6543/postgres`` is truncated at the ``#``
    (a fragment delimiter) and the host parses as ``ss``, so the connection
    fails with "failed to connect". The password must be percent-encoded.

    The password is the text between the first ``:`` after the scheme and the
    LAST ``@`` before the host. Encoding happens on the raw string, before
    ``urlsplit``, so a ``#`` or ``/`` inside the password cannot truncate the
    URL first. An already-encoded password is decoded first so the operation is
    idempotent and never double-encodes a ``%``.
    """
    scheme, sep, rest = url.partition("://")
    if not sep:
        return url
    at = rest.rfind("@")
    if at == -1:
        return url
    userinfo = rest[:at]
    remainder = rest[at + 1:]
    if ":" not in userinfo:
        return url
    user, _, password = userinfo.partition(":")
    if not password:
        return url
    return f"{scheme}://{user}:{quote(unquote(password), safe='')}@{remainder}"


engine = create_async_engine(
    async_database_url(),
    pool_pre_ping=True,
    pool_size=int(os.getenv("DB_POOL_SIZE", "5")),
    max_overflow=int(os.getenv("DB_MAX_OVERFLOW", "10")),
)

AsyncSessionLocal = async_sessionmaker(
    bind=engine,
    class_=AsyncSession,
    expire_on_commit=False,
    autoflush=False,
)


async def get_db_session() -> AsyncGenerator[AsyncSession, None]:
    async with AsyncSessionLocal() as session:
        yield session


async def create_all_tables() -> None:
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
        # Index the RBAC assignment actor foreign key. Guard the startup path
        # because a fresh database may not have applied the RBAC registry yet.
        rbac_user_roles_exists = await connection.scalar(text(
            "SELECT to_regclass('public.lifelink_user_roles') IS NOT NULL"
        ))
        if rbac_user_roles_exists:
            await connection.execute(text(
                "CREATE INDEX IF NOT EXISTS ix_lifelink_user_roles_assigned_by "
                "ON public.lifelink_user_roles (assigned_by)"
            ))
        # create_all() creates new tables but does not add columns to tables that
        # already exist. Keep the requester location-source upgrade here as well
        # as sql/014_requester_location_source.sql so existing Render databases
        # receive it during the normal API startup path.
        await connection.execute(text(
            "ALTER TABLE emergency_requests "
            "ADD COLUMN IF NOT EXISTS requester_location_source VARCHAR(32) "
            "NOT NULL DEFAULT 'unknown'"
        ))
        request_source_constraint_exists = await connection.scalar(text(
            "SELECT EXISTS (SELECT 1 FROM pg_constraint "
            "WHERE conname = 'ck_emergency_requests_requester_location_source' "
            "AND conrelid = 'emergency_requests'::regclass)"
        ))
        if not request_source_constraint_exists:
            await connection.execute(text(
                "ALTER TABLE emergency_requests "
                "ADD CONSTRAINT ck_emergency_requests_requester_location_source "
                "CHECK (requester_location_source IN "
                "('fused', 'gps', 'network', 'passive', 'unknown', "
                "'manual_pin', 'manual_coordinates', 'facility'))"
            ))
        await connection.execute(text(
            "ALTER TABLE lifelink_profiles ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(4096)"
        ))
        await connection.execute(text(
            "ALTER TABLE donors "
            "ADD COLUMN IF NOT EXISTS user_id VARCHAR(128), "
            "ADD COLUMN IF NOT EXISTS last_location_precision_meters INTEGER NOT NULL DEFAULT 500, "
            "ADD COLUMN IF NOT EXISTS donor_note TEXT NOT NULL DEFAULT '', "
            "ADD COLUMN IF NOT EXISTS preferred_contact_method VARCHAR(32) NOT NULL DEFAULT 'in_app', "
            "ADD COLUMN IF NOT EXISTS pause_reason VARCHAR(240), "
            "ADD COLUMN IF NOT EXISTS profile_visible BOOLEAN NOT NULL DEFAULT TRUE, "
            "ADD COLUMN IF NOT EXISTS map_visible BOOLEAN NOT NULL DEFAULT FALSE, "
            "ADD COLUMN IF NOT EXISTS map_visibility_updated_at TIMESTAMPTZ, "
            "ADD COLUMN IF NOT EXISTS exact_location_sharing_enabled BOOLEAN NOT NULL DEFAULT FALSE, "
            "ADD COLUMN IF NOT EXISTS last_presence_at TIMESTAMPTZ, "
            "ADD COLUMN IF NOT EXISTS last_location_provider VARCHAR(24)"
        ))
        provider_constraint_exists = await connection.scalar(text(
            "SELECT EXISTS (SELECT 1 FROM pg_constraint "
            "WHERE conname = 'ck_donors_last_location_provider')"
        ))
        if not provider_constraint_exists:
            await connection.execute(text(
                "ALTER TABLE donors ADD CONSTRAINT ck_donors_last_location_provider "
                "CHECK (last_location_provider IS NULL OR "
                "last_location_provider IN ('fused', 'gps', 'network', 'passive', 'unknown'))"
            ))
        # Legacy availability timestamps are not evidence of a GPS fix. Keep
        # last_presence_at NULL until the server records an actual presence update.
        await connection.execute(text(
            "CREATE INDEX IF NOT EXISTS ix_donors_last_presence_at ON donors (last_presence_at)"
        ))
        await connection.execute(text(
            "ALTER TABLE lifelink_profiles "
            "ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(4096), "
            "ADD COLUMN IF NOT EXISTS can_request BOOLEAN NOT NULL DEFAULT TRUE, "
            "ADD COLUMN IF NOT EXISTS can_donate BOOLEAN NOT NULL DEFAULT FALSE"
        ))
        await connection.execute(text(
            "UPDATE lifelink_profiles SET can_donate = TRUE WHERE role = 'donor' AND can_donate = FALSE"
        ))
        # Admin API audit queries require these additive columns. Keep this
        # idempotent startup migration aligned with sql/011_admin_audit_metadata.sql
        # because this service currently performs schema upgrades during startup.
        await connection.execute(text(
            "ALTER TABLE audit_events "
            "ADD COLUMN IF NOT EXISTS outcome VARCHAR(16) NOT NULL DEFAULT 'success', "
            "ADD COLUMN IF NOT EXISTS target_type VARCHAR(64), "
            "ADD COLUMN IF NOT EXISTS target_id VARCHAR(128), "
            "ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(80)"
        ))
        await connection.execute(text(
            "CREATE INDEX IF NOT EXISTS ix_audit_events_created_at "
            "ON audit_events (created_at DESC)"
        ))
        await connection.execute(text(
            "CREATE INDEX IF NOT EXISTS ix_audit_events_action_created_at "
            "ON audit_events (action, created_at DESC)"
        ))
        # Add the required IDS/IPS audit context while preserving the existing
        # audit_events identifiers and query compatibility.
        await connection.execute(text(
            "ALTER TABLE audit_events "
            "ADD COLUMN IF NOT EXISTS log_id UUID, "
            "ADD COLUMN IF NOT EXISTS actions VARCHAR(16) NOT NULL DEFAULT 'OTHER', "
            "ADD COLUMN IF NOT EXISTS description TEXT NOT NULL DEFAULT '', "
            "ADD COLUMN IF NOT EXISTS session_id VARCHAR(128), "
            "ADD COLUMN IF NOT EXISTS ip_address VARCHAR(64), "
            "ADD COLUMN IF NOT EXISTS timestamp TIMESTAMPTZ, "
            "ADD COLUMN IF NOT EXISTS device_used VARCHAR(255) NOT NULL DEFAULT 'unknown'"
        ))
        await connection.execute(text(
            "UPDATE audit_events SET log_id = gen_random_uuid() WHERE log_id IS NULL"
        ))
        await connection.execute(text(
            "UPDATE audit_events SET timestamp = created_at WHERE timestamp IS NULL"
        ))
        await connection.execute(text(
            "ALTER TABLE audit_events "
            "ALTER COLUMN log_id SET NOT NULL, "
            "ALTER COLUMN timestamp SET DEFAULT now(), "
            "ALTER COLUMN timestamp SET NOT NULL"
        ))
        await connection.execute(text(
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_audit_events_log_id ON audit_events (log_id)"
        ))
        await connection.execute(text(
            "CREATE INDEX IF NOT EXISTS ix_audit_events_session_timestamp "
            "ON audit_events (session_id, timestamp DESC)"
        ))
        await connection.execute(text(
            "CREATE INDEX IF NOT EXISTS ix_audit_events_actor_timestamp "
            "ON audit_events (actor_id, timestamp DESC)"
        ))
        # Server-observed sessions support administrator session inventory and
        # immediate application-level revocation on protected admin endpoints.
        await connection.execute(text(
            "CREATE TABLE IF NOT EXISTS lifelink_user_sessions ("
            "session_id VARCHAR(128) PRIMARY KEY, "
            "user_id VARCHAR(128) NOT NULL, "
            "device_used VARCHAR(255) NOT NULL DEFAULT 'unknown', "
            "ip_address VARCHAR(64), "
            "created_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
            "last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
            "expires_at TIMESTAMPTZ, "
            "revoked_at TIMESTAMPTZ, "
            "revoked_by VARCHAR(128), "
            "status VARCHAR(16) NOT NULL DEFAULT 'active' "
            "CHECK (status IN ('active', 'revoked', 'expired')))"
        ))
        await connection.execute(text(
            "CREATE INDEX IF NOT EXISTS ix_lifelink_user_sessions_user_seen "
            "ON lifelink_user_sessions (user_id, last_seen_at DESC)"
        ))
        await connection.execute(text(
            "CREATE INDEX IF NOT EXISTS ix_lifelink_user_sessions_status_seen "
            "ON lifelink_user_sessions (status, last_seen_at DESC)"
        ))
        await connection.execute(text(
            "CREATE TABLE IF NOT EXISTS lifelink_security_alerts ("
            "id UUID PRIMARY KEY DEFAULT gen_random_uuid(), "
            "rule_id VARCHAR(64) NOT NULL, "
            "severity VARCHAR(16) NOT NULL CHECK (severity IN ('critical', 'high', 'medium', 'low')), "
            "status VARCHAR(20) NOT NULL DEFAULT 'open' CHECK "
            "(status IN ('open', 'acknowledged', 'investigating', 'resolved', 'false_positive')), "
            "user_id VARCHAR(128) NOT NULL, "
            "session_id VARCHAR(128), "
            "summary TEXT NOT NULL, "
            "evidence JSONB NOT NULL DEFAULT '{}'::jsonb, "
            "occurrences INTEGER NOT NULL DEFAULT 1, "
            "created_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
            "last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
            "acknowledged_at TIMESTAMPTZ, "
            "acknowledged_by VARCHAR(128), "
            "resolved_at TIMESTAMPTZ, "
            "resolved_by VARCHAR(128), "
            "correlation_id VARCHAR(80))"
        ))
        await connection.execute(text(
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_lifelink_security_alerts_open_rule_user "
            "ON lifelink_security_alerts (rule_id, user_id) "
            "WHERE status IN ('open', 'acknowledged', 'investigating')"
        ))
        await connection.execute(text(
            "CREATE INDEX IF NOT EXISTS ix_lifelink_security_alerts_status_created "
            "ON lifelink_security_alerts (status, created_at DESC)"
        ))
        # Role-scoped RBAC-03 findings remain separate from user-scoped alerts.
        # Keep startup schema creation aligned with migration 021 for idempotent deploys.
        await connection.execute(text(
            "CREATE TABLE IF NOT EXISTS lifelink_role_security_findings ("
            "id UUID PRIMARY KEY DEFAULT gen_random_uuid(), "
            "rule_id VARCHAR(64) NOT NULL, "
            "role_key VARCHAR(64) NOT NULL, "
            "severity VARCHAR(16) NOT NULL CHECK (severity IN ('critical', 'high', 'medium', 'low')), "
            "status VARCHAR(20) NOT NULL DEFAULT 'open' CHECK "
            "(status IN ('open', 'acknowledged', 'investigating', 'resolved', 'false_positive')), "
            "summary TEXT NOT NULL, "
            "evidence JSONB NOT NULL DEFAULT '{}'::jsonb, "
            "occurrences INTEGER NOT NULL DEFAULT 1 CHECK (occurrences >= 1), "
            "created_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
            "last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
            "resolved_at TIMESTAMPTZ, "
            "correlation_id VARCHAR(80))"
        ))
        await connection.execute(text(
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_lifelink_role_security_findings_open_rule_role "
            "ON lifelink_role_security_findings (rule_id, role_key) "
            "WHERE status IN ('open', 'acknowledged', 'investigating')"
        ))
        await connection.execute(text(
            "CREATE INDEX IF NOT EXISTS ix_lifelink_role_security_findings_status_created "
            "ON lifelink_role_security_findings (status, created_at DESC)"
        ))
        await connection.execute(text(
            "ALTER TABLE lifelink_role_security_findings ENABLE ROW LEVEL SECURITY"
        ))
        await connection.execute(text(
            "REVOKE ALL ON TABLE lifelink_role_security_findings FROM PUBLIC"
        ))
        if await connection.scalar(text("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon')")):
            await connection.execute(text(
                "REVOKE ALL ON TABLE lifelink_role_security_findings FROM anon"
            ))
        if await connection.scalar(text("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated')")):
            await connection.execute(text(
                "REVOKE ALL ON TABLE lifelink_role_security_findings FROM authenticated"
            ))
        # Persist scanner health independently of process logs so administrators
        # can distinguish a healthy zero-finding scan from a scanner that stopped.
        await connection.execute(text(
            "CREATE TABLE IF NOT EXISTS lifelink_security_scanner_runs ("
            "scanner_key VARCHAR(64) PRIMARY KEY, "
            "status VARCHAR(16) NOT NULL DEFAULT 'never_run' CHECK "
            "(status IN ('never_run', 'running', 'succeeded', 'failed')), "
            "last_started_at TIMESTAMPTZ, "
            "last_finished_at TIMESTAMPTZ, "
            "last_succeeded_at TIMESTAMPTZ, "
            "last_failed_at TIMESTAMPTZ, "
            "last_findings INTEGER NOT NULL DEFAULT 0 CHECK (last_findings >= 0), "
            "last_duration_ms INTEGER CHECK (last_duration_ms IS NULL OR last_duration_ms >= 0), "
            "consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0), "
            "last_error_code VARCHAR(64), "
            "updated_at TIMESTAMPTZ NOT NULL DEFAULT now())"
        ))
        await connection.execute(text(
            "CREATE INDEX IF NOT EXISTS ix_lifelink_security_scanner_runs_status "
            "ON lifelink_security_scanner_runs (status, updated_at DESC)"
        ))
        await connection.execute(text(
            "ALTER TABLE lifelink_security_scanner_runs ENABLE ROW LEVEL SECURITY"
        ))
        await connection.execute(text(
            "REVOKE ALL ON TABLE lifelink_security_scanner_runs FROM PUBLIC"
        ))
        if await connection.scalar(text("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon')")):
            await connection.execute(text(
                "REVOKE ALL ON TABLE lifelink_security_scanner_runs FROM anon"
            ))
        if await connection.scalar(text("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated')")):
            await connection.execute(text(
                "REVOKE ALL ON TABLE lifelink_security_scanner_runs FROM authenticated"
            ))
