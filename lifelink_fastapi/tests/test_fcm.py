import asyncio
import json

import httpx

from app import fcm


def test_pushes_keep_each_token_bound_to_its_authenticated_recipient(monkeypatch):
    payloads = []

    def receive(request):
        payloads.append(json.loads(request.content)["message"])
        return httpx.Response(200, json={"name": "synthetic-message"})

    client = httpx.AsyncClient
    monkeypatch.setattr(fcm, "_service_account", lambda: {"project_id": "synthetic-project"})
    monkeypatch.setattr(fcm, "_access_token", lambda _: ("synthetic-token", "synthetic-project"))
    monkeypatch.setattr(fcm.httpx, "AsyncClient", lambda **kwargs: client(transport=httpx.MockTransport(receive), **kwargs))
    count = asyncio.run(fcm.send_push(
        [("alice", "device-a"), ("bob", "device-b"), ("alice", "device-a"), (" ", "unowned-device")],
        "Request update", "Open LifeLink", {"request_id": "request-1", "user_id": "must-not-be-reused"},
    ))
    assert count == 2
    assert {message["token"]: message["data"]["user_id"] for message in payloads} == {"device-a": "alice", "device-b": "bob"}
    for message in payloads:
        assert "notification" not in message  # OS auto-display would bypass the app's owner check.
        assert message["data"]["request_id"] == "request-1"
        assert message["data"]["title"] == "Request update"
        assert message["data"]["body"] == "Open LifeLink"


def test_push_failure_does_not_fail_the_api_request(monkeypatch):
    async def fail(*args):
        raise RuntimeError("synthetic delivery failure")

    monkeypatch.setattr(fcm, "send_push", fail)
    asyncio.run(fcm.send_push_safely([("alice", "device-a")], "Update", "Body", {}))
