from __future__ import annotations

import os
import re
from datetime import datetime, timezone
from uuid import uuid4

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from pydantic import BaseModel, Field
from sqlalchemy import func, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from .db import get_db_session
from .db_models import Donor, EmergencyRequest, LifeLinkProfile
from .security import Principal, get_postgres_principal, require_verified_email

router = APIRouter(prefix="/v1/admin", tags=["admin"])


def _configured_admin_ids() -> set[str]:
    """Server-managed allowlist; never derive administrative access from user_metadata."""
    return {item.strip() for item in os.getenv("LIFELINK_ADMIN_USER_IDS", "").split(",") if item.strip()}


def require_admin(principal: Principal = Depends(get_postgres_principal)) -> Principal:
    if principal.subject == "development-user":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Sign in with an authenticated account")
    require_verified_email(principal)
    if principal.subject not in _configured_admin_ids():
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Administrator access is required")
    return principal


class AdminProfileUpdate(BaseModel):
    role: str = Field(pattern="^(requester|donor)$")
    can_request: bool
    can_donate: bool


async def _write_audit(
    session: AsyncSession,
    actor_id: str,
    action: str,
    *,
    outcome: str = "success",
    target_type: str | None = None,
    target_id: str | None = None,
    correlation_id: str | None = None,
    metadata: dict | None = None,
) -> None:
    # Deliberately excludes email, location, blood type, credentials, and request bodies.
    await session.execute(
        text(
            "INSERT INTO audit_events "
            "(id, actor_id, action, metadata, created_at, outcome, target_type, target_id, correlation_id) "
            "VALUES (:id, :actor_id, :action, CAST(:metadata AS jsonb), :created_at, :outcome, "
            ":target_type, :target_id, :correlation_id)"
        ),
        {
            "id": str(uuid4()),
            "actor_id": actor_id,
            "action": action[:64],
            "metadata": __import__("json").dumps(metadata or {}),
            "created_at": datetime.now(timezone.utc),
            "outcome": outcome,
            "target_type": target_type,
            "target_id": target_id,
            "correlation_id": correlation_id,
        },
    )


_SENSITIVE_AUDIT_KEY = re.compile(
    r"(?:email|phone|address|location|latitude|longitude|blood|token|password|secret|"
    r"authorization|credential|request.?body|payload|contact.?info|birth.?date)",
    re.IGNORECASE,
)


def _safe_audit_metadata(value, *, depth: int = 0):
    """Bound and redact untrusted metadata before showing it in the admin UI."""
    if depth >= 5:
        return "[TRUNCATED]"
    if isinstance(value, dict):
        safe = {}
        for key, item in list(value.items())[:50]:
            name = str(key)[:100]
            safe[name] = "[REDACTED]" if _SENSITIVE_AUDIT_KEY.search(name) else _safe_audit_metadata(
                item, depth=depth + 1
            )
        if len(value) > 50:
            safe["_truncated"] = True
        return safe
    if isinstance(value, (list, tuple)):
        safe = [_safe_audit_metadata(item, depth=depth + 1) for item in value[:50]]
        if len(value) > 50:
            safe.append("[TRUNCATED]")
        return safe
    if isinstance(value, str):
        return value[:1000]
    if value is None or isinstance(value, (bool, int, float)):
        return value
    return str(value)[:1000]


def _correlation_id(value: str | None) -> str:
    # Client-provided IDs are untrusted and bounded; generate one if invalid.
    if value and re.fullmatch(r"[A-Za-z0-9._:-]{1,80}", value):
        return value
    return str(uuid4())


@router.get("/me")
async def admin_me(
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
    x_correlation_id: str | None = Header(default=None),
):
    await _write_audit(
        session, principal.subject, "admin.session.verified", target_type="admin_session",
        correlation_id=_correlation_id(x_correlation_id),
    )
    await session.commit()
    return {"is_admin": True, "subject": principal.subject}


@router.get("/dashboard")
async def admin_dashboard(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
    x_correlation_id: str | None = Header(default=None),
):
    correlation_id = _correlation_id(x_correlation_id)
    users = await session.scalar(select(func.count()).select_from(LifeLinkProfile)) or 0
    donors = await session.scalar(select(func.count()).select_from(Donor)) or 0
    requests = await session.scalar(select(func.count()).select_from(EmergencyRequest)) or 0
    await _write_audit(session, principal.subject, "admin.dashboard.view", target_type="dashboard",
                       correlation_id=correlation_id)
    await session.commit()
    return {
        "users": users,
        "donors": donors,
        "requests": requests,
        "generated_at": datetime.now(timezone.utc),
        "correlation_id": correlation_id,
    }


