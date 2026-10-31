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
from typing import Any
from urllib.parse import parse_qs, urlsplit

import pytest

pgserver = pytest.importorskip("pgserver")

import httpx
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app import db, db_models
from app.db import get_db_session
from app.donor_profile_repositories import SqlAlchemyDonorProfileStore
from app.donor_repositories import SqlAlchemyDonorStore
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
        current: dict[str, Any] = {"id": "nobody", "_session_factory": sessions}

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
    # The normal app follows profile save with a dedicated GPS presence write.
    # Keeping this in the helper makes matching tests model the real client flow.
    current["id"] = user_id
    presence = await client.post(
        "/v1/donor-profile/presence",
        json={"latitude": 11.2440, "longitude": 125.0010, "precision_meters": 35},
    )
    assert presence.status_code == 200, presence.text
    return response.json()


async def submit_request(client, current, requester_id: str, **kwargs) -> dict:
    current["id"] = requester_id
    response = await client.post("/v1/emergency-requests", json=request_payload(requester_id, **kwargs))
    assert response.status_code == 201, response.text
    return response.json()


def test_presence_backfill_only_runs_when_column_is_added(pg_url, monkeypatch):
    async def scenario():
        engine = create_async_engine(pg_url)
        monkeypatch.setattr(db, "engine", engine)
        try:
            async with engine.begin() as connection:
                await connection.execute(text("DROP SCHEMA public CASCADE"))
                await connection.execute(text("CREATE SCHEMA public"))
                await connection.run_sync(db_models.Base.metadata.create_all)
                # Simulate a database from before last_presence_at was introduced.
                await connection.execute(text("ALTER TABLE donors DROP COLUMN last_presence_at CASCADE"))
                await connection.execute(text(
                    "INSERT INTO donors (id, user_id, display_name, blood_type, latitude, longitude, "
                    "available, availability_updated_at, verified, service_radius_km, "
                    "last_location_precision_meters, estimated_response_probability, donor_note, "
                    "preferred_contact_method, profile_visible, map_visible, exact_location_sharing_enabled) "
                    "VALUES ('legacy-backfill-test', 'legacy-backfill-test', 'Legacy donor', 'O+', "
                    "11.244, 125.001, TRUE, now(), FALSE, 15, 500, 0.5, '', 'in_app', TRUE, FALSE, FALSE)"
                ))

            await db.create_all_tables()
            async with engine.begin() as connection:
                backfilled = await connection.scalar(text(
                    "SELECT last_presence_at = availability_updated_at "
                    "FROM donors WHERE id = 'legacy-backfill-test'"
                ))
                await connection.execute(text(
                    "UPDATE donors SET last_presence_at = NULL, availability_updated_at = now() "
                    "WHERE id = 'legacy-backfill-test'"
                ))

            # A later startup must not reinterpret a recent availability change as a GPS fix.
            await db.create_all_tables()
            async with engine.begin() as connection:
                remains_null = await connection.scalar(text(
                    "SELECT last_presence_at IS NULL FROM donors WHERE id = 'legacy-backfill-test'"
                ))
            assert backfilled is True
            assert remains_null is True
        finally:
            await engine.dispose()

    asyncio.run(scenario())


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



def test_availability_mirror_failure_rolls_back_profile_preference(pg_url, monkeypatch):
    original_get_by_identity = SqlAlchemyDonorStore.get_by_identity

    async def fail_mirror(self, user_id: str):
        raise RuntimeError("synthetic operational-row mirror failure")

    async def scenario(client, current, run_sql):
        user = new_user("donor-availability-atomic")
        await create_account(client, current, user)
        await opt_in_donor(client, current, user, availability="offline")

        monkeypatch.setattr(SqlAlchemyDonorStore, "get_by_identity", fail_mirror)
        current["id"] = user
        failed = await client.patch(
            "/v1/donor-profile/availability",
            json={"availability_status": "available"},
        )
        assert failed.status_code >= 500

        monkeypatch.setattr(SqlAlchemyDonorStore, "get_by_identity", original_get_by_identity)
        current["id"] = user
        profile = await client.get("/v1/donor-profile")
        assert profile.status_code == 200, profile.text
        assert profile.json()["availability_status"] == "offline"

        async with current["_session_factory"]() as session:
            donor_row = await original_get_by_identity(SqlAlchemyDonorStore(session), user)
        assert donor_row is not None
        assert donor_row.available is False

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


