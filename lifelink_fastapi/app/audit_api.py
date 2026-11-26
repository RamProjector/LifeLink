from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from .admin_api import _correlation_id, _safe_audit_metadata, _write_audit
from .db import get_db_session
from .security import Principal, get_active_postgres_principal, require_verified_email

router = APIRouter(prefix="/v1/audit", tags=["audit"])


@router.get("/me")
async def read_my_audit_events(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_active_postgres_principal),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    action: str | None = Query(default=None, max_length=64),
):
    """Return only audit events attributed to the authenticated account."""
    if principal.subject == "development-user":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Sign in to view your activity history",
            headers={"WWW-Authenticate": "Bearer"},
        )
    require_verified_email(principal)

    result = await session.execute(
        text(
            "SELECT log_id, action, actions, description, session_id, ip_address, "
            "timestamp, device_used, metadata, outcome "
            "FROM audit_events "
            "WHERE actor_id = :actor_id AND (:action IS NULL OR action = :action) "
            "ORDER BY timestamp DESC, log_id DESC LIMIT :limit OFFSET :offset"
        ),
        {
            "actor_id": principal.subject,
            "action": action,
            "limit": limit,
            "offset": offset,
        },
    )
    rows = result.mappings().all()

    # Record that the account viewed its own activity, in the same transaction.
    await _write_audit(
        session,
        principal.subject,
        "audit.self.read",
        request=request,
        session_id=principal.session_id,
        session_expires_at=principal.session_expires_at,
        target_type="personal_audit",
        correlation_id=_correlation_id(request.headers.get("x-correlation-id")),
        metadata={"limit": limit, "offset": offset, "filtered": bool(action)},
    )
    await session.commit()

    return {
        "items": [
            {
                **dict(row),
                "timestamp": row["timestamp"].isoformat() if row["timestamp"] else None,
                "metadata": _safe_audit_metadata(row["metadata"]),
            }
            for row in rows
        ],
        "limit": limit,
        "offset": offset,
    }
