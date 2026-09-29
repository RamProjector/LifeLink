"""Security tests for the account-verification gate and principal hardening.

These cover the fix for the reported bug where an unverified account could be
saved or authenticated: the API now refuses to persist or serve a profile for a
principal whose token explicitly marks the email unverified, and a supplied
Bearer token is always verified rather than trusted verbatim.
"""
from __future__ import annotations

import asyncio

import pytest
from fastapi import HTTPException

from app.main_postgres import ProfileIn, get_profile, upsert_profile
from app.security import (
    Principal,
    _claim_says_unverified,
    _get_principal,
    _has_verification_claim,
    require_verified_email,
)


class _StubSession:
    """Minimal session stub: the verification gate runs before any DB access."""

    async def get(self, model, key):
        return None


# --------------------------------------------------------------- claim parsing

def test_absent_verification_claim_is_unknown_not_unverified():
    # Supabase access tokens carry no verification claim by default; the issuer
    # enforces verification, so an absent claim must not be treated as unverified.
    assert _has_verification_claim({}) is False
    assert _claim_says_unverified({}) is False


def test_explicit_false_claims_are_unverified():
    assert _claim_says_unverified({"email_verified": False}) is True
    assert _claim_says_unverified({"email_confirmed": False}) is True
    assert _claim_says_unverified({"email_confirmed_at": None}) is True


def test_verified_claims_are_not_unverified():
    assert _claim_says_unverified({"email_verified": True}) is False
    assert _claim_says_unverified({"email_confirmed_at": "2026-01-01T00:00:00Z"}) is False


# ------------------------------------------------------------ principal helper

def test_require_verified_email_rejects_explicitly_unverified():
    with pytest.raises(HTTPException) as exc:
        require_verified_email(Principal(subject="u1", email_verified=False))
    assert exc.value.status_code == 403


def test_require_verified_email_allows_unknown_and_verified():
    require_verified_email(Principal(subject="u1", email_verified=None))
    require_verified_email(Principal(subject="u1", email_verified=True))


def test_supplied_bearer_token_is_never_trusted_verbatim():
    # With auth disabled for local testing, a supplied token must still be
    # verified instead of being used as the subject (the old impersonation bug).
    with pytest.raises(HTTPException):
        _get_principal("Bearer victim-user-id", required=False, verify_when_present=True)


def test_missing_authorization_still_falls_back_when_not_required():
    principal = _get_principal(None, required=False, verify_when_present=True)
    assert principal.subject == "development-user"


# ------------------------------------------------------------- endpoint gating

def test_upsert_profile_rejects_unverified_principal():
    principal = Principal(subject="user-1", email="u@example.com", email_verified=False)
    with pytest.raises(HTTPException) as exc:
        asyncio.run(upsert_profile(ProfileIn(role="requester"), session=None, principal=principal))
    assert exc.value.status_code == 403


def test_get_profile_rejects_unverified_principal():
    principal = Principal(subject="user-1", email="u@example.com", email_verified=False)
    with pytest.raises(HTTPException) as exc:
        asyncio.run(get_profile(session=None, principal=principal))
    assert exc.value.status_code == 403


def test_get_profile_allows_verified_principal_past_the_gate():
    # A verified principal passes the gate and reaches the (empty) store, which
    # reports 404 rather than the 403 reserved for unverified accounts.
    principal = Principal(subject="user-1", email="u@example.com", email_verified=True)
    with pytest.raises(HTTPException) as exc:
        asyncio.run(get_profile(session=_StubSession(), principal=principal))
    assert exc.value.status_code == 404
