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
