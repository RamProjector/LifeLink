from __future__ import annotations

import os
from dataclasses import dataclass

from fastapi import Header, HTTPException, status


@dataclass(frozen=True)
class Principal:
    subject: str


def auth_required() -> bool:
    return os.getenv("LIFELINK_AUTH_REQUIRED", "false").lower() == "true"


def get_principal(authorization: str | None = Header(default=None)) -> Principal:
    """Development-friendly auth seam.

    Local tests and demos remain anonymous by default. Set LIFELINK_AUTH_REQUIRED=true
    in deployed environments and replace token parsing with Firebase/JWT verification.
    """
    if not authorization:
        if auth_required():
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Bearer authentication required")
        return Principal(subject="development-user")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Use a Bearer access token")
    return Principal(subject=token.strip())


def require_owner(principal: Principal, resource_owner_id: str) -> None:
    """Temporary ownership boundary until verified JWT subject claims are connected."""
    if auth_required() and principal.subject != resource_owner_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You do not own this resource")
