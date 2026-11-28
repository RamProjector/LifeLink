from __future__ import annotations

import math
import os
import re
from datetime import datetime, timezone
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from pydantic import BaseModel, Field
from sqlalchemy import func, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from .db import get_db_session
from .db_models import Donor, EmergencyRequest, LifeLinkProfile
from .security import Principal, device_class, get_active_postgres_principal, require_verified_email

router = APIRouter(prefix="/v1/admin", tags=["admin"])


def _configured_admin_ids() -> set[str]:
    """Server-managed allowlist; never derive administrative access from user_metadata."""
    return {
        item.strip()
        for setting in ("LIFELINK_ADMIN_USER_IDS", "LIFELINK_ADDITIONAL_ADMIN_USER_IDS")
        for item in os.getenv(setting, "").split(",")
        if item.strip()
    }


async def require_admin_identity(
    request: Request,
    principal: Principal = Depends(get_active_postgres_principal),
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


async def _require_admin_aal2(
    request: Request,
    principal: Principal,
    session: AsyncSession,
) -> Principal:
    if principal.assurance_level != "aal2":
        await _record_denied_admin_attempt(
            session,
            principal,
            request,
            rule_id="IDS-06",
            action="security.admin_mfa.assurance_denied",
            severity="high",
            threshold=3,
            summary="An administrator session attempted protected access without verified MFA assurance",
        )
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Administrator multi-factor verification is required. Complete authenticator verification and sign in again.",
        )
    return principal


async def require_admin_mfa_event(
    request: Request,
    principal: Principal = Depends(get_active_postgres_principal),
    session: AsyncSession = Depends(get_db_session),
) -> Principal:
    """Allow AAL2 administrators to write enrollment receipts, including the first factor."""
    principal = await require_admin_identity(request, principal, session)
    return await _require_admin_aal2(request, principal, session)


async def require_admin(
    request: Request,
    principal: Principal = Depends(get_active_postgres_principal),
    session: AsyncSession = Depends(get_db_session),
) -> Principal:
    """Require allowlisted identity, active AAL2 session, and two verified TOTP factors."""
    principal = await require_admin_identity(request, principal, session)
    principal = await _require_admin_aal2(request, principal, session)
    verified_totp_count = await session.scalar(
        text(
            "SELECT count(*) FROM auth.mfa_factors "
            "WHERE user_id::varchar = CAST(:user_id AS varchar) "
            "AND factor_type = 'totp' AND status = 'verified'"
        ),
        {"user_id": principal.subject},
    ) or 0
    if int(verified_totp_count) < 2:
        await _record_denied_admin_attempt(
            session,
            principal,
            request,
            rule_id="IDS-07",
            action="security.admin_mfa.factor_count_denied",
            severity="high",
            threshold=3,
            summary="An administrator session attempted protected access without two verified TOTP authenticators",
        )
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Two verified TOTP authenticators are required for administrator access. "
                "Complete backup authenticator enrollment."
            ),
        )
    return principal


def require_admin_permission(permission_key: str):
    """Build an endpoint guard with a safe, explicitly staged RBAC rollout.

    The default observe mode preserves the existing allowlist + MFA boundary while
    deployments provision role assignments. Enforce mode requires an active
    administrative role assignment carrying the exact permission key.
    """
    if not re.fullmatch(r"[a-z][a-z0-9_.]{1,99}", permission_key):
        raise ValueError("Invalid administrator permission key")

    async def dependency(
        request: Request,
        principal: Principal = Depends(require_admin),
        session: AsyncSession = Depends(get_db_session),
    ) -> Principal:
        mode = os.getenv("LIFELINK_RBAC_ENFORCEMENT_MODE", "observe").strip().lower()
        if mode not in {"observe", "enforce"}:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Administrator authorization mode is misconfigured",
            )
        if mode == "observe":
            return principal

        granted = await session.scalar(
            text(
                "SELECT EXISTS ("
                "SELECT 1 FROM lifelink_user_roles ur "
                "JOIN lifelink_roles r ON r.id = ur.role_id "
                "LEFT JOIN lifelink_role_permissions rp ON rp.role_id = r.id "
                "LEFT JOIN lifelink_permissions p ON p.id = rp.permission_id "
                "WHERE ur.user_id = CAST(:user_id AS uuid) "
                "AND r.role_key = 'admin' "
                "AND p.permission_key = :permission_key "
                "AND (ur.expires_at IS NULL OR ur.expires_at > statement_timestamp())"
                ")"
            ),
            {
                "user_id": principal.subject,
                "permission_key": permission_key,
            },
        )
        if not granted:
            await _record_denied_admin_attempt(
                session,
                principal,
                request,
                rule_id="IDS-08",
                action="security.admin_permission.denied",
                severity="medium",
                threshold=3,
                summary="An administrator attempted an operation without its required RBAC permission",
            )
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Your administrator role does not grant this permission",
            )
        return principal

    # Keep the established route-inventory contract: each operational route still
    # exposes an admin authorization dependency, now with a permission-specific guard.
    dependency.__name__ = "require_admin"
    dependency.__qualname__ = "require_admin"
    dependency.permission_key = permission_key  # type: ignore[attr-defined]
    return dependency


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
    correlation_id = _correlation_id(request.headers.get("x-correlation-id")) if request else None
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
                "session_id = EXCLUDED.session_id, evidence = EXCLUDED.evidence, "
                "correlation_id = EXCLUDED.correlation_id"
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