def test_profile_save_without_gps_presence_is_not_matchable(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await create_account(client, current, donor)
        current["id"] = donor
        created = await client.put(
            "/v1/donor-profile",
            json={
                "blood_type": "O+",
                "latitude": 11.2440,
                "longitude": 125.0010,
                "area": "Tacloban",
                "service_radius_km": 15,
                "availability_status": "available",
                "notifications_enabled": True,
            },
        )
        assert created.status_code == 200, created.text
        await run_sql(f"UPDATE donor_profiles SET verified = TRUE WHERE user_id = '{donor}'")
        enabled = await client.patch(
            "/v1/donor-profile/availability",
            json={"availability_status": "available"},
        )
        assert enabled.status_code == 200, enabled.text

        # Profile saves and availability toggles must not impersonate a GPS fix.
        created_request = await submit_request(client, current, requester)
        assert created_request["matches"] == [], "a donor without GPS presence must not be matchable"

    run_scenario(pg_url, scenario)


def test_stale_donor_presence_is_excluded_from_matching(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await create_account(client, current, donor)
        await opt_in_donor(client, current, donor, availability="available")
        await run_sql(f"UPDATE donor_profiles SET verified = TRUE WHERE user_id = '{donor}'")
        current["id"] = donor
        changed = await client.patch(
            "/v1/donor-profile/availability",
            json={"availability_status": "available"},
        )
        assert changed.status_code == 200, changed.text

        # A stale availability flag must not keep an old location matchable.
        await run_sql(
            f"UPDATE donors SET last_presence_at = now() - interval '2 days' "
            f"WHERE id = '{donor}'"
        )
        created = await submit_request(client, current, requester)
        assert created["matches"] == [], "stale donor presence must be excluded"

    run_scenario(pg_url, scenario)


def test_presence_sweeper_expires_stale_donor_but_keeps_fresh_donor(pg_url):
    async def scenario(client, current, run_sql):
        stale_donor, fresh_donor = new_user("stale"), new_user("fresh")
        for donor in (stale_donor, fresh_donor):
            await create_account(client, current, donor)
            await opt_in_donor(client, current, donor, availability="offline")
            await run_sql(f"UPDATE donor_profiles SET verified = TRUE WHERE user_id = '{donor}'")
            current["id"] = donor
            enabled = await client.patch(
                "/v1/donor-profile/availability",
                json={"availability_status": "available"},
            )
            assert enabled.status_code == 200, enabled.text

        await run_sql(
            f"UPDATE donors SET last_presence_at = now() - interval '2 days' "
            f"WHERE user_id = '{stale_donor}'"
        )
        async with current["_session_factory"]() as session:
            expired_count = await SqlAlchemyDonorProfileStore(session).expire_stale_presence()
            stale_row = await session.scalar(
                select(db_models.Donor).where(db_models.Donor.user_id == stale_donor)
            )
            fresh_row = await session.scalar(
                select(db_models.Donor).where(db_models.Donor.user_id == fresh_donor)
            )

        assert expired_count == 1
        assert stale_row is not None and stale_row.available is False
        assert fresh_row is not None and fresh_row.available is True

    run_scenario(pg_url, scenario)



def test_presence_sweeper_preserves_heartbeat_after_candidate_select(pg_url, monkeypatch):
    async def scenario(client, current, run_sql):
        donor = new_user("donor-heartbeat-race")
        await create_account(client, current, donor)
        await opt_in_donor(client, current, donor, availability="available")
        await run_sql(f"UPDATE donor_profiles SET verified = TRUE WHERE user_id = '{donor}'")
        current["id"] = donor
        enabled = await client.patch(
            "/v1/donor-profile/availability",
            json={"availability_status": "available"},
        )
        assert enabled.status_code == 200, enabled.text
        await run_sql(
            f"UPDATE donors SET last_presence_at = now() - interval '2 days' "
            f"WHERE user_id = '{donor}'"
        )

        heartbeat_at = datetime.now(UTC)
        heartbeat_written = False
        async with current["_session_factory"]() as session:
            original_scalars = session.scalars

            async def scalars_with_concurrent_heartbeat(statement, *args, **kwargs):
                nonlocal heartbeat_written
                result = await original_scalars(statement, *args, **kwargs)
                if not heartbeat_written:
                    async with current["_session_factory"]() as heartbeat_session:
                        await heartbeat_session.execute(
                            text(
                                "UPDATE donors SET last_presence_at = :heartbeat_at "
                                "WHERE user_id = :user_id"
                            ),
                            {"heartbeat_at": heartbeat_at, "user_id": donor},
                        )
                        await heartbeat_session.commit()
                    heartbeat_written = True
                return result

            monkeypatch.setattr(session, "scalars", scalars_with_concurrent_heartbeat)
            expired_count = await SqlAlchemyDonorProfileStore(session).expire_stale_presence()

        assert heartbeat_written
        async with current["_session_factory"]() as verify_session:
            donor_row = await verify_session.scalar(
                select(db_models.Donor).where(db_models.Donor.user_id == donor)
            )
        assert expired_count == 0
        assert donor_row is not None and donor_row.available is True
        assert donor_row.last_presence_at == heartbeat_at

    run_scenario(pg_url, scenario)


def test_donor_presence_updates_location_and_server_freshness(pg_url):
    async def scenario(client, current, run_sql):
        donor = new_user("donor")
        await create_account(client, current, donor)
        await opt_in_donor(client, current, donor, availability="available")
        await run_sql(f"UPDATE donor_profiles SET verified = TRUE WHERE user_id = '{donor}'")
        current["id"] = donor
        enabled = await client.patch(
            "/v1/donor-profile/availability",
            json={"availability_status": "available"},
        )
        assert enabled.status_code == 200, enabled.text

        response = await client.post(
            "/v1/donor-profile/presence",
            json={
                "latitude": 11.2501,
                "longitude": 125.0102,
                "precision_meters": 35,
            },
        )
        assert response.status_code == 200, response.text
        assert response.json()["latitude"] == pytest.approx(11.2501)
        assert response.json()["longitude"] == pytest.approx(125.0102)

        # Prove a fresh, verified, explicitly available donor is matchable first.
        current["id"] = new_user("requester")
        fresh = await submit_request(client, current, new_user("requester"))
        assert [match["donor_id"] for match in fresh["matches"]] == [donor]

        # Then make the server-owned presence timestamp stale and ensure matching
        # rejects the same donor even though their explicit preference is available.
        await run_sql(
            f"UPDATE donors SET last_presence_at = now() - interval '2 days' "
            f"WHERE id = '{donor}'"
        )
        stale = await submit_request(client, current, new_user("requester"))
        assert stale["matches"] == [], "server-side stale presence must be excluded"

    run_scenario(pg_url, scenario)
