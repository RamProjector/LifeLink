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


async def require_admin(
    request: Request,
    principal: Principal = Depends(get_postgres_principal),
    session: AsyncSession = Depends(get_db_session),
) -> Principal:
    if principal.subject == "development-user":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Sign in with an authenticated account")
    require_verified_email(principal)
    if principal.subject not in _configured_admin_ids():
        await _record_denied_admin_attempt(
            session, principal, request, rule_id="IDS-03", action="security.admin_access.denied",
            severity="high", threshold=5,
            summary="Repeated attempts to access administrator endpoints without authorization",
        )
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Administrator access is required")
    if not principal.session_id:
        await _record_denied_admin_attempt(
            session, principal, request, rule_id="IDS-04", action="security.admin_session.missing",
            severity="medium", threshold=3,
            summary="Administrator endpoint accessed without a Supabase session identifier",
        )
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Session context is missing. Sign in again to establish a secure session.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    if principal.session_id:
        revoked = await session.scalar(
            text(
                "SELECT EXISTS (SELECT 1 FROM lifelink_user_sessions "
                "WHERE session_id = :session_id AND revoked_at IS NOT NULL)"
            ),
            {"session_id": principal.session_id},
        )
        if revoked:
            await _record_denied_admin_attempt(
                session, principal, request, rule_id="IDS-05", action="security.revoked_session.reuse",
                severity="high", threshold=1,
                summary="A revoked administrator session attempted to access a protected endpoint",
            )
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="This session has been revoked. Sign in again.",
                headers={"WWW-Authenticate": "Bearer"},
            )
    return principal


async def _record_denied_admin_attempt(
    session: AsyncSession,
    principal: Principal,
    request: Request,
    *,
    rule_id: str,
    action: str,
    severity: str,
    threshold: int,
    summary: str,
) -> None:
    """Persist denied admin access and raise a deduplicated alert after a threshold."""
    correlation_id = _correlation_id(request.headers.get("x-correlation-id"))
    await _write_audit(
        session,
        principal.subject,
        action,
        request=request,
        session_id=principal.session_id,
        session_expires_at=principal.session_expires_at,
        outcome="denied",
        target_type="admin_api",
        target_id=request.url.path[:128],
        correlation_id=correlation_id,
        metadata={"method": request.method, "rule_id": rule_id},
    )
    count = await session.scalar(
        text(
            "SELECT count(*) FROM audit_events "
            "WHERE actor_id = :actor_id AND action = :action "
            "AND timestamp >= now() - interval '10 minutes'"
        ),
        {"actor_id": principal.subject, "action": action},
    ) or 0
    if count >= threshold:
        await session.execute(
            text(
                "INSERT INTO lifelink_security_alerts "
                "(rule_id, severity, status, user_id, session_id, summary, evidence, occurrences, correlation_id) "
                "VALUES (:rule_id, :severity, 'open', :user_id, :session_id, :summary, "
                "CAST(:evidence AS jsonb), :occurrences, :correlation_id) "
                "ON CONFLICT (rule_id, user_id) "
                "WHERE status IN ('open', 'acknowledged', 'investigating') "
                "DO UPDATE SET last_seen_at = now(), occurrences = lifelink_security_alerts.occurrences + 1, "
                "session_id = EXCLUDED.session_id, evidence = EXCLUDED.evidence"
            ),
            {
                "rule_id": rule_id,
                "severity": severity,
                "user_id": principal.subject,
                "session_id": principal.session_id,
                "summary": summary,
                "evidence": __import__("json").dumps({
                    "attempt_count_10m": int(count),
                    "endpoint": request.url.path[:128],
                    "method": request.method,
                }),
                "occurrences": int(count),
                "correlation_id": correlation_id,
            },
        )
    await session.commit()


class AdminAlertUpdate(BaseModel):
    status: str = Field(pattern="^(acknowledged|investigating|resolved|false_positive)$")


class AdminProfileUpdate(BaseModel):
    role: str = Field(pattern="^(requester|donor)$")
    can_request: bool
    can_donate: bool