class AdminMfaAuditEvent(BaseModel):
    event_type: Literal[
        "factor_enrollment_verified",
        "backup_factor_verified",
        "factor_replacement_verified",
        "factor_removal_requested",
        "recovery_reenrollment_verified",
    ]
    verified_factor_count: int = Field(ge=0, le=10)
    ticket_reference: str | None = Field(default=None, min_length=3, max_length=64, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$")


def _audit_action_kind(action: str) -> str:
    """Map CRUD event verbs to the required normalized action vocabulary."""
    tokens = set(re.split(r"[^a-z]+", action.lower()))
    if tokens.intersection({"add", "added", "create", "created", "insert", "inserted", "request", "requested", "share", "shared", "send", "sent", "save", "saved", "block", "blocked", "report", "reported"}):
        return "ADD"
    if tokens.intersection({"edit", "edited", "update", "updated", "patch", "patched", "change", "changed", "toggle", "toggled", "cancel", "cancelled", "fulfill", "fulfilled", "respond", "response", "presence", "availability"}):
        return "EDIT"
    if tokens.intersection({"delete", "deleted", "remove", "removed", "revoke", "revoked", "unsave", "unsaved"}):
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
    device_used = device_class(request.headers.get("user-agent") if request else None)
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
                "device_used": device_used,
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
            "description": str((metadata or {}).get("description") or action.replace(".", " ").replace("_", " "))[:500],
            "session_id": session_id,
            "ip_address": peer_ip,
            "timestamp": datetime.now(timezone.utc),
            "device_used": device_used,
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


@router.get("/preflight")
async def admin_identity_preflight(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin_identity),
    x_correlation_id: str | None = Header(default=None),
):
    """Confirm allowlist/session eligibility before MFA without returning admin data.

    This endpoint intentionally remains available at AAL1 for first-time MFA
    enrollment. All operational admin endpoints, including /me, must require
    AAL2 when backend MFA enforcement is enabled.
    """
    await _write_audit(
        session, principal.subject, "admin.identity.preflight", request=request,
        session_id=principal.session_id, session_expires_at=principal.session_expires_at,
        target_type="admin_identity_preflight",
        correlation_id=_correlation_id(x_correlation_id),
        metadata={"purpose": "mfa_enrollment"},
    )
    await session.commit()
    return {"authorized": True}


@router.post("/mfa/events")
async def admin_mfa_event(
    payload: AdminMfaAuditEvent,
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin_mfa_event),
    x_correlation_id: str | None = Header(default=None),
):
    """Record an AAL2 MFA lifecycle receipt reconciled to Supabase's factor inventory.

    The client-reported count is retained only for discrepancy review; it is never
    treated as evidence. Recovery receipts still require external two-operator
    approval and an independently reconciled incident ticket.
    """
    if payload.event_type == "recovery_reenrollment_verified" and not payload.ticket_reference:
        raise HTTPException(status_code=422, detail="An approved recovery ticket reference is required")

    # The caller's count is useful for detecting stale UI state, but it is never
    # accepted as proof. Reconcile every lifecycle receipt against Supabase's
    # server-managed factor inventory before writing an audit claim.
    verified_totp_count = int(await session.scalar(
        text(
            "SELECT count(*) FROM auth.mfa_factors "
            "WHERE user_id::varchar = CAST(:user_id AS varchar) "
            "AND factor_type = 'totp' AND status = 'verified'"
        ),
        {"user_id": principal.subject},
    ) or 0)
    minimum_verified_factors = {
        "factor_enrollment_verified": 1,
        "backup_factor_verified": 2,
        "factor_replacement_verified": 2,
        "factor_removal_requested": 3,
        "recovery_reenrollment_verified": 2,
    }[payload.event_type]
    if verified_totp_count < minimum_verified_factors:
        raise HTTPException(
            status_code=409,
            detail="Supabase's verified authenticator inventory does not satisfy this MFA lifecycle action.",
        )

    action = {
        "factor_enrollment_verified": "admin.mfa.factor_enrollment_verified",
        "backup_factor_verified": "admin.mfa.backup_factor_verified",
        "factor_replacement_verified": "admin.mfa.factor_replacement_verified",
        "factor_removal_requested": "admin.mfa.factor_removal_requested",
        "recovery_reenrollment_verified": "admin.mfa.recovery_reenrollment_verified",
    }[payload.event_type]
    metadata = {
        "event_type": payload.event_type,
        "verified_factor_count": verified_totp_count,
        "client_reported_factor_count": payload.verified_factor_count,
        "evidence_source": "server_confirmed_supabase_auth_factor_inventory",
    }
    if payload.ticket_reference:
        metadata["ticket_reference"] = payload.ticket_reference
    await _write_audit(
        session,
        principal.subject,
        action,
        request=request,
        session_id=principal.session_id,
        session_expires_at=principal.session_expires_at,
        target_type="admin_mfa",
        target_id=payload.event_type,
        correlation_id=_correlation_id(x_correlation_id),
        metadata=metadata,
    )
    await session.commit()
    return {"recorded": True}


