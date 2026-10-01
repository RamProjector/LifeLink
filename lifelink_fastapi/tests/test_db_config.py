from app.db import async_database_url


def test_hosted_postgres_sslmode_is_converted_for_asyncpg(monkeypatch):
    monkeypatch.setenv(
        "LIFELINK_DATABASE_URL",
        "postgresql://user:password@db.example.test:5432/postgres?sslmode=require&application_name=lifelink",
    )
    assert async_database_url() == (
        "postgresql+asyncpg://user:password@db.example.test:5432/postgres?"
        "application_name=lifelink&ssl=require"
    )


def test_pooler_password_with_reserved_characters_is_percent_encoded(monkeypatch):
    # A Supabase pooler password containing '@' and '#' must be encoded, or the
    # URL parses the host as "ss" and the connection fails with "failed to connect".
    monkeypatch.setenv(
        "LIFELINK_DATABASE_URL",
        "postgresql://postgres.abcdefghijklmnop:p@ss#1@aws-0-ap-southeast-1.pooler.supabase.com:6543/postgres?sslmode=require",
    )
    assert async_database_url() == (
        "postgresql+asyncpg://postgres.abcdefghijklmnop:p%40ss%231@"
        "aws-0-ap-southeast-1.pooler.supabase.com:6543/postgres?ssl=require"
    )


def test_already_encoded_password_is_not_double_encoded(monkeypatch):
    monkeypatch.setenv(
        "LIFELINK_DATABASE_URL",
        "postgresql://postgres.abcdefghijklmnop:p%40ss%231@aws-0-ap-southeast-1.pooler.supabase.com:6543/postgres",
    )
    assert async_database_url() == (
        "postgresql+asyncpg://postgres.abcdefghijklmnop:p%40ss%231@"
        "aws-0-ap-southeast-1.pooler.supabase.com:6543/postgres"
    )


def test_password_without_reserved_characters_is_unchanged(monkeypatch):
    monkeypatch.setenv(
        "LIFELINK_DATABASE_URL",
        "postgresql://postgres.abcdefghijklmnop:simplepassword@aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres",
    )
    assert async_database_url() == (
        "postgresql+asyncpg://postgres.abcdefghijklmnop:simplepassword@"
        "aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres"
    )
