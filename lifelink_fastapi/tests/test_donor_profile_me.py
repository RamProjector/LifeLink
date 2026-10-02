"""Tests for the separate donor-profile flow (opt in after account creation).

Covers:
  * create / get / update the current user's donor profile,
  * the availability toggle,
  * the verified + available gating in matching (unverified and unavailable
    profile donors are excluded; a verified + available one is included),
  * opt-out stops matching,
  * matching recipients only ever see the minimum donor details (no PII).

These run against a real PostgreSQL via pgserver, like the other end-to-end
tests, so the enum columns and the operational-donor sync are exercised for
real. Without pgserver the module is skipped.
"""

from __future__ import annotations

import asyncio
import tempfile
import uuid
from datetime import UTC, datetime, timedelta
from urllib.parse import parse_qs, urlsplit

import pytest

pgserver = pytest.importorskip("pgserver")

import httpx
from sqlalchemy import text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app import db_models
from app.db import get_db_session
from app.main_postgres import app
from app.security import Principal, get_postgres_principal

db_models.GeographyPoint.get_col_spec = lambda self, **kw: "TEXT"  # type: ignore[method-assign]


@pytest.fixture(scope="module")
def pg_url() -> str:
    server = pgserver.get_server(tempfile.mkdtemp(prefix="lifelink-dp-pg-"))
    host = parse_qs(urlsplit(server.get_uri()).query)["host"][0]
    yield f"postgresql+asyncpg://postgres@/postgres?host={host}"
    server.cleanup()


def run_scenario(pg_url: str, scenario):
    async def main():
        engine = create_async_engine(pg_url)
        async with engine.begin() as conn:
            await conn.execute(text("DROP SCHEMA public CASCADE"))
            await conn.execute(text("CREATE SCHEMA public"))
            await conn.run_sync(db_models.Base.metadata.create_all)
        sessions = async_sessionmaker(engine, expire_on_commit=False, autoflush=False)
        current = {"id": "nobody"}

        async def session_dependency():
            async with sessions() as session:
                yield session

        app.dependency_overrides[get_db_session] = session_dependency
        app.dependency_overrides[get_postgres_principal] = lambda: Principal(
            subject=current["id"], email=f"{current['id']}@example.com"
        )
        transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
        try:
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:

                async def run_sql(statement: str):
                    async with engine.begin() as conn:
                        await conn.execute(text(statement))

                await scenario(client, current, run_sql)
        finally:
            app.dependency_overrides.clear()
            await engine.dispose()

    asyncio.run(main())


def new_user(label: str) -> str:
    return f"{label}-{uuid.uuid4().hex[:8]}"


def request_payload(requester_id: str, *, hours: float = 1.0) -> dict:
    return {
        "requester_id": requester_id,
        "blood_type": "O+",
        "units": 2,
        "urgency": "urgent",
        "response_deadline": (datetime.now(UTC) + timedelta(hours=hours)).isoformat(),
        "location": {
            "facility_id": None,
            "facility_name": "Test Hospital",
            "area": "Tacloban",
            "latitude": 11.2433,
            "longitude": 125.0,
            "precision_meters": 100,
            "verified": False,
        },
        "contact_method": "in_app",
        "note": "",
        "genuine_request_confirmed": True,
        "sharing_consent_confirmed": True,
        "ai_matching_enabled": True,
        "idempotency_key": uuid.uuid4().hex,
    }


async def create_account(client, current, user_id: str) -> None:
    """A normal account first — no donor credentials required."""
    current["id"] = user_id
    response = await client.put(
        "/v1/profile",
        json={"role": "requester", "display_name": "Test User", "can_request": True, "can_donate": False},
    )
    assert response.status_code == 200, response.text