@router.get("/me")
async def admin_me(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin_permission("dashboard.read")),
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
    principal: Principal = Depends(require_admin_permission("dashboard.read")),
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


# users.read intentionally includes the effective RBAC role keys and mapped
# administrative permission summary shown in the user directory. Full role
# registry and assignment-detail endpoints remain separately gated by roles.read.
@router.get("/users")
async def admin_list_users(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin_permission("users.read")),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    x_correlation_id: str | None = Header(default=None),
):
    result = await session.execute(
        text(
            "SELECT u.id::varchar AS user_id, COALESCE(u.email, p.email) AS email, "
            "CASE WHEN NOT (to_jsonb(u) ? 'deleted_at') OR NOT (to_jsonb(u) ? 'banned_until') THEN 'unknown' "
            "WHEN NULLIF(to_jsonb(u)->>'deleted_at', '') IS NOT NULL THEN 'deleted' "
            "WHEN NULLIF(to_jsonb(u)->>'banned_until', '')::timestamptz > statement_timestamp() THEN 'banned' "
            "ELSE 'active' END AS account_state, "
            "CASE WHEN to_jsonb(u) ? 'email_confirmed_at' "
            "THEN NULLIF(to_jsonb(u)->>'email_confirmed_at', '') IS NOT NULL ELSE NULL END AS email_confirmed, "
            "CASE WHEN to_jsonb(u) ? 'is_anonymous' "
            "THEN COALESCE(to_jsonb(u)->>'is_anonymous', 'false') = 'true' ELSE NULL END AS is_anonymous, "
            "COALESCE(p.role::varchar, 'unconfigured') AS role, p.display_name, "
            "COALESCE(p.can_request, false) AS can_request, "
            "COALESCE(p.can_donate, false) AS can_donate, "
            "COALESCE(p.created_at, u.created_at) AS created_at, "
            "(p.user_id IS NOT NULL) AS has_profile, "
            "COALESCE(array_agg(DISTINCT r.role_key ORDER BY r.role_key) "
            "FILTER (WHERE r.role_key IS NOT NULL), ARRAY[]::varchar[]) AS rbac_roles, "
            "COALESCE(array_agg(DISTINCT perm.permission_key ORDER BY perm.permission_key) "
            "FILTER (WHERE perm.permission_key IS NOT NULL), ARRAY[]::varchar[]) AS rbac_permissions "
            "FROM auth.users u "
            "LEFT JOIN lifelink_profiles p ON p.user_id = u.id::varchar "
            "LEFT JOIN lifelink_user_roles ur ON ur.user_id = u.id "
            "AND (ur.expires_at IS NULL OR ur.expires_at > statement_timestamp()) "
            "LEFT JOIN lifelink_roles r ON r.id = ur.role_id "
            "LEFT JOIN lifelink_role_permissions rp ON rp.role_id = r.id "
            "LEFT JOIN lifelink_permissions perm ON perm.id = rp.permission_id "
            "GROUP BY u.id, u.email, u.created_at, p.user_id, p.email, p.role, "
            "p.display_name, p.can_request, p.can_donate, p.created_at "
            "ORDER BY COALESCE(p.created_at, u.created_at) DESC, u.id "
            "LIMIT :limit OFFSET :offset"
        ),
        {"limit": limit, "offset": offset},
    )
    users = result.mappings().all()
    total = await session.scalar(text("SELECT count(*) FROM auth.users")) or 0
    await _write_audit(session, principal.subject, "admin.users.list", request=request, session_id=principal.session_id, session_expires_at=principal.session_expires_at, target_type="user_list",
                       correlation_id=_correlation_id(x_correlation_id), metadata={"limit": limit, "offset": offset})
    await session.commit()
    return {
        "items": [
            {
                **dict(row),
                "created_at": row["created_at"].isoformat() if row["created_at"] else None,
            }
            for row in users
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
    principal: Principal = Depends(require_admin_permission("users.update")),
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


# Exact grants explicitly reviewed for every registered role. The 19 keys in
# migration 020 are administrative permissions, not end-user feature grants.
# Requester/donor authorization continues to use the existing capability,
# ownership, and participant checks; those roles must hold no admin grants.
# super_admin uses the separate admin + RBAC-manager allowlists and likewise
# must not acquire ordinary permission-map rows or self-service privileges.
RBAC_APPROVED_PERMISSION_MATRIX = {
    "requester": frozenset(),
    "donor": frozenset(),
    # One Admin product role; administrative areas are permissions, not roles.
    "admin": frozenset({
        "dashboard.read", "users.read", "users.update", "users.suspend",
        "requests.read", "operations.read", "communications.metadata.read",
        "audit.read", "security.alerts.read", "security.alerts.manage",
        "security.sessions.read", "security.sessions.revoke", "roles.read",
        "reports.read",
    }),
}


def _rbac_permission_mismatch(role_key: str, actual_permissions: set[str]) -> tuple[list[str], list[str]] | None:
    expected = RBAC_APPROVED_PERMISSION_MATRIX.get(role_key)
    if expected is None:
        return None
    return sorted(expected - actual_permissions), sorted(actual_permissions - expected)


async def _scan_rbac_discrepancies(session: AsyncSession, principal: Principal, request: Request | None) -> int:
    """Detect inapplicable roles and permission grants outside the approved role policy."""
    result = await session.execute(text(
        "SELECT ur.user_id::text AS user_id, r.role_key, "
        "COALESCE(p.can_request, false) AS can_request, COALESCE(p.can_donate, false) AS can_donate, "
        "EXISTS (SELECT 1 FROM donor_profiles dp WHERE dp.user_id = ur.user_id::text) "
        "OR EXISTS (SELECT 1 FROM donors d WHERE d.user_id = ur.user_id::text) AS has_donor_profile, "
        "COALESCE(array_agg(DISTINCT perm.permission_key) FILTER (WHERE perm.permission_key IS NOT NULL), ARRAY[]::varchar[]) AS permissions "
        "FROM lifelink_user_roles ur JOIN lifelink_roles r ON r.id = ur.role_id "
        "LEFT JOIN lifelink_profiles p ON p.user_id = ur.user_id::text "
        "LEFT JOIN lifelink_role_permissions rp ON rp.role_id = r.id "
        "LEFT JOIN lifelink_permissions perm ON perm.id = rp.permission_id "
        "WHERE ur.expires_at IS NULL OR ur.expires_at > statement_timestamp() "
        "GROUP BY ur.user_id, r.role_key, p.can_request, p.can_donate"
    ))
    count = 0
    correlation_id = _correlation_id(request.headers.get("x-correlation-id")) if request else None
    for row in result.mappings().all():
        user_id, role_key = row["user_id"], row["role_key"]
        profile = {"can_request": row["can_request"], "can_donate": row["can_donate"], "has_donor_profile": row["has_donor_profile"]}
        reason = _role_mismatch_reason(role_key, user_id, profile)
        if reason:
            await _record_rbac_mismatch(session, principal, request, user_id, role_key, reason, correlation_id)
            count += 1
        permission_mismatch = _rbac_permission_mismatch(role_key, set(row["permissions"] or []))
        if permission_mismatch is not None:
            missing, unexpected = permission_mismatch
            if missing or unexpected:
                await _write_audit(session, principal.subject, "security.rbac.permission_mismatch", request=request,
                    session_id=principal.session_id, outcome="denied", target_type="user", target_id=user_id,
                    correlation_id=correlation_id,
                    metadata={"role_key": role_key, "missing_permission_keys": missing,
                              "unexpected_permission_keys": unexpected, "rule_id": "RBAC-02"})
                await session.execute(text(
                    "INSERT INTO lifelink_security_alerts (rule_id, severity, status, user_id, session_id, summary, evidence, occurrences, correlation_id) "
                    "VALUES ('RBAC-02', 'high', 'open', :user_id, :session_id, 'Role permission mapping differs from approved policy', CAST(:evidence AS jsonb), 1, :correlation_id) "
                    "ON CONFLICT (rule_id, user_id) WHERE status IN ('open', 'acknowledged', 'investigating') "
                    "DO UPDATE SET last_seen_at = now(), "
                    "occurrences = lifelink_security_alerts.occurrences + CASE "
                    "WHEN lifelink_security_alerts.evidence IS DISTINCT FROM EXCLUDED.evidence THEN 1 ELSE 0 END, "
                    "evidence = EXCLUDED.evidence, "
                    "correlation_id = COALESCE(EXCLUDED.correlation_id, lifelink_security_alerts.correlation_id)"
                ), {"user_id": user_id, "session_id": principal.session_id,
                    "evidence": __import__("json").dumps({
                        "role_key": role_key, "missing_permission_keys": missing,
                        "unexpected_permission_keys": unexpected,
                    }), "correlation_id": correlation_id})
                count += 1
    count += await _scan_rbac_role_policy_integrity(session, principal, request)
    return count


async def _scan_rbac_role_policy_integrity(
    session: AsyncSession, principal: Principal, request: Request | None
) -> int:
    """Check the exact approved role registry, including missing and unapproved roles."""
    result = await session.execute(text(
        "SELECT r.role_key, "
        "COALESCE(array_agg(DISTINCT p.permission_key) FILTER (WHERE p.permission_key IS NOT NULL), ARRAY[]::varchar[]) AS permissions "
        "FROM lifelink_roles r "
        "LEFT JOIN lifelink_role_permissions rp ON rp.role_id = r.id "
        "LEFT JOIN lifelink_permissions p ON p.id = rp.permission_id "
        "GROUP BY r.role_key ORDER BY r.role_key"
    ))
    actual_by_role = {
        row["role_key"]: set(row["permissions"] or [])
        for row in result.mappings().all()
    }
    approved_role_keys = set(RBAC_APPROVED_PERMISSION_MATRIX)
    correlation_id = _correlation_id(request.headers.get("x-correlation-id")) if request else None
    count = 0
    for role_key, expected in RBAC_APPROVED_PERMISSION_MATRIX.items():
        role_missing = role_key not in actual_by_role
        if role_missing:
            missing, unexpected = sorted(expected), []
        else:
            mismatch = _rbac_permission_mismatch(role_key, actual_by_role[role_key])
            if mismatch is None:
                continue
            missing, unexpected = mismatch
        evidence = {
            "role_key": role_key,
            "role_definition_missing": role_missing,
            "missing_permission_keys": missing,
            "unexpected_permission_keys": unexpected,
        }
        if role_missing or missing or unexpected:
            await session.execute(text(
                "INSERT INTO lifelink_role_security_findings "
                "(rule_id, role_key, severity, status, summary, evidence, occurrences, correlation_id) "
                "VALUES ('RBAC-03', :role_key, 'high', 'open', "
                "'Role permission mapping differs from approved policy', CAST(:evidence AS jsonb), 1, :correlation_id) "
                "ON CONFLICT (rule_id, role_key) "
                "WHERE status IN ('open', 'acknowledged', 'investigating') "
                "DO UPDATE SET last_seen_at = now(), "
                "occurrences = lifelink_role_security_findings.occurrences + CASE "
                "WHEN lifelink_role_security_findings.evidence IS DISTINCT FROM EXCLUDED.evidence THEN 1 ELSE 0 END, "
                "evidence = EXCLUDED.evidence, "
                "correlation_id = COALESCE(EXCLUDED.correlation_id, lifelink_role_security_findings.correlation_id)"
            ), {"role_key": role_key, "evidence": __import__("json").dumps(evidence),
                "correlation_id": correlation_id})
            count += 1
        else:
            # Close an active finding automatically once the approved policy is restored.
            await session.execute(text(
                "UPDATE lifelink_role_security_findings SET status = 'resolved', resolved_at = now() "
                "WHERE rule_id = 'RBAC-03' AND role_key = :role_key "
                "AND status IN ('open', 'acknowledged', 'investigating')"
            ), {"role_key": role_key})

    # The policy is an exact three-role registry. An extra role definition is a
    # finding even before anyone is assigned to it, so it cannot become an
    # undocumented privilege path later.
    for role_key in sorted(set(actual_by_role) - approved_role_keys):
        evidence = {
            "role_key": role_key,
            "role_definition_unapproved": True,
            "actual_permission_keys": sorted(actual_by_role[role_key]),
        }
        await session.execute(text(
            "INSERT INTO lifelink_role_security_findings "
            "(rule_id, role_key, severity, status, summary, evidence, occurrences, correlation_id) "
            "VALUES ('RBAC-03', :role_key, 'high', 'open', "
            "'Role definition is outside the approved three-role registry', CAST(:evidence AS jsonb), 1, :correlation_id) "
            "ON CONFLICT (rule_id, role_key) "
            "WHERE status IN ('open', 'acknowledged', 'investigating') "
            "DO UPDATE SET last_seen_at = now(), "
            "occurrences = lifelink_role_security_findings.occurrences + CASE "
            "WHEN lifelink_role_security_findings.evidence IS DISTINCT FROM EXCLUDED.evidence THEN 1 ELSE 0 END, "
            "evidence = EXCLUDED.evidence, "
            "correlation_id = COALESCE(EXCLUDED.correlation_id, lifelink_role_security_findings.correlation_id)"
        ), {"role_key": role_key, "evidence": __import__("json").dumps(evidence),
            "correlation_id": correlation_id})
        count += 1

    # Resolve unapproved-role findings only after the corresponding definition
    # has actually disappeared from the registry.
    existing_findings = await session.execute(text(
        "SELECT role_key FROM lifelink_role_security_findings "
        "WHERE rule_id = 'RBAC-03' AND status IN ('open', 'acknowledged', 'investigating')"
    ))
    for finding in existing_findings.mappings().all():
        role_key = finding["role_key"]
        if role_key not in approved_role_keys and role_key not in actual_by_role:
            await session.execute(text(
                "UPDATE lifelink_role_security_findings SET status = 'resolved', resolved_at = now() "
                "WHERE rule_id = 'RBAC-03' AND role_key = :role_key "
                "AND status IN ('open', 'acknowledged', 'investigating')"
            ), {"role_key": role_key})
    return count

@router.get("/security/role-integrity")
async def admin_role_integrity_findings(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin_permission("audit.read")),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    finding_status: str | None = Query(default="open", alias="status", pattern="^(open|acknowledged|investigating|resolved|false_positive)$"),
):
    result = await session.execute(text(
        "SELECT id, rule_id, role_key, severity, status, summary, evidence, occurrences, "
        "created_at, last_seen_at, resolved_at, correlation_id "
        "FROM lifelink_role_security_findings "
        "WHERE (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar)) "
        "ORDER BY CASE severity WHEN 'critical' THEN 0 WHEN 'high' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END, "
        "last_seen_at DESC LIMIT :limit OFFSET :offset"
    ), {"status": finding_status, "limit": limit, "offset": offset})
    rows = result.mappings().all()
    await _write_audit(
        session, principal.subject, "admin.security.role_integrity.read", request=request,
        session_id=principal.session_id, session_expires_at=principal.session_expires_at,
        target_type="role_security_findings",
        metadata={"limit": limit, "offset": offset, "status": finding_status},
    )
    await session.commit()
    return {
        "items": [
            {**dict(row), **{
                key: row[key].isoformat() if row[key] else None
                for key in ("created_at", "last_seen_at", "resolved_at")
            }, "evidence": _safe_audit_metadata(row["evidence"])}
            for row in rows
        ],
        "limit": limit,
        "offset": offset,
    }


@router.get("/security/scanner")
async def admin_security_scanner_health(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin_permission("security.alerts.read")),
):
    """Return persisted RBAC scanner health without exposing exception details."""
    result = await session.execute(text(
        "SELECT scanner_key, status, last_started_at, last_finished_at, last_succeeded_at, "
        "last_failed_at, last_findings, last_duration_ms, consecutive_failures, "
        "last_error_code, updated_at, "
        "CASE WHEN status = 'running' AND last_started_at IS NOT NULL "
        "THEN GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - last_started_at))) "
        "ELSE NULL END AS running_age_seconds, "
        "CASE WHEN status = 'succeeded' AND last_succeeded_at IS NOT NULL "
        "THEN GREATEST(0, EXTRACT(EPOCH FROM (clock_timestamp() - last_succeeded_at))) "
        "ELSE NULL END AS success_age_seconds "
        "FROM lifelink_security_scanner_runs WHERE scanner_key = 'rbac'"
    ))
    row = result.mappings().first()
    await _write_audit(
        session, principal.subject, "admin.security.scanner.read", request=request,
        session_id=principal.session_id, target_type="security_scanner", target_id="rbac",
    )
    await session.commit()
    try:
        configured_interval = float(os.getenv("LIFELINK_RBAC_SCAN_SECONDS", "300"))
        if not math.isfinite(configured_interval):
            raise ValueError("scan interval must be finite")
        scan_interval_seconds = min(86400, max(60, int(configured_interval)))
    except (ValueError, OverflowError):
        scan_interval_seconds = 300
    if row is None:
        return {"scanner_key": "rbac", "status": "never_run", "last_findings": 0,
                "consecutive_failures": 0, "last_error_code": None,
                "running_age_seconds": None, "success_age_seconds": None,
                "scan_interval_seconds": scan_interval_seconds}
    return {**dict(row), "scan_interval_seconds": scan_interval_seconds}


