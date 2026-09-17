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


def auth_required() -> bool:
    return os.getenv("LIFELINK_AUTH_REQUIRED", "false").lower() == "true"


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
    user_metadata = claims.get("user_metadata") or {}
    app_metadata = claims.get("app_metadata") or {}
    return Principal(
        subject=subject,
        email=claims.get("email"),
        role=app_metadata.get("role") or user_metadata.get("role"),
    )


def get_principal(authorization: str | None = Header(default=None)) -> Principal:
    """Return the authenticated Supabase user, failing closed in deployed mode."""
    if not authorization:
        if auth_required():
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Bearer authentication required",
                headers={"WWW-Authenticate": "Bearer"},
            )
        return Principal(subject="development-user")

    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Use a Bearer access token")

    if auth_required():
        return _verify_supabase_token(token.strip())
    return Principal(subject=token.strip())


def require_owner(principal: Principal, resource_owner_id: str) -> None:
    if auth_required() and principal.subject != resource_owner_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You do not own this resource")


def require_role(principal: Principal, *roles: str) -> None:
    if auth_required() and principal.role not in roles:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="This action requires an authorized role")
