"""Edge-case tests for the Supabase pooler connection-string handling.

These strengthen the fix in ``app/db.py``: a pooler password containing
URL-reserved characters must be percent-encoded *before* the URL is parsed, so
a ``#`` (fragment delimiter) or ``/`` cannot truncate the host and produce the
"failed to connect" error. The encoding must also be idempotent so an
already-encoded password is never double-encoded.
"""

from urllib.parse import urlsplit

import pytest

from app.db import _encode_password, async_database_url

POOLER_HOST = "aws-0-ap-southeast-1.pooler.supabase.com"
POOLER_USER = "postgres.abcdefghijklmnop"


def _pooler_url(password: str, port: int = 6543) -> str:
    return f"postgresql://{POOLER_USER}:{password}@{POOLER_HOST}:{port}/postgres"


# --- _encode_password: reserved characters -----------------------------------


@pytest.mark.parametrize(
    ("raw", "encoded"),
    [
        ("p@ss", "p%40ss"),
        ("p#ss", "p%23ss"),
        ("p/ss", "p%2Fss"),
        ("p:ss", "p%3Ass"),
        ("p?ss", "p%3Fss"),
        ("p&ss", "p%26ss"),
        ("p%ss", "p%25ss"),
        ("a@b#c/d:e?f&g%h", "a%40b%23c%2Fd%3Ae%3Ff%26g%25h"),
    ],
)
def test_every_reserved_character_is_percent_encoded(raw, encoded):
    assert _encode_password(_pooler_url(raw)) == _pooler_url(encoded)


def test_already_encoded_password_is_not_double_encoded():
    # %40 = '@', %23 = '#'. Decoding then re-encoding must be a no-op.
    assert _encode_password(_pooler_url("p%40ss%23x")) == _pooler_url("p%40ss%23x")


def test_plain_alphanumeric_password_is_unchanged():
    assert _encode_password(_pooler_url("SimplePassword123")) == _pooler_url("SimplePassword123")


def test_empty_password_is_unchanged():
    assert _encode_password(_pooler_url("")) == _pooler_url("")


def test_user_without_password_is_unchanged():
    url = f"postgresql://{POOLER_USER}@{POOLER_HOST}:6543/postgres"
    assert _encode_password(url) == url


def test_url_without_userinfo_is_unchanged():
    url = f"postgresql://{POOLER_HOST}:5432/postgres"
    assert _encode_password(url) == url


def test_non_url_string_is_unchanged():
    assert _encode_password("not-a-url") == "not-a-url"


# --- async_database_url: end-to-end parsing ----------------------------------


def test_hash_in_password_does_not_truncate_host(monkeypatch):
    # Without encoding, urlsplit sees the '#' as a fragment delimiter and the
    # host parses as garbage ("ss"), which is the "failed to connect" failure.
    monkeypatch.setenv("LIFELINK_DATABASE_URL", _pooler_url("p#ss"))
    result = async_database_url()
    parts = urlsplit(result)
    assert parts.hostname == POOLER_HOST
    assert parts.port == 6543
    assert parts.username == POOLER_USER
    assert parts.password == "p%23ss"
    assert parts.fragment == ""


def test_slash_in_password_does_not_truncate_path(monkeypatch):
    monkeypatch.setenv("LIFELINK_DATABASE_URL", _pooler_url("p/ss"))
    parts = urlsplit(async_database_url())
    assert parts.hostname == POOLER_HOST
    assert parts.port == 6543
    assert parts.username == POOLER_USER
    assert parts.path == "/postgres"


def test_pooler_host_port_and_user_are_preserved(monkeypatch):
    monkeypatch.setenv("LIFELINK_DATABASE_URL", _pooler_url("p@ss#1"))
    parts = urlsplit(async_database_url())
    assert parts.scheme == "postgresql+asyncpg"
    assert parts.hostname == POOLER_HOST
    assert parts.port == 6543
    assert parts.username == POOLER_USER
    assert parts.password == "p%40ss%231"


def test_ampersand_and_question_mark_do_not_become_query_params(monkeypatch):
    monkeypatch.setenv("LIFELINK_DATABASE_URL", _pooler_url("a&b?c"))
    result = async_database_url()
    parts = urlsplit(result)
    assert parts.hostname == POOLER_HOST
    assert parts.query == ""
    assert parts.password == "a%26b%3Fc"


def test_sslmode_conversion_still_applies_with_encoded_password(monkeypatch):
    monkeypatch.setenv("LIFELINK_DATABASE_URL", _pooler_url("p@ss#1") + "?sslmode=require")
    result = async_database_url()
    parts = urlsplit(result)
    assert parts.hostname == POOLER_HOST
    assert parts.password == "p%40ss%231"
    assert "ssl=require" in parts.query
    assert "sslmode" not in parts.query


def test_session_pooler_port_5432_is_preserved(monkeypatch):
    monkeypatch.setenv("LIFELINK_DATABASE_URL", _pooler_url("p@ss", port=5432))
    parts = urlsplit(async_database_url())
    assert parts.port == 5432
    assert parts.hostname == POOLER_HOST