@router.get("/security/notifications")
async def admin_security_notifications(
    request: Request,
    after: datetime | None = Query(default=None),
    after_id: UUID = Query(default=UUID(int=0)),
    limit: int = Query(default=25, ge=1, le=50),
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin_permission("security.alerts.read")),
):
    """Return only new, non-sensitive alert metadata for opt-in browser notifications."""
    effective_after = after
    if effective_after is None:
        effective_after = await session.scalar(text("SELECT clock_timestamp()"))
    if effective_after is None:
        raise HTTPException(status_code=503, detail="Notification cursor time is unavailable")
    if effective_after.tzinfo is None or effective_after.utcoffset() is None:
        raise HTTPException(status_code=422, detail="Notification cursor must include a timezone")
    result = await session.execute(
        text(
            "SELECT id, rule_id, severity, created_at, source FROM ("
            "SELECT id::text AS id, rule_id, severity, created_at, 'alert'::text AS source "
            "FROM lifelink_security_alerts "
            "UNION ALL "
            "SELECT id::text AS id, rule_id, severity, created_at, 'role_policy'::text AS source "
            "FROM lifelink_role_security_findings"
            ") AS new_events WHERE (created_at, id) > (:after, :after_id) "
            "ORDER BY created_at ASC, id ASC LIMIT :limit"
        ),
        {"after": effective_after, "after_id": str(after_id), "limit": limit},
    )
    rows = result.mappings().all()
    items = [
        {
            "id": row["id"],
            "rule_id": row["rule_id"],
            "severity": row["severity"],
            "created_at": row["created_at"],
            "source": row["source"],
        }
        for row in rows
    ]
    cursor = (
        {"created_at": rows[-1]["created_at"], "id": rows[-1]["id"]}
        if rows else {"created_at": effective_after, "id": str(after_id)}
    )
    if items:
        await _write_audit(
            session, principal.subject, "admin.security.notifications.polled", request=request,
            session_id=principal.session_id, target_type="security_notifications",
            metadata={"returned_count": len(items), "source": "browser_notification_poll"},
        )
        await session.commit()
    return {"items": items, "cursor": cursor}


