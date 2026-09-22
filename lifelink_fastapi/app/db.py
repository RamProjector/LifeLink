from __future__ import annotations

import os
from collections.abc import AsyncGenerator
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

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
    parts = urlsplit(url)
    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    if query.pop("sslmode", None) == "require":
        query["ssl"] = "require"
    return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))


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
        await connection.execute(text(
            "ALTER TABLE lifelink_profiles ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(4096)"
        ))
        await connection.execute(text(
            "ALTER TABLE donors "
            "ADD COLUMN IF NOT EXISTS donor_note TEXT NOT NULL DEFAULT '', "
            "ADD COLUMN IF NOT EXISTS preferred_contact_method VARCHAR(32) NOT NULL DEFAULT 'in_app', "
            "ADD COLUMN IF NOT EXISTS pause_reason VARCHAR(240), "
            "ADD COLUMN IF NOT EXISTS profile_visible BOOLEAN NOT NULL DEFAULT TRUE"
        ))
        await connection.execute(text(
            "ALTER TABLE lifelink_profiles "
            "ADD COLUMN IF NOT EXISTS can_request BOOLEAN NOT NULL DEFAULT TRUE, "
            "ADD COLUMN IF NOT EXISTS can_donate BOOLEAN NOT NULL DEFAULT FALSE"
        ))
        await connection.execute(text(
            "UPDATE lifelink_profiles SET can_donate = TRUE WHERE role = 'donor' AND can_donate = FALSE"
        ))