def _audit_action_kind(action: str) -> str:
    """Map CRUD events to the required normalized action vocabulary."""
    verb = action.rsplit(".", 1)[-1].lower()
    if verb in {"add", "create", "insert"}:
        return "ADD"
    if verb in {"edit", "update", "patch"}:
        return "EDIT"
    if verb in {"delete", "remove", "revoke"}:
        return "DELETE"
    return "OTHER"


async def _write_audit(
    session: AsyncSession,
    actor_id: str,
    action: str,
    *,
    request: Request | None = None,
    session_id: str | None = None,
    session_expires_at: datetime | None = None,
    outcome: str = "success",
    target_type: str | None = None,
    target_id: str | None = None,
    correlation_id: str | None = None,
    metadata: dict | None = None,
) -> None:
    # IP and device context are derived from the ASGI request, never client-supplied
    # headers. Do not trust X-Forwarded-For until the deployment's trusted-proxy
    # configuration is explicitly verified.
    peer_ip = request.client.host if request and request.client else None
    user_agent = request.headers.get("user-agent", "unknown")[:255] if request else "unknown"
    if session_id:
        await session.execute(
            text(
                "INSERT INTO lifelink_user_sessions "
                "(session_id, user_id, device_used, ip_address, created_at, last_seen_at, expires_at, status) "
                "VALUES (:session_id, :user_id, :device_used, :ip_address, :now, :now, :expires_at, 'active') "
                "ON CONFLICT (session_id) DO UPDATE SET "
                "last_seen_at = EXCLUDED.last_seen_at, "
                "device_used = EXCLUDED.device_used, "
                "ip_address = COALESCE(EXCLUDED.ip_address, lifelink_user_sessions.ip_address), "
                "expires_at = COALESCE(EXCLUDED.expires_at, lifelink_user_sessions.expires_at), "
                "status = CASE WHEN lifelink_user_sessions.revoked_at IS NULL THEN 'active' ELSE 'revoked' END"
            ),
            {
                "session_id": session_id,
                "user_id": actor_id,
                "device_used": user_agent,
                "ip_address": peer_ip,
                "now": datetime.now(timezone.utc),
                "expires_at": session_expires_at,
            },
        )
    await session.execute(
        text(
            "INSERT INTO audit_events "
            "(id, log_id, actor_id, action, actions, description, session_id, ip_address, "
            "timestamp, device_used, metadata, created_at, outcome, target_type, target_id, correlation_id) "
            "VALUES (:id, :log_id, :actor_id, :action, :actions, :description, :session_id, "
            ":ip_address, :timestamp, :device_used, CAST(:metadata AS jsonb), :created_at, :outcome, "
            ":target_type, :target_id, :correlation_id)"
        ),
        {
            "id": str(uuid4()),
            "log_id": str(uuid4()),
            "actor_id": actor_id,
            "action": action[:64],
            "actions": _audit_action_kind(action),
            "description": action[:500],
            "session_id": session_id,
            "ip_address": peer_ip,
            "timestamp": datetime.now(timezone.utc),
            "device_used": user_agent,
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
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
    x_correlation_id: str | None = Header(default=None),
):
    await _write_audit(
        session, principal.subject, "admin.session.verified", request=request, session_id=principal.session_id, session_expires_at=principal.session_expires_at, target_type="admin_session",
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
    await _write_audit(session, principal.subject, "admin.dashboard.view", request=request, session_id=principal.session_id, session_expires_at=principal.session_expires_at, target_type="dashboard",
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
    request: Request,
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
    await _write_audit(session, principal.subject, "admin.users.list", request=request, session_id=principal.session_id, session_expires_at=principal.session_expires_at, target_type="user_list",
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
        session, principal.subject, "admin.user.update", request=request, session_id=principal.session_id, session_expires_at=principal.session_expires_at, target_type="user", target_id=user_id,
        correlation_id=_correlation_id(x_correlation_id),
        metadata={"before": before, "after": payload.model_dump()},
    )
    await session.commit()
    return {"user_id": row.user_id, "role": payload.role, "can_request": row.can_request, "can_donate": row.can_donate}


async def _scan_rbac_discrepancies(session: AsyncSession, principal: Principal, request: Request) -> int:
    """Detect active role assignments that conflict with account capabilities or server allowlists."""
    result = await session.execute(text(
        "SELECT ur.user_id::text AS user_id, r.role_key, "
        "COALESCE(p.can_request, false) AS can_request, "
        "COALESCE(p.can_donate, false) AS can_donate, "
        "EXISTS (SELECT 1 FROM donor_profiles dp WHERE dp.user_id = ur.user_id) "
        "OR EXISTS (SELECT 1 FROM donors d WHERE d.user_id = ur.user_id::text) AS has_donor_profile "
        "FROM lifelink_user_roles ur JOIN lifelink_roles r ON r.id = ur.role_id "
        "LEFT JOIN lifelink_profiles p ON p.user_id = ur.user_id::text "
        "WHERE ur.expires_at IS NULL OR ur.expires_at > now()"
    ))
    count = 0
    for row in result.mappings().all():
        profile = {"can_request": row["can_request"], "can_donate": row["can_donate"],
                   "has_donor_profile": row["has_donor_profile"]}
        reason = _role_mismatch_reason(row["role_key"], row["user_id"], profile)
        if reason:
            await _record_rbac_mismatch(session, principal, request, row["user_id"], row["role_key"], reason)
            count += 1
    return count

@router.get("/security/alerts")
async def admin_security_alerts(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    alert_status: str | None = Query(default=None, alias="status", pattern="^(open|acknowledged|investigating|resolved|false_positive)$"),
):
    await _scan_rbac_discrepancies(session, principal, request)
    result = await session.execute(
        text(
            "SELECT id, rule_id, severity, status, user_id, session_id, summary, evidence, occurrences, "
            "created_at, last_seen_at, acknowledged_at, acknowledged_by, resolved_at, resolved_by, correlation_id "
            "FROM lifelink_security_alerts "
            "WHERE (:status IS NULL OR status = :status) "
            "ORDER BY CASE severity WHEN 'critical' THEN 0 WHEN 'high' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END, "
            "last_seen_at DESC LIMIT :limit OFFSET :offset"
        ),
        {"status": alert_status, "limit": limit, "offset": offset},
    )
    rows = result.mappings().all()
    await _write_audit(
        session, principal.subject, "admin.security.alerts.read", request=request,
        session_id=principal.session_id, session_expires_at=principal.session_expires_at,
        target_type="security_alerts", metadata={"limit": limit, "offset": offset, "status": alert_status},
    )
    await session.commit()
    return {
        "items": [
            {**dict(row), **{
                key: row[key].isoformat() if row[key] else None
                for key in ("created_at", "last_seen_at", "acknowledged_at", "resolved_at")
            }, "evidence": _safe_audit_metadata(row["evidence"])}
            for row in rows
        ],
        "limit": limit,
        "offset": offset,
    }


@router.patch("/security/alerts/{alert_id}")
async def admin_update_security_alert(
    alert_id: str,
    payload: AdminAlertUpdate,
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
):
    if payload.status in {"acknowledged", "investigating"}:
        update_sql = (
            "UPDATE lifelink_security_alerts SET status = :status, "
            "acknowledged_at = COALESCE(acknowledged_at, now()), acknowledged_by = :actor_id "
            "WHERE id = :alert_id AND status IN ('open', 'acknowledged', 'investigating') "
            "RETURNING id, rule_id, status"
        )
    else:
        update_sql = (
            "UPDATE lifelink_security_alerts SET status = :status, "
            "resolved_at = now(), resolved_by = :actor_id "
            "WHERE id = :alert_id AND status IN ('open', 'acknowledged', 'investigating') "
            "RETURNING id, rule_id, status"
        )
    result = await session.execute(
        text(update_sql),
        {"status": payload.status, "actor_id": principal.subject, "alert_id": alert_id},
    )
    row = result.mappings().first()
    if row is None:
        await session.rollback()
        raise HTTPException(status_code=404, detail="Open security alert not found")
    await _write_audit(
        session, principal.subject, "admin.security.alert.update", request=request,
        session_id=principal.session_id, session_expires_at=principal.session_expires_at,
        target_type="security_alert", target_id=alert_id, metadata={"status": payload.status},
    )
    await session.commit()
    return {"id": str(row["id"]), "rule_id": row["rule_id"], "status": row["status"]}


@router.get("/sessions")
async def admin_list_sessions(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
):
    result = await session.execute(
        text(
            "SELECT session_id, user_id, device_used, ip_address, created_at, last_seen_at, "
            "expires_at, revoked_at, status "
            "FROM lifelink_user_sessions ORDER BY last_seen_at DESC LIMIT :limit OFFSET :offset"
        ),
        {"limit": limit, "offset": offset},
    )
    rows = result.mappings().all()
    await _write_audit(
        session, principal.subject, "admin.sessions.list", request=request,
        session_id=principal.session_id, session_expires_at=principal.session_expires_at,
        target_type="session_list", metadata={"limit": limit, "offset": offset},
    )
    await session.commit()
    return {
        "items": [
            {**dict(row), **{
                key: row[key].isoformat() if row[key] else None
                for key in ("created_at", "last_seen_at", "expires_at", "revoked_at")
            }}
            for row in rows
        ],
        "limit": limit,
        "offset": offset,
    }


@router.post("/sessions/{session_id}/revoke")
async def admin_revoke_session(
    session_id: str,
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
):
    if principal.session_id and session_id == principal.session_id:
        raise HTTPException(status_code=409, detail="Use sign-out to end your current administrator session")
    result = await session.execute(
        text(
            "UPDATE lifelink_user_sessions SET revoked_at = now(), revoked_by = :actor_id, "
            "status = 'revoked' WHERE session_id = :session_id AND revoked_at IS NULL "
            "RETURNING session_id, user_id"
        ),
        {"actor_id": principal.subject, "session_id": session_id},
    )
    row = result.mappings().first()
    if row is None:
        await session.rollback()
        raise HTTPException(status_code=404, detail="Active session not found")
    await _write_audit(
        session, principal.subject, "admin.session.revoke", request=request,
        session_id=principal.session_id, session_expires_at=principal.session_expires_at,
        target_type="user_session", target_id=session_id,
        metadata={"affected_user_id": row["user_id"]},
    )
    await session.commit()
    return {"session_id": session_id, "status": "revoked"}


@router.get("/audit")
async def admin_audit_logs(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    action: str | None = Query(default=None, max_length=64),
    x_correlation_id: str | None = Header(default=None),
):
    # Record the read before selecting the page; no endpoint permits audit mutation or deletion.
    await _write_audit(session, principal.subject, "admin.audit.read", request=request, session_id=principal.session_id, session_expires_at=principal.session_expires_at, target_type="audit_log",
                       correlation_id=_correlation_id(x_correlation_id),
                       metadata={"limit": limit, "offset": offset, "filtered": bool(action)})
    query = (
        "SELECT id, log_id, actor_id, action, actions, description, session_id, ip_address, timestamp, device_used, "
            "metadata, created_at, outcome, target_type, target_id, correlation_id "
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
    request: Request,
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
    await _write_audit(session, principal.subject, "admin.errors.read", request=request, session_id=principal.session_id, session_expires_at=principal.session_expires_at, target_type="error_log",
                       metadata={"limit": limit, "offset": offset})
    await session.commit()
    return {"items": [{**dict(row), "metadata": _safe_audit_metadata(row["metadata"]), "created_at": row["created_at"].isoformat() if row["created_at"] else None}
                      for row in rows], "limit": limit, "offset": offset}


# RBAC management is deliberately gated by a separate server-side allowlist.
# Existing admin access does not automatically imply permission to grant roles.
def _configured_rbac_manager_ids() -> set[str]:
    return {
        item.strip()
        for item in os.getenv("LIFELINK_RBAC_MANAGER_USER_IDS", "").split(",")
        if item.strip()
    }


def _require_rbac_manager(principal: Principal) -> None:
    if principal.subject not in _configured_rbac_manager_ids():
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Role assignment requires a separately authorized RBAC manager",
        )


def _role_mismatch_reason(role_key: str, user_id: str, profile: dict | None) -> str | None:
    """Return why a role is not applicable to an account, if it is not."""
    admin_roles = {"super_admin", "security_admin", "operations_admin", "user_admin", "auditor"}
    if role_key == "requester":
        return None if profile and profile.get("can_request") else "requester_capability_missing"
    if role_key == "donor":
        return None if profile and (profile.get("can_donate") or profile.get("has_donor_profile")) else "donor_capability_missing"
    if role_key in admin_roles:
        if user_id not in _configured_admin_ids():
            return "account_not_in_admin_allowlist"
        if role_key == "super_admin" and user_id not in _configured_rbac_manager_ids():
            return "super_admin_requires_rbac_manager_allowlist"
        return None
    return "role_has_no_applicability_policy"

async def _record_rbac_mismatch(session, principal, request, user_id: str, role_key: str, reason: str) -> None:
    """Persist an IDS finding and deduplicated administrator alert."""
    await _write_audit(session, principal.subject, "security.rbac.role_mismatch", request=request,
        session_id=principal.session_id, outcome="denied", target_type="user", target_id=user_id,
        metadata={"role_key": role_key, "mismatch_reason": reason, "rule_id": "RBAC-01"})
    await session.execute(text(
        "INSERT INTO lifelink_security_alerts "
        "(rule_id, severity, status, user_id, session_id, summary, evidence, occurrences, correlation_id) "
        "VALUES ('RBAC-01', 'high', 'open', CAST(:user_id AS uuid), :session_id, "
        "'Role assignment is not applicable to this account', CAST(:evidence AS jsonb), 1, :correlation_id) "
        "ON CONFLICT (rule_id, user_id) WHERE status IN ('open', 'acknowledged', 'investigating') "
        "DO UPDATE SET last_seen_at = now(), occurrences = lifelink_security_alerts.occurrences + 1, "
        "session_id = EXCLUDED.session_id, evidence = EXCLUDED.evidence"
    ), {"user_id": user_id, "session_id": principal.session_id,
        "evidence": __import__("json").dumps({"role_key": role_key, "mismatch_reason": reason}),
        "correlation_id": _correlation_id(request.headers.get("x-correlation-id"))})

class AdminUserRolesUpdate(BaseModel):
    # An empty list is a deliberate revoke-all operation; it must remain possible
    # to remove stale or excessive role assignments without assigning a replacement.
    role_keys: list[str] = Field(max_length=8)


@router.get("/roles")
async def admin_list_roles(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
):
    result = await session.execute(
        text(
            "SELECT r.role_key, r.display_name, r.description, "
            "COALESCE(array_agg(p.permission_key ORDER BY p.permission_key) "
            "FILTER (WHERE p.permission_key IS NOT NULL), ARRAY[]::varchar[]) AS permissions "
            "FROM lifelink_roles r "
            "LEFT JOIN lifelink_role_permissions rp ON rp.role_id = r.id "
            "LEFT JOIN lifelink_permissions p ON p.id = rp.permission_id "
            "GROUP BY r.id ORDER BY r.display_name"
        )
    )
    rows = result.mappings().all()
    await _write_audit(
        session, principal.subject, "admin.roles.list", request=request,
        session_id=principal.session_id, target_type="role_registry",
    )
    await session.commit()
    return {"items": [dict(row) for row in rows]}


@router.get("/users/{user_id}/roles")
async def admin_get_user_roles(
    user_id: str,
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
):
    result = await session.execute(
        text(
            "SELECT r.role_key, r.display_name, ur.assigned_at, ur.expires_at "
            "FROM lifelink_user_roles ur JOIN lifelink_roles r ON r.id = ur.role_id "
            "WHERE ur.user_id = CAST(:user_id AS uuid) "
            "AND (ur.expires_at IS NULL OR ur.expires_at > now()) "
            "ORDER BY r.display_name"
        ),
        {"user_id": user_id},
    )
    rows = result.mappings().all()
    await _write_audit(
        session, principal.subject, "admin.user.roles.read", request=request,
        session_id=principal.session_id, target_type="user_roles", target_id=user_id,
    )
    await session.commit()
    return {"user_id": user_id, "items": [dict(row) for row in rows]}


@router.put("/users/{user_id}/roles")
async def admin_replace_user_roles(
    user_id: str,
    payload: AdminUserRolesUpdate,
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin),
):
    _require_rbac_manager(principal)
    if user_id == principal.subject:
        raise HTTPException(status_code=409, detail="RBAC managers cannot change their own role assignments")
    role_keys = sorted(set(payload.role_keys))
    if len(role_keys) != len(payload.role_keys):
        raise HTTPException(status_code=422, detail="Duplicate role keys are not allowed")
    allowed = await session.execute(
        text("SELECT role_key FROM lifelink_roles WHERE role_key = ANY(:role_keys)"),
        {"role_keys": role_keys},
    )
    known = {row[0] for row in allowed.all()}
    unknown = sorted(set(role_keys) - known)
    if unknown:
        await _write_audit(
            session, principal.subject, "security.rbac.unknown_role_assignment", request=request,
            session_id=principal.session_id, outcome="denied", target_type="user", target_id=user_id,
            metadata={"unknown_role_keys": unknown},
        )
        await session.commit()
        raise HTTPException(status_code=422, detail="One or more requested roles do not exist")
    user_exists = await session.scalar(
        text("SELECT EXISTS (SELECT 1 FROM auth.users WHERE id = CAST(:user_id AS uuid))"),
        {"user_id": user_id},
    )
    if not user_exists:
        raise HTTPException(status_code=404, detail="User account not found")
    profile_row = await session.execute(
        text(
            "SELECT COALESCE(p.can_request, false) AS can_request, "
            "COALESCE(p.can_donate, false) AS can_donate, "
            "EXISTS (SELECT 1 FROM donor_profiles dp WHERE dp.user_id = CAST(:user_id AS uuid)) "
            "OR EXISTS (SELECT 1 FROM donors d WHERE d.user_id = CAST(:user_id AS varchar)) AS has_donor_profile "
            "FROM (SELECT 1) seed LEFT JOIN lifelink_profiles p ON p.user_id = CAST(:user_id AS varchar)"
        ), {"user_id": user_id},
    )
    profile = dict(profile_row.mappings().one())
    mismatches = [
        (role_key, reason) for role_key in role_keys
        if (reason := _role_mismatch_reason(role_key, user_id, profile)) is not None
    ]
    if mismatches:
        for role_key, reason in mismatches:
            await _record_rbac_mismatch(session, principal, request, user_id, role_key, reason)
        await session.commit()
        raise HTTPException(status_code=409, detail={
            "message": "One or more roles are not applicable to this account",
            "mismatches": [{"role_key": role_key, "reason": reason} for role_key, reason in mismatches],
        })
    before_result = await session.execute(
        text(
            "SELECT r.role_key FROM lifelink_user_roles ur "
            "JOIN lifelink_roles r ON r.id = ur.role_id "
            "WHERE ur.user_id = CAST(:user_id AS uuid) "
            "AND (ur.expires_at IS NULL OR ur.expires_at > now())"
        ),
        {"user_id": user_id},
    )
    before = sorted(row[0] for row in before_result.all())
    await session.execute(
        text("DELETE FROM lifelink_user_roles WHERE user_id = CAST(:user_id AS uuid)"),
        {"user_id": user_id},
    )
    if role_keys:
        await session.execute(
            text(
                "INSERT INTO lifelink_user_roles (user_id, role_id, assigned_by) "
                "SELECT CAST(:user_id AS uuid), id, CAST(:actor_id AS uuid) "
                "FROM lifelink_roles WHERE role_key = ANY(:role_keys)"
            ),
            {"user_id": user_id, "actor_id": principal.subject, "role_keys": role_keys},
        )
    await _write_audit(
        session, principal.subject, "admin.user.roles.update", request=request,
        session_id=principal.session_id, target_type="user", target_id=user_id,
        metadata={"before_role_keys": before, "after_role_keys": role_keys},
    )
    await session.commit()
    return {"user_id": user_id, "role_keys": role_keys}