@router.get("/security/alerts")
async def admin_security_alerts(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin_permission("security.alerts.read")),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    alert_status: str | None = Query(default=None, alias="status", pattern="^(open|acknowledged|investigating|resolved|false_positive)$"),
    correlation_id: str | None = Query(default=None, max_length=80),
    x_correlation_id: str | None = Header(default=None),
):
    # Detection runs in the independent lifespan scanner. Keep this GET read-only:
    # alert retrieval must not trigger policy scans or mutate finding state.
    result = await session.execute(
        text(
            "SELECT id, rule_id, severity, status, user_id, session_id, summary, evidence, occurrences, "
            "created_at, last_seen_at, acknowledged_at, acknowledged_by, resolved_at, resolved_by, correlation_id "
            "FROM lifelink_security_alerts "
            "WHERE (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar)) "
            "AND (CAST(:correlation_id AS varchar) IS NULL OR correlation_id = CAST(:correlation_id AS varchar)) "
            "ORDER BY CASE severity WHEN 'critical' THEN 0 WHEN 'high' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END, "
            "last_seen_at DESC LIMIT :limit OFFSET :offset"
        ),
        {"status": alert_status, "correlation_id": correlation_id, "limit": limit, "offset": offset},
    )
    rows = result.mappings().all()
    await _write_audit(
        session, principal.subject, "admin.security.alerts.read", request=request,
        session_id=principal.session_id, session_expires_at=principal.session_expires_at,
        target_type="security_alerts", correlation_id=_correlation_id(x_correlation_id),
        metadata={"limit": limit, "offset": offset, "status": alert_status, "correlation_filtered": correlation_id is not None},
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
    principal: Principal = Depends(require_admin_permission("security.alerts.manage")),
    x_correlation_id: str | None = Header(default=None),
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
        target_type="security_alert", target_id=alert_id,
        correlation_id=_correlation_id(x_correlation_id),
        metadata={"status": payload.status},
    )
    await session.commit()
    return {"id": str(row["id"]), "rule_id": row["rule_id"], "status": row["status"]}


