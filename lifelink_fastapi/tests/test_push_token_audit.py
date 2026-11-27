from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest

from app.security import Principal


class FakeProfile:
    def __init__(self, fcm_token: str | None):
        self.fcm_token = fcm_token


class FakeSession:
    def __init__(self, profile: FakeProfile):
        self.profile = profile
        self.committed = False

    async def get(self, model, key):
        return self.profile

    async def commit(self):
        self.committed = True


@pytest.mark.parametrize(
    ("existing_token", "expected_action"),
    [
        (None, "api.added.push_token"),
        ("previous-secret-token", "api.updated.push_token"),
    ],
)
def test_push_token_registration_is_audited_without_recording_token(
    monkeypatch, existing_token, expected_action
):
    from app import main_postgres

    profile = FakeProfile(existing_token)
    session = FakeSession(profile)
    captured = {}

    async def capture_audit(*args, **kwargs):
        captured["actor_id"] = args[1]
        captured["action"] = args[2]
        captured.update(kwargs)

    monkeypatch.setattr(main_postgres, "_write_audit", capture_audit)
    request = SimpleNamespace(
        client=SimpleNamespace(host="127.0.0.1"),
        headers={"user-agent": "LifeLinkTest/1.0"},
    )
    principal = Principal(
        subject="user-123",
        email_verified=True,
        session_id="session-123",
    )

    asyncio.run(
        main_postgres.register_push_token(
            payload=main_postgres.PushTokenIn(token="new-secret-token"),
            request=request,
            session=session,
            principal=principal,
            x_correlation_id="android:push-token-register",
        )
    )

    assert profile.fcm_token == "new-secret-token"
    assert session.committed is True
    assert captured["actor_id"] == "user-123"
    assert captured["action"] == expected_action
    assert captured["session_id"] == "session-123"
    assert captured["correlation_id"] == "android:push-token-register"
    assert captured["metadata"] == {
        "description": "Push notification token registered"
    }
    assert "new-secret-token" not in repr(captured)
    assert "previous-secret-token" not in repr(captured)
