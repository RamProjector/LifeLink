from __future__ import annotations

import asyncio
import json
import logging
import os
from typing import Any

import httpx

logger = logging.getLogger(__name__)


def _service_account() -> dict[str, Any] | None:
    raw = os.getenv("FIREBASE_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw:
        return None
    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        logger.error("FIREBASE_SERVICE_ACCOUNT_JSON is not valid JSON")
        return None
    return value if isinstance(value, dict) else None


def _access_token(account: dict[str, Any]) -> tuple[str, str] | None:
    try:
        from google.auth.transport.requests import Request
        from google.oauth2 import service_account

        credentials = service_account.Credentials.from_service_account_info(
            account,
            scopes=["https://www.googleapis.com/auth/firebase.messaging"],
        )
        credentials.refresh(Request())
        project_id = account.get("project_id")
        if not credentials.token or not project_id:
            return None
        return credentials.token, project_id
    except Exception:
        logger.exception("Unable to obtain a Firebase access token")
        return None


def enabled() -> bool:
    return _service_account() is not None


async def send_push(tokens: list[str], title: str, body: str, data: dict[str, str]) -> int:
    account = _service_account()
    if not account or not tokens:
        return 0
    auth = await asyncio.to_thread(_access_token, account)
    if not auth:
        return 0
    access_token, project_id = auth
    url = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
    headers = {"Authorization": f"Bearer {access_token}"}
    sent = 0
    async with httpx.AsyncClient(timeout=10) as client:
        for token in set(tokens):
            payload = {
                "message": {
                    "token": token,
                    "notification": {"title": title, "body": body},
                    "data": data,
                    "android": {"priority": "high"},
                }
            }
            try:
                response = await client.post(url, headers=headers, json=payload)
                if response.is_success:
                    sent += 1
                else:
                    logger.warning("FCM delivery failed with status %s", response.status_code)
            except httpx.HTTPError:
                logger.exception("FCM delivery request failed")
    return sent


async def send_push_safely(tokens: list[str], title: str, body: str, data: dict[str, str]) -> None:
    try:
        await send_push(tokens, title, body, data)
    except Exception:
        logger.exception("FCM delivery failed without affecting the API request")