@router.get("/sessions")
async def admin_list_sessions(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin_permission("security.sessions.read")),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    correlation_id: str | None = Query(default=None, max_length=80),
    x_correlation_id: str | None = Header(default=None),
):
    result = await session.execute(
        text(
            "SELECT s.session_id, s.user_id, s.device_used, s.ip_address, s.created_at, s.last_seen_at, "
            "s.expires_at, s.revoked_at, s.revoked_by, s.status, "
            "COALESCE(matched.correlation_id, latest.correlation_id) AS last_correlation_id "
            "FROM lifelink_user_sessions s "
            "LEFT JOIN LATERAL ("
            "SELECT ae.correlation_id FROM audit_events ae "
            "WHERE ((ae.target_type = 'user_session' AND ae.target_id = CAST(s.session_id AS varchar)) OR (COALESCE(ae.target_type, '') <> 'user_session' AND ae.session_id = s.session_id)) "
            "AND ae.correlation_id IS NOT NULL "
            "ORDER BY ae.timestamp DESC LIMIT 1"
            ") latest ON TRUE "
            "LEFT JOIN LATERAL ("
            "SELECT ae.correlation_id FROM audit_events ae "
            "WHERE ((ae.target_type = 'user_session' AND ae.target_id = CAST(s.session_id AS varchar)) OR (COALESCE(ae.target_type, '') <> 'user_session' AND ae.session_id = s.session_id)) "
            "AND ae.correlation_id = CAST(:correlation_id AS varchar) "
            "ORDER BY ae.timestamp DESC LIMIT 1"
            ") matched ON TRUE "
            "WHERE (CAST(:correlation_id AS varchar) IS NULL OR matched.correlation_id IS NOT NULL) "
            "ORDER BY s.last_seen_at DESC LIMIT :limit OFFSET :offset"
        ),
        {"correlation_id": correlation_id, "limit": limit, "offset": offset},
    )
    rows = result.mappings().all()
    await _write_audit(
        session, principal.subject, "admin.sessions.list", request=request,
        session_id=principal.session_id, session_expires_at=principal.session_expires_at,
        target_type="session_list", correlation_id=_correlation_id(x_correlation_id),
        metadata={"limit": limit, "offset": offset, "correlation_filtered": correlation_id is not None},
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
    principal: Principal = Depends(require_admin_permission("security.sessions.revoke")),
    x_correlation_id: str | None = Header(default=None),
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
        correlation_id=_correlation_id(x_correlation_id),
        metadata={"affected_user_id": row["user_id"]},
    )
    await session.commit()
    return {"session_id": session_id, "status": "revoked"}


