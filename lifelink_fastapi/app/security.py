from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache

import jwt
from fastapi import Header, HTTPException, status
from jwt import PyJWKClient


@dataclass(frozen=True)
class Principal:
    subject: str
    email: str | None = None
    role: str | None = None
    # None means "the issuer did not state it"; False means explicitly unverified.
    email_verified: bool | None = None


def auth_required(default: bool = False) -> bool:
    return os.getenv("LIFELINK_AUTH_REQUIRED", str(default).lower()).lower() == "true"


def _supabase_url() -> str:
    return os.getenv("SUPABASE_URL", "").rstrip("/")


@lru_cache(maxsize=1)
def _jwks_client() -> PyJWKClient:
    url = _supabase_url()
    if not url:
        raise RuntimeError("SUPABASE_URL must be configured when LIFELINK_AUTH_REQUIRED=true")
    return PyJWKClient(f"{url}/auth/v1/.well-known/jwks.json", cache_keys=True)


def _verify_supabase_token(token: str) -> Principal:
    url = _supabase_url()
    if not url:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Supabase authentication is not configured",
        )
    try:
        signing_key = _jwks_client().get_signing_key_from_jwt(token)
        claims = jwt.decode(
            token,
            signing_key.key,
            algorithms=["ES256", "RS256"],
            audience=os.getenv("SUPABASE_JWT_AUDIENCE", "authenticated"),
            issuer=os.getenv("SUPABASE_JWT_ISSUER", f"{url}/auth/v1"),
            options={"require": ["exp", "iat", "sub"]},
        )
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired Supabase access token",
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc

    subject = claims.get("sub")
    if not isinstance(subject, str) or not subject:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token subject is missing")

    # Defense in depth: an unverified account must never be treated as an
    # authenticated principal. Supabase already refuses to issue a session to an
    # unverified user (mailer_allow_unverified_email_sign_ins=false), so this is
    # a second gate that also covers a custom access-token hook that surfaces the
    # claim. A token that carries no verification claim at all is left to the
    # issuer's own enforcement rather than being rejected outright.
    if _claim_says_unverified(claims):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Email address is not verified",
        )

    user_metadata = claims.get("user_metadata") or {}
    app_metadata = claims.get("app_metadata") or {}
    return Principal(
        subject=subject,
        email=claims.get("email"),
        role=app_metadata.get("role") or user_metadata.get("role"),
        email_verified=not _claim_says_unverified(claims) if _has_verification_claim(claims) else None,
    )


def _has_verification_claim(claims: dict) -> bool:
    return any(key in claims for key in ("email_verified", "email_confirmed", "email_confirmed_at"))


def _claim_says_unverified(claims: dict) -> bool:
    """True only when the token explicitly states the email is not verified.

    Supabase access tokens do not carry ``email_confirmed_at`` by default, so an
    absent claim is treated as "unknown" (the issuer enforces verification) and
    only an explicit ``false`` is rejected.
    """
    for key in ("email_verified", "email_confirmed"):
        value = claims.get(key)
        if value is False:
            return True
    return claims.get("email_confirmed_at") is None and "email_confirmed_at" in claims


def _get_principal(
    authorization: str | None, required: bool, verify_when_present: bool = False
) -> Principal:
    if not authorization:
        if required:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Bearer authentication required",
                headers={"WWW-Authenticate": "Bearer"},
            )
        return Principal(subject="development-user")

    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Use a Bearer access token")

    # A supplied Bearer token is ALWAYS verified when the caller asks for it.
    # Trusting the raw token string as the subject (the old behaviour when auth
    # was not required) let anyone impersonate any user id by sending
    # ``Authorization: Bearer <victim-user-id>``.
    if required or verify_when_present:
        return _verify_supabase_token(token.strip())
    return Principal(subject=token.strip())


def get_principal(authorization: str | None = Header(default=None)) -> Principal:
    """In-memory/demo dependency; anonymous access is allowed unless explicitly enabled."""
    return _get_principal(authorization, auth_required())


def get_postgres_principal(authorization: str | None = Header(default=None)) -> Principal:
    """Production dependency; authentication is required unless explicitly disabled for local testing.

    Even when authentication is disabled for local testing, a Bearer token that
    is actually supplied is still verified rather than trusted verbatim.
    """
    return _get_principal(authorization, auth_required(default=True), verify_when_present=True)


def require_verified_email(principal: Principal) -> None:
    """Reject a principal whose token explicitly marks the email unverified.

    ``_verify_supabase_token`` already enforces this for real tokens; this helper
    lets an endpoint re-assert the rule for principals built by other paths.
    """
    if getattr(principal, "email_verified", None) is False:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Email address is not verified",
        )


def require_owner(principal: Principal, resource_owner_id: str) -> None:
    if auth_required() and principal.subject != resource_owner_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You do not own this resource")


def require_role(principal: Principal, *roles: str) -> None:
    if auth_required() and principal.role not in roles:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="This action requires an authorized role")