@router.get("/users")
async def admin_list_users(
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    x_correlation_id: str | None = Header(default=None),
):
    result = await session.execute(
        select(LifeLinkProfile).order_by(LifeLinkProfile.created_at.desc()).offset(offset).limit(limit)
    )
    profiles = result.scalars().all()
    total = await session.scalar(select(func.count()).select_from(LifeLinkProfile)) or 0
    await _write_audit(session, principal.subject, "admin.users.list", target_type="user_list",
                       correlation_id=_correlation_id(x_correlation_id), metadata={"limit": limit, "offset": offset})
    await session.commit()
    return {
        "items": [
            {
                "user_id": row.user_id,
                "email": row.email,
                "role": row.role.value if hasattr(row.role, "value") else str(row.role),
                "display_name": row.display_name,
                "can_request": row.can_request,
                "can_donate": row.can_donate,
                "created_at": row.created_at,
            }
            for row in profiles
        ],
        "total": total,
        "limit": limit,
        "offset": offset,
    }


@router.patch("/users/{user_id}")
async def admin_update_user(
    user_id: str,
    payload: AdminProfileUpdate,
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
    x_correlation_id: str | None = Header(default=None),
):
    if user_id == principal.subject:
        raise HTTPException(status_code=409, detail="Administrators cannot change their own user capabilities here")
    row = await session.get(LifeLinkProfile, user_id)
    if row is None:
        raise HTTPException(status_code=404, detail="User profile not found")
    before = {
        "role": row.role.value if hasattr(row.role, "value") else str(row.role),
        "can_request": row.can_request,
        "can_donate": row.can_donate,
    }
    from .db_models import LifeLinkRoleEnum
    row.role = LifeLinkRoleEnum(payload.role)
    row.can_request = payload.can_request
    row.can_donate = payload.can_donate
    row.updated_at = datetime.now(timezone.utc)
    await _write_audit(
        session, principal.subject, "admin.user.update", target_type="user", target_id=user_id,
        correlation_id=_correlation_id(x_correlation_id),
        metadata={"before": before, "after": payload.model_dump()},
    )
    await session.commit()
    return {"user_id": row.user_id, "role": payload.role, "can_request": row.can_request, "can_donate": row.can_donate}


@router.get("/audit")
async def admin_audit_logs(
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    action: str | None = Query(default=None, max_length=64),
    x_correlation_id: str | None = Header(default=None),
):
    # Record the read before selecting the page; no endpoint permits audit mutation or deletion.
    await _write_audit(session, principal.subject, "admin.audit.read", target_type="audit_log",
                       correlation_id=_correlation_id(x_correlation_id),
                       metadata={"limit": limit, "offset": offset, "filtered": bool(action)})
    query = (
        "SELECT id, actor_id, action, metadata, created_at, outcome, target_type, target_id, correlation_id "
        "FROM audit_events WHERE (:action IS NULL OR action = :action) "
        "ORDER BY created_at DESC LIMIT :limit OFFSET :offset"
    )
    result = await session.execute(text(query), {"action": action, "limit": limit, "offset": offset})
    rows = result.mappings().all()
    await session.commit()
    return {
        "items": [
            {**dict(row), "metadata": _safe_audit_metadata(row["metadata"]), "created_at": row["created_at"].isoformat() if row["created_at"] else None}
            for row in rows
        ],
        "limit": limit,
        "offset": offset,
    }


@router.get("/errors")
async def admin_errors(
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
):
    result = await session.execute(
        text(
            "SELECT id, actor_id, action, metadata, created_at, outcome, target_type, target_id, correlation_id "
            "FROM audit_events WHERE action LIKE 'error.%' OR outcome IN ('error', 'denied') "
            "ORDER BY created_at DESC LIMIT :limit OFFSET :offset"
        ),
        {"limit": limit, "offset": offset},
    )
    rows = result.mappings().all()
    await _write_audit(session, principal.subject, "admin.errors.read", target_type="error_log",
                       metadata={"limit": limit, "offset": offset})
    await session.commit()
    return {"items": [{**dict(row), "metadata": _safe_audit_metadata(row["metadata"]), "created_at": row["created_at"].isoformat() if row["created_at"] else None}
                      for row in rows], "limit": limit, "offset": offset}