@router.get("/audit")
async def admin_audit_logs(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin_permission("audit.read")),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    action: str | None = Query(default=None, max_length=64),
    actor_id: str | None = Query(default=None, max_length=128),
    correlation_id: str | None = Query(default=None, max_length=128),
    x_correlation_id: str | None = Header(default=None),
):
    # Record the read before selecting the page; no endpoint permits audit mutation or deletion.
    await _write_audit(session, principal.subject, "admin.audit.read", request=request, session_id=principal.session_id, session_expires_at=principal.session_expires_at, target_type="audit_log",
                       correlation_id=_correlation_id(x_correlation_id),
                       metadata={"limit": limit, "offset": offset, "filtered": any(value is not None for value in (action, actor_id, correlation_id))})
    query = (
        "SELECT id, log_id, actor_id, action, actions, description, session_id, ip_address, timestamp, device_used, "
            "metadata, created_at, outcome, target_type, target_id, correlation_id "
        "FROM audit_events WHERE (CAST(:action AS varchar) IS NULL OR action = CAST(:action AS varchar)) "
        "AND (CAST(:actor_id AS varchar) IS NULL OR actor_id = CAST(:actor_id AS varchar)) "
        "AND (CAST(:correlation_id AS varchar) IS NULL OR correlation_id = CAST(:correlation_id AS varchar)) "
        "ORDER BY created_at DESC LIMIT :limit OFFSET :offset"
    )
    result = await session.execute(text(query), {"action": action, "actor_id": actor_id, "correlation_id": correlation_id, "limit": limit, "offset": offset})
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
    principal: Principal = Depends(require_admin_permission("audit.read")),
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    correlation_id: str | None = Query(default=None, max_length=128),
):
    result = await session.execute(
        text(
            "SELECT id, log_id, actor_id, action, actions, description, session_id, ip_address, timestamp, device_used, "
            "metadata, created_at, outcome, target_type, target_id, correlation_id "
            "FROM audit_events WHERE (action LIKE 'error.%' OR outcome IN ('error', 'denied')) "
            "AND (CAST(:correlation_id AS varchar) IS NULL OR correlation_id = CAST(:correlation_id AS varchar)) "
            "ORDER BY created_at DESC LIMIT :limit OFFSET :offset"
        ),
        {"correlation_id": correlation_id, "limit": limit, "offset": offset},
    )
    rows = result.mappings().all()
    await _write_audit(
        session, principal.subject, "admin.errors.read", request=request,
        session_id=principal.session_id, session_expires_at=principal.session_expires_at,
        target_type="error_log",
        metadata={"limit": limit, "offset": offset, "filtered": bool(correlation_id)},
    )
    await session.commit()
    return {
        "items": [
            {**dict(row), "metadata": _safe_audit_metadata(row["metadata"]),
             "created_at": row["created_at"].isoformat() if row["created_at"] else None}
            for row in rows
        ],
        "limit": limit,
        "offset": offset,
    }


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
    admin_roles = {"admin"}
    if role_key == "requester":
        return None if profile and profile.get("can_request") else "requester_capability_missing"
    if role_key == "donor":
        return None if profile and (profile.get("can_donate") or profile.get("has_donor_profile")) else "donor_capability_missing"
    if role_key in admin_roles:
        if user_id not in _configured_admin_ids():
            return "account_not_in_admin_allowlist"
        return None
    return "role_has_no_applicability_policy"