async def opt_in_donor(client, current, user_id: str, *, availability: str = "available", blood_type: str = "O+") -> dict:
    current["id"] = user_id
    response = await client.put(
        "/v1/donor-profile",
        json={
            "blood_type": blood_type,
            "latitude": 11.2440,
            "longitude": 125.0010,
            "area": "Tacloban",
            "service_radius_km": 15,
            "availability_status": availability,
            "notifications_enabled": True,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


async def submit_request(client, current, requester_id: str, **kwargs) -> dict:
    current["id"] = requester_id
    response = await client.post("/v1/emergency-requests", json=request_payload(requester_id, **kwargs))
    assert response.status_code == 201, response.text
    return response.json()


def test_account_creation_does_not_require_donor_credentials(pg_url):
    """A user can create an account and use the app without a donor profile."""

    async def scenario(client, current, run_sql):
        user = new_user("user")
        await create_account(client, current, user)

        # No donor profile exists yet.
        current["id"] = user
        missing = await client.get("/v1/donor-profile")
        assert missing.status_code == 404

        # The account can still create a request.
        created = await submit_request(client, current, user)
        assert created["request_id"]

    run_scenario(pg_url, scenario)


def test_donor_profile_create_get_and_update(pg_url):
    async def scenario(client, current, run_sql):
        user = new_user("donor")
        await create_account(client, current, user)

        created = await opt_in_donor(client, current, user)
        assert created["user_id"] == user
        assert created["blood_type"] == "O+"
        assert created["availability_status"] == "available"
        assert created["verified"] is False, "a client can never mark itself verified"
        assert created["notifications_enabled"] is True

        current["id"] = user
        fetched = await client.get("/v1/donor-profile")
        assert fetched.status_code == 200, fetched.text
        assert fetched.json()["blood_type"] == "O+"

        # Update: change blood type and turn notifications off.
        updated = await client.put(
            "/v1/donor-profile",
            json={
                "blood_type": "A-",
                "latitude": 11.25,
                "longitude": 125.01,
                "area": "Palo",
                "service_radius_km": 20,
                "availability_status": "paused",
                "notifications_enabled": False,
            },
        )
        assert updated.status_code == 200, updated.text
        body = updated.json()
        assert body["blood_type"] == "A-"
        assert body["area"] == "Palo"
        assert body["availability_status"] == "paused"
        assert body["notifications_enabled"] is False
        assert body["verified"] is False

    run_scenario(pg_url, scenario)


def test_availability_toggle(pg_url):
    async def scenario(client, current, run_sql):
        user = new_user("donor")
        await create_account(client, current, user)
        await opt_in_donor(client, current, user, availability="offline")

        current["id"] = user
        toggled = await client.patch("/v1/donor-profile/availability", json={"availability_status": "available"})
        assert toggled.status_code == 200, toggled.text
        assert toggled.json()["availability_status"] == "available"

        toggled = await client.patch("/v1/donor-profile/availability", json={"availability_status": "paused"})
        assert toggled.status_code == 200, toggled.text
        assert toggled.json()["availability_status"] == "paused"

    run_scenario(pg_url, scenario)


def test_unverified_profile_donor_is_never_matched(pg_url):
    """The core gating rule: an unverified profile donor is excluded."""

    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await create_account(client, current, donor)
        # Opts in and turns availability on, but is not verified yet.
        await opt_in_donor(client, current, donor, availability="available")

        created = await submit_request(client, current, requester)
        assert created["matches"] == [], "an unverified donor must not be matched"

        # The operational donor row must not be marked available either.
        async with create_async_engine(pg_url).connect() as conn:
            row = (
                await conn.execute(
                    text("SELECT available, verified FROM donors WHERE user_id = :u"), {"u": donor}
                )
            ).one()
        assert row[0] is False, "an unverified donor must never be marked available"
        assert row[1] is False

    run_scenario(pg_url, scenario)


def test_verified_and_available_profile_donor_is_matched(pg_url):
    """Once verified and available, the profile donor is matched."""

    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await create_account(client, current, donor)
        await opt_in_donor(client, current, donor, availability="available")

        # An admin verifies the donor (server-controlled), then the donor
        # re-asserts availability so the operational row picks up the change.
        await run_sql(f"UPDATE donor_profiles SET verified = TRUE WHERE user_id = '{donor}'")
        current["id"] = donor
        toggled = await client.patch("/v1/donor-profile/availability", json={"availability_status": "available"})
        assert toggled.status_code == 200, toggled.text
        assert toggled.json()["verified"] is True

        created = await submit_request(client, current, requester)
        assert [m["donor_id"] for m in created["matches"]] == [donor]

        # The donor sees the request in their inbox.
        current["id"] = donor
        inbox = await client.get(f"/v1/donors/{donor}/requests")
        assert inbox.status_code == 200, inbox.text
        assert [item["request_id"] for item in inbox.json()] == [created["request_id"]]

    run_scenario(pg_url, scenario)


def test_verified_but_unavailable_profile_donor_is_excluded(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await create_account(client, current, donor)
        await opt_in_donor(client, current, donor, availability="available")
        await run_sql(f"UPDATE donor_profiles SET verified = TRUE WHERE user_id = '{donor}'")

        # Verified, but the donor pauses availability.
        current["id"] = donor
        await client.patch("/v1/donor-profile/availability", json={"availability_status": "paused"})

        created = await submit_request(client, current, requester)
        assert created["matches"] == [], "a verified but unavailable donor must not be matched"

    run_scenario(pg_url, scenario)


def test_blood_type_mismatch_is_excluded(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await create_account(client, current, donor)
        # Requester needs O+; an AB- donor is not compatible.
        await opt_in_donor(client, current, donor, availability="available", blood_type="AB-")
        await run_sql(f"UPDATE donor_profiles SET verified = TRUE WHERE user_id = '{donor}'")
        current["id"] = donor
        await client.patch("/v1/donor-profile/availability", json={"availability_status": "available"})

        created = await submit_request(client, current, requester)
        assert created["matches"] == [], "an incompatible blood type must not be matched"

    run_scenario(pg_url, scenario)


def test_opt_out_stops_matching(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await create_account(client, current, donor)
        await opt_in_donor(client, current, donor, availability="available")
        await run_sql(f"UPDATE donor_profiles SET verified = TRUE WHERE user_id = '{donor}'")
        current["id"] = donor
        await client.patch("/v1/donor-profile/availability", json={"availability_status": "available"})

        created = await submit_request(client, current, requester)
        assert [m["donor_id"] for m in created["matches"]] == [donor]

        # Opt out.
        current["id"] = donor
        removed = await client.delete("/v1/donor-profile")
        assert removed.status_code == 204, removed.text
        assert (await client.get("/v1/donor-profile")).status_code == 404

        # A new request no longer matches the opted-out donor.
        created = await submit_request(client, current, requester)
        assert created["matches"] == []

    run_scenario(pg_url, scenario)


def test_matching_recipient_sees_only_minimum_donor_details(pg_url):
    """PII privacy: a match exposes no coordinates, contact details, or email."""

    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await create_account(client, current, donor)
        await opt_in_donor(client, current, donor, availability="available")
        await run_sql(f"UPDATE donor_profiles SET verified = TRUE WHERE user_id = '{donor}'")
        current["id"] = donor
        await client.patch("/v1/donor-profile/availability", json={"availability_status": "available"})

        created = await submit_request(client, current, requester)
        assert len(created["matches"]) == 1
        match = created["matches"][0]
        # Minimum necessary details only.
        assert set(match.keys()) <= {
            "donor_id",
            "display_name",
            "blood_type",
            "distance_km",
            "estimated_travel_minutes",
            "score",
            "explanation",
        }
        for forbidden in ("latitude", "longitude", "email", "phone", "contact_email", "fcm_token"):
            assert forbidden not in match, f"match must not expose {forbidden}"

    run_scenario(pg_url, scenario)


def test_donor_profile_requires_authentication(pg_url):
    async def scenario(client, current, run_sql):
        current["id"] = "development-user"
        response = await client.put(
            "/v1/donor-profile",
            json={
                "blood_type": "O+",
                "latitude": 11.24,
                "longitude": 125.0,
                "service_radius_km": 15,
                "availability_status": "available",
            },
        )
        assert response.status_code == 401

    run_scenario(pg_url, scenario)


def test_unknown_blood_type_is_rejected(pg_url):
    async def scenario(client, current, run_sql):
        user = new_user("donor")
        await create_account(client, current, user)
        current["id"] = user
        response = await client.put(
            "/v1/donor-profile",
            json={
                "blood_type": "UNKNOWN",
                "latitude": 11.24,
                "longitude": 125.0,
                "service_radius_km": 15,
                "availability_status": "available",
            },
        )
        assert response.status_code == 400

    run_scenario(pg_url, scenario)