async def _record_rbac_mismatch(session, principal, request: Request | None, user_id: str, role_key: str, reason: str,
                               correlation_id: str | None = None) -> None:
    """Persist an IDS finding and deduplicated administrator alert."""
    await _write_audit(session, principal.subject, "security.rbac.role_mismatch", request=request,
        session_id=principal.session_id, outcome="denied", target_type="user", target_id=user_id,
        correlation_id=correlation_id,
        metadata={"role_key": role_key, "mismatch_reason": reason, "rule_id": "RBAC-01"})
    await session.execute(text(
        "INSERT INTO lifelink_security_alerts "
        "(rule_id, severity, status, user_id, session_id, summary, evidence, occurrences, correlation_id) "
        "VALUES ('RBAC-01', 'high', 'open', CAST(:user_id AS varchar), :session_id, "
        "'Role assignment is not applicable to this account', CAST(:evidence AS jsonb), 1, :correlation_id) "
        "ON CONFLICT (rule_id, user_id) WHERE status IN ('open', 'acknowledged', 'investigating') "
        "DO UPDATE SET last_seen_at = now(), "
        "occurrences = lifelink_security_alerts.occurrences + CASE "
        "WHEN lifelink_security_alerts.evidence IS DISTINCT FROM EXCLUDED.evidence THEN 1 ELSE 0 END, "
        "session_id = EXCLUDED.session_id, evidence = EXCLUDED.evidence, "
        "correlation_id = COALESCE(EXCLUDED.correlation_id, lifelink_security_alerts.correlation_id)"
    ), {"user_id": user_id, "session_id": principal.session_id,
        "evidence": __import__("json").dumps({"role_key": role_key, "mismatch_reason": reason}),
        "correlation_id": correlation_id or (_correlation_id(request.headers.get("x-correlation-id")) if request else None)})

class AdminUserRolesUpdate(BaseModel):
    # An empty list is a deliberate revoke-all operation; it must remain possible
    # to remove stale or excessive role assignments without assigning a replacement.
    role_keys: list[str] = Field(max_length=3)


@router.get("/roles")
async def admin_list_roles(
    request: Request,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(require_admin_permission("roles.read")),
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
    principal: Principal = Depends(require_admin_permission("roles.read")),
):
    result = await session.execute(
        text(
            "SELECT r.role_key, r.display_name, ur.assigned_at, ur.expires_at "
            "FROM lifelink_user_roles ur JOIN lifelink_roles r ON r.id = ur.role_id "
            "WHERE ur.user_id = CAST(:user_id AS uuid) "
            "AND (ur.expires_at IS NULL OR ur.expires_at > statement_timestamp()) "
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
            "EXISTS (SELECT 1 FROM donor_profiles dp WHERE dp.user_id = CAST(:user_id AS varchar)) "
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
            await _record_rbac_mismatch(
                session, principal, request, user_id, role_key, reason,
                _correlation_id(request.headers.get("x-correlation-id")),
            )
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
            "AND (ur.expires_at IS NULL OR ur.expires_at > statement_timestamp())"
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
