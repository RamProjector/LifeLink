"""End-to-end tests for the confirmed LifeLink privacy behavior.

These run against a real PostgreSQL (via ``pgserver``) so they exercise the
actual enum, timestamptz, and unique-constraint behavior the hosted database
uses. Without ``pgserver`` the module is skipped, so CI and Docker builds are
unaffected.

Covered:
* the donor map exposes approximate areas and freshness only, never exact pins;
* donor map visibility is opt-in and hiding revokes live shares;
* exact location is disclosed only to the matched requester, only while a live
  share exists, and expires when the request ends;
* conversations are scoped to the requester and donor of one request;
* contact details are hidden by default and every explicit share is audited.
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
    server = pgserver.get_server(tempfile.mkdtemp(prefix="lifelink-privacy-pg-"))
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
                        result = await conn.execute(text(statement))
                        return result.fetchall() if result.returns_rows else None

                await scenario(client, current, run_sql)
        finally:
            app.dependency_overrides.clear()
            await engine.dispose()

    asyncio.run(main())


def new_user(label: str) -> str:
    return f"{label}-{uuid.uuid4().hex[:8]}"


def request_payload(requester_id: str, *, blood_type: str = "O+", hours: float = 1.0) -> dict:
    return {
        "requester_id": requester_id,
        "blood_type": blood_type,
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


async def setup_donor(
    client,
    current,
    donor_id: str,
    *,
    blood_type: str = "O+",
    latitude: float = 11.2440,
    longitude: float = 125.0010,
    map_visible: bool = False,
    exact_sharing: bool = False,
) -> None:
    current["id"] = donor_id
    profile = await client.put(
        "/v1/profile",
        json={"role": "donor", "display_name": "Test Donor", "can_request": True, "can_donate": True},
    )
    assert profile.status_code == 200, profile.text
    saved = await client.put(
        f"/v1/donors/{donor_id}",
        json={
            "donor_id": donor_id,
            "display_name": "Test Donor",
            "blood_type": blood_type,
            "latitude": latitude,
            "longitude": longitude,
            "service_radius_km": 15,
            "verified": False,
        },
    )
    assert saved.status_code == 200, saved.text
    available = await client.patch(f"/v1/donors/{donor_id}/availability", json={"availability": "available"})
    assert available.status_code == 200, available.text
    visibility = await client.put(
        f"/v1/donors/{donor_id}/map-visibility",
        json={"map_visible": map_visible, "exact_location_sharing_enabled": exact_sharing},
    )
    assert visibility.status_code == 200, visibility.text
    # Profile and availability writes do not refresh GPS presence; the
    # fixture models the separate location observation sent by the Android app.
    presence = await client.post(
        f"/v1/donors/{donor_id}/presence",
        json={"latitude": latitude, "longitude": longitude, "precision_meters": 35},
    )
    assert presence.status_code == 200, presence.text


async def submit_request(client, current, requester_id: str, **kwargs) -> dict:
    current["id"] = requester_id
    response = await client.post("/v1/emergency-requests", json=request_payload(requester_id, **kwargs))
    assert response.status_code == 201, response.text
    return response.json()


def test_donor_map_shows_approximate_areas_only(pg_url):
    async def scenario(client, current, run_sql):
        hidden, shown = new_user("donor"), new_user("donor")
        await setup_donor(client, current, hidden, map_visible=False)
        await setup_donor(client, current, shown, map_visible=True)

        current["id"] = new_user("viewer")
        response = await client.get("/v1/donor-map")
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["approximate_only"] is True
        assert len(body["entries"]) == 1
        entry = body["entries"][0]
        # Treat the public map response as an allowlisted privacy contract:
        # adding an identity, contact, or exact-location field must fail this test.
        assert set(entry) == {
            "area_label",
            "latitude",
            "longitude",
            "radius_meters",
            "blood_type",
            "availability",
            "freshness_at",
            "freshness_age_minutes",
            "is_stale",
        }
        assert -90 <= entry["latitude"] <= 90
        assert -180 <= entry["longitude"] <= 180
        assert entry["radius_meters"] >= 1000
        # Coarsened to a ~1 km grid, never the exact stored coordinate.
        assert entry["latitude"] == 11.24
        assert entry["longitude"] == 125.0
        assert entry["latitude"] != 11.2440
        # No donor identity is exposed.
        assert "donor_id" not in entry
        assert "display_name" not in entry
        # A freshness timestamp is always present.
        assert entry["freshness_at"] is not None
        assert entry["freshness_age_minutes"] >= 0

    run_scenario(pg_url, scenario)


def test_donor_map_requires_authentication(pg_url):
    async def scenario(client, current, run_sql):
        current["id"] = "development-user"
        response = await client.get("/v1/donor-map")
        assert response.status_code == 401

    run_scenario(pg_url, scenario)


def test_hiding_from_map_revokes_live_location_share(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor, map_visible=True, exact_sharing=True)
        created = await submit_request(client, current, requester)

        location_share_path = (
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location-share"
        )
        current["id"] = stranger
        stranger_activation = await client.post(location_share_path)
        assert stranger_activation.status_code == 403, stranger_activation.text

        current["id"] = requester
        activated = await client.post(location_share_path)
        assert activated.status_code == 200, activated.text
        visible = await client.get(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location"
        )
        assert visible.json()["shared"] is True

        # Donor hides from the map: the live share must be revoked immediately.
        current["id"] = donor
        hidden = await client.put(
            f"/v1/donors/{donor}/map-visibility",
            json={"map_visible": False, "exact_location_sharing_enabled": True},
        )
        assert hidden.status_code == 200, hidden.text

        current["id"] = requester
        # A requester cannot silently reactivate a share after the donor revoked
        # it, even though the donor's account-level opt-in remains enabled.
        reactivated = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location-share"
        )
        assert reactivated.status_code == 409, reactivated.text

        after = await client.get(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location"
        )
        assert after.json()["shared"] is False
        assert after.json()["latitude"] is None
        assert after.json()["longitude"] is None
        assert after.json()["latitude"] is None
        assert after.json()["longitude"] is None

    run_scenario(pg_url, scenario)


def test_exact_location_requires_a_match_and_donor_opt_in(pg_url):
    async def scenario(client, current, run_sql):
        matched, unmatched = new_user("donor"), new_user("donor")
        requester = new_user("requester")
        await setup_donor(client, current, matched, blood_type="O+", exact_sharing=True)
        # A+ donor is not compatible with an O+ request, so it never matches.
        await setup_donor(client, current, unmatched, blood_type="A+", exact_sharing=True)
        created = await submit_request(client, current, requester)
        assert [m["donor_id"] for m in created["matches"]] == [matched]

        current["id"] = requester
        not_matched = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{unmatched}/location-share"
        )
        assert not_matched.status_code == 403

        # A matched donor that has not enabled exact sharing cannot be shared.
        no_optin = new_user("donor")
        await setup_donor(client, current, no_optin, blood_type="O+", exact_sharing=False)
        created2 = await submit_request(client, current, requester)
        assert no_optin in [m["donor_id"] for m in created2["matches"]]
        blocked = await client.post(
            f"/v1/emergency-requests/{created2['request_id']}/donors/{no_optin}/location-share"
        )
        assert blocked.status_code == 409

    run_scenario(pg_url, scenario)



def test_exact_location_is_restricted_to_the_requesting_account(pg_url):
    """A live share must not become readable by a donor or unrelated account.

    Regression coverage for object-level authorization: knowing both the
    request ID and donor ID is not sufficient to retrieve exact GPS coordinates.
    """
    async def scenario(client, current, run_sql):
        donor, requester, stranger = (
            new_user("donor"),
            new_user("requester"),
            new_user("stranger"),
        )
        await setup_donor(
            client,
            current,
            donor,
            latitude=11.2447,
            longitude=125.0023,
            exact_sharing=True,
        )
        created = await submit_request(client, current, requester)

        current["id"] = requester
        activated = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location-share"
        )
        assert activated.status_code == 200, activated.text

        location_path = (
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location"
        )

        # A third party who knows both identifiers still cannot retrieve GPS.
        current["id"] = stranger
        stranger_response = await client.get(location_path)
        assert stranger_response.status_code == 403, stranger_response.text

        # The donor is a participant, but exact coordinates are requester-only.
        current["id"] = donor
        donor_response = await client.get(location_path)
        assert donor_response.status_code == 403, donor_response.text

        # The authorized requester receives the exact coordinates only while
        # the donor's explicit share is active.
        current["id"] = requester
        requester_response = await client.get(location_path)
        assert requester_response.status_code == 200, requester_response.text
        body = requester_response.json()
        assert body["shared"] is True
        assert body["latitude"] == 11.2447
        assert body["longitude"] == 125.0023

    run_scenario(pg_url, scenario)


def test_exact_location_expires_when_request_is_cancelled(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor, exact_sharing=True)
        created = await submit_request(client, current, requester)

        current["id"] = requester
        activated = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location-share"
        )
        assert activated.status_code == 200, activated.text
        assert (
            await client.get(f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location")
        ).json()["shared"] is True

        cancelled = await client.post(f"/v1/emergency-requests/{created['request_id']}/cancel")
        assert cancelled.status_code == 200, cancelled.text

        after = await client.get(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location"
        )
        assert after.json()["shared"] is False
        assert after.json()["latitude"] is None

    run_scenario(pg_url, scenario)


def test_donor_can_revoke_exact_location_share(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor, exact_sharing=True)
        created = await submit_request(client, current, requester)

        current["id"] = requester
        await client.post(f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location-share")

        current["id"] = donor
        revoked = await client.delete(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location-share"
        )
        assert revoked.status_code == 200, revoked.text
        assert revoked.json()["status"] == "revoked"

        current["id"] = requester
        after = await client.get(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location"
        )
        assert after.json()["shared"] is False

        # Defense in depth: even callers that bypass the HTTP pre-check cannot
        # reactivate a revoked consent through the repository write method.
        from app.privacy_repositories import LocationShareRevokedError, SqlAlchemyPrivacyStore

        engine = create_async_engine(pg_url)
        sessions = async_sessionmaker(engine, expire_on_commit=False, autoflush=False)
        with pytest.raises(LocationShareRevokedError):
            async with sessions() as session:
                await SqlAlchemyPrivacyStore(session).ensure_location_share(
                    created["request_id"],
                    donor,
                    requester,
                    datetime.now(UTC) + timedelta(hours=1),
                )
        async with engine.connect() as connection:
            status_result = await connection.execute(
                text(
                    "SELECT status FROM donor_location_shares "
                    "WHERE request_id = :request_id AND donor_id = :donor_id"
                ),
                {"request_id": created["request_id"], "donor_id": donor},
            )
            assert status_result.scalar_one() == "revoked"
        await engine.dispose()

    run_scenario(pg_url, scenario)


def test_conversation_is_scoped_to_the_two_participants(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester, outsider = new_user("donor"), new_user("requester"), new_user("outsider")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)

        current["id"] = requester
        opened = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/conversation"
        )
        assert opened.status_code == 200, opened.text
        conversation_id = opened.json()["conversation_id"]

        sent = await client.post(
            f"/v1/conversations/{conversation_id}/messages",
            json={"body": "Hello, are you available to donate?"},
        )
        assert sent.status_code == 201, sent.text

        # The donor can read and reply.
        current["id"] = donor
        donor_view = await client.get(f"/v1/conversations/{conversation_id}/messages")
        assert donor_view.status_code == 200, donor_view.text
        assert [m["body"] for m in donor_view.json()] == ["Hello, are you available to donate?"]
        reply = await client.post(
            f"/v1/conversations/{conversation_id}/messages", json={"body": "Yes, I can help."}
        )
        assert reply.status_code == 201, reply.text

        # A third party is rejected on both read and write.
        current["id"] = outsider
        assert (await client.get(f"/v1/conversations/{conversation_id}/messages")).status_code == 403
        assert (
            await client.post(f"/v1/conversations/{conversation_id}/messages", json={"body": "hi"})
        ).status_code == 403

        # The requester sees both messages, in order.
        current["id"] = requester
        full = await client.get(f"/v1/conversations/{conversation_id}/messages")
        assert [m["body"] for m in full.json()] == [
            "Hello, are you available to donate?",
            "Yes, I can help.",
        ]

    run_scenario(pg_url, scenario)


def test_contact_details_are_hidden_until_explicitly_shared_and_audited(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)

        current["id"] = requester
        opened = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/conversation"
        )
        conversation_id = opened.json()["conversation_id"]

        # Hidden by default.
        assert (await client.get(f"/v1/conversations/{conversation_id}/contact-shares")).json() == []

        shared = await client.post(
            f"/v1/conversations/{conversation_id}/contact-shares",
            json={"field": "phone", "value": "+63 900 000 0000"},
        )
        assert shared.status_code == 201, shared.text
        assert shared.json()["field"] == "phone"

        # The other participant can read the shared value.
        current["id"] = donor
        shares = await client.get(f"/v1/conversations/{conversation_id}/contact-shares")
        assert shares.status_code == 200, shares.text
        assert [s["value"] for s in shares.json()] == ["+63 900 000 0000"]

        # An audit record was written for the share.
        async with create_async_engine(pg_url).connect() as conn:
            result = await conn.execute(
                text("SELECT action FROM audit_events WHERE action = 'contact_share_phone'")
            )
            assert result.all() == [("contact_share_phone",)]

    run_scenario(pg_url, scenario)


def test_contact_share_audit_row_exists(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)

        current["id"] = requester
        opened = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/conversation"
        )
        conversation_id = opened.json()["conversation_id"]
        await client.post(
            f"/v1/conversations/{conversation_id}/contact-shares",
            json={"field": "email", "value": "donor@example.test"},
        )

        async with create_async_engine(pg_url).connect() as conn:
            result = await conn.execute(
                text("SELECT actor_id, action FROM audit_events WHERE action = 'contact_share_email'")
            )
            rows = result.all()
        assert len(rows) == 1
        assert rows[0][0] == requester

    run_scenario(pg_url, scenario)


def test_map_visibility_off_revokes_live_share(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor, map_visible=True, exact_sharing=True)
        created = await submit_request(client, current, requester)

        current["id"] = requester
        activated = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location-share"
        )
        assert activated.status_code == 200, activated.text
        assert (
            await client.get(f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location")
        ).json()["shared"] is True

        # The donor keeps the map pin but turns OFF exact-location sharing.
        current["id"] = donor
        updated = await client.put(
            f"/v1/donors/{donor}/map-visibility",
            json={"map_visible": True, "exact_location_sharing_enabled": False},
        )
        assert updated.status_code == 200, updated.text

        current["id"] = requester
        after = await client.get(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location"
        )
        assert after.json()["shared"] is False
        assert after.json()["latitude"] is None

    run_scenario(pg_url, scenario)


def test_legacy_donor_gps_presence_refreshes_stale_location(pg_url):
    async def scenario(client, current, run_sql):
        donor = new_user("donor")
        await setup_donor(client, current, donor, map_visible=True)
        await run_sql(
            f"UPDATE donors SET last_presence_at = now() - interval '10 days' WHERE id = '{donor}'"
        )
        current["id"] = donor
        refreshed = await client.post(
            f"/v1/donors/{donor}/presence",
            json={"latitude": 11.2540, "longitude": 125.0110, "precision_meters": 35},
        )
        assert refreshed.status_code == 200, refreshed.text

        current["id"] = new_user("viewer")
        body = (await client.get("/v1/donor-map")).json()
        assert len(body["entries"]) == 1
        assert body["entries"][0]["latitude"] == 11.25
        assert body["entries"][0]["longitude"] == 125.01

    run_scenario(pg_url, scenario)


def test_donors_without_fresh_gps_presence_are_hidden_from_the_map(pg_url):
    async def scenario(client, current, run_sql):
        stale_donor, never_present = new_user("donor"), new_user("donor")
        await setup_donor(client, current, stale_donor, map_visible=True)
        await setup_donor(client, current, never_present, map_visible=True)
        await run_sql(
            f"UPDATE donors SET last_presence_at = now() - interval '10 days' "
            f"WHERE id = '{stale_donor}'"
        )
        await run_sql(f"UPDATE donors SET last_presence_at = NULL WHERE id = '{never_present}'")
        current["id"] = new_user("viewer")
        body = (await client.get("/v1/donor-map")).json()
        assert body["entries"] == [], "stale and never-refreshed donors must both be hidden"

    run_scenario(pg_url, scenario)


def test_exact_location_requires_a_fresh_donor_location(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor, exact_sharing=True)
        created = await submit_request(client, current, requester)

        current["id"] = requester
        await client.post(f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location-share")
        assert (
            await client.get(f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location")
        ).json()["shared"] is True

        # The donor's snapshot goes stale: the pin must stop being disclosed.
        await run_sql(
            f"UPDATE donors SET last_presence_at = now() - interval '10 days' WHERE id = '{donor}'"
        )
        after = await client.get(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location"
        )
        assert after.json()["shared"] is False
        assert "fresh" in (after.json()["reason"] or "").lower()

        # A missing GPS timestamp must also deny disclosure; profile edits or
        # account timestamps must never be treated as a substitute GPS fix.
        await run_sql(f"UPDATE donors SET last_presence_at = NULL WHERE id = '{donor}'")
        never_present = await client.get(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location"
        )
        assert never_present.json()["shared"] is False
        assert "fresh" in (never_present.json()["reason"] or "").lower()
        assert never_present.json().get("latitude") is None
        assert never_present.json().get("longitude") is None

    run_scenario(pg_url, scenario)


def test_map_coarsening_uses_a_consistent_grid(pg_url):
    async def scenario(client, current, run_sql):
        a, b = new_user("donor"), new_user("donor")
        await setup_donor(client, current, a, latitude=11.2401, longitude=125.0001, map_visible=True)
        await setup_donor(client, current, b, latitude=11.2499, longitude=125.0099, map_visible=True)
        current["id"] = new_user("viewer")
        entries = (await client.get("/v1/donor-map")).json()["entries"]
        assert len(entries) == 2
        # Both donors fall in the same ~1 km cell, so both snap to the same point.
        assert {e["latitude"] for e in entries} == {11.24}
        assert {e["longitude"] for e in entries} == {125.0}

    run_scenario(pg_url, scenario)


def test_contact_share_rejects_invalid_values(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)
        current["id"] = requester
        opened = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/conversation"
        )
        conversation_id = opened.json()["conversation_id"]

        bad_email = await client.post(
            f"/v1/conversations/{conversation_id}/contact-shares",
            json={"field": "email", "value": "not-an-email"},
        )
        assert bad_email.status_code == 400
        bad_phone = await client.post(
            f"/v1/conversations/{conversation_id}/contact-shares",
            json={"field": "phone", "value": "call me"},
        )
        assert bad_phone.status_code == 400
        # Nothing invalid was stored.
        assert (await client.get(f"/v1/conversations/{conversation_id}/contact-shares")).json() == []

    run_scenario(pg_url, scenario)


def test_blank_message_is_rejected(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)
        current["id"] = requester
        opened = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/conversation"
        )
        conversation_id = opened.json()["conversation_id"]
        blank = await client.post(
            f"/v1/conversations/{conversation_id}/messages", json={"body": "   "}
        )
        assert blank.status_code == 400

    run_scenario(pg_url, scenario)


def test_location_share_cannot_be_activated_on_an_inactive_request(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor, exact_sharing=True)
        created = await submit_request(client, current, requester)
        current["id"] = requester
        cancelled = await client.post(f"/v1/emergency-requests/{created['request_id']}/cancel")
        assert cancelled.status_code == 200, cancelled.text
        blocked = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location-share"
        )
        assert blocked.status_code == 409

    run_scenario(pg_url, scenario)


def test_sweeper_expires_shares_for_timed_out_requests(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor, exact_sharing=True)
        created = await submit_request(client, current, requester)
        current["id"] = requester
        await client.post(f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location-share")

        # The request deadline passes without an explicit cancel/fulfil.
        await run_sql(
            f"UPDATE emergency_requests SET response_deadline = now() - interval '1 hour' "
            f"WHERE id = '{created['request_id']}'"
        )

        from app.privacy_repositories import SqlAlchemyPrivacyStore

        engine = create_async_engine(pg_url)
        sessions = async_sessionmaker(engine, expire_on_commit=False, autoflush=False)
        async with sessions() as session:
            closed = await SqlAlchemyPrivacyStore(session).expire_stale_shares()
        await engine.dispose()
        assert closed >= 1

        async with create_async_engine(pg_url).connect() as conn:
            result = await conn.execute(
                text("SELECT status FROM donor_location_shares WHERE request_id = :rid"),
                {"rid": created["request_id"]},
            )
            assert result.all() == [("expired",)]

    run_scenario(pg_url, scenario)


def test_migration_007_applies_cleanly(pg_url):
    from pathlib import Path

    async def main():
        engine = create_async_engine(pg_url)
        async with engine.begin() as conn:
            await conn.execute(text("DROP SCHEMA public CASCADE"))
            await conn.execute(text("CREATE SCHEMA public"))
            await conn.run_sync(db_models.Base.metadata.create_all)
        sql = (
            Path(__file__).resolve().parents[1] / "sql" / "007_donor_map_chat_contact_sharing.sql"
        ).read_text()
        async with engine.begin() as conn:
            for statement in [s.strip() for s in sql.split(";") if s.strip()]:
                await conn.execute(text(statement))
            columns = await conn.execute(
                text("SELECT column_name FROM information_schema.columns WHERE table_name = 'donors'")
            )
            names = {row[0] for row in columns.all()}
            assert {"map_visible", "map_visibility_updated_at", "exact_location_sharing_enabled"} <= names
            tables = await conn.execute(
                text("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
            )
            table_names = {row[0] for row in tables.all()}
            assert {"donor_location_shares", "conversations", "messages", "contact_shares"} <= table_names
        await engine.dispose()

    asyncio.run(main())


def test_block_is_enforced_and_suppresses_push(pg_url, monkeypatch):
    """A block is enforceable state: the blocked participant cannot message or
    share contact details, and no push is delivered to the blocker."""
    from app import main_postgres

    delivered = []

    async def capture(recipients, title, body, data):
        delivered.append((list(recipients), title))

    monkeypatch.setattr(main_postgres, "send_push_safely", capture)

    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)
        current["id"] = requester
        opened = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/conversation"
        )
        conversation_id = opened.json()["conversation_id"]

        # Ignore pushes from setup (e.g. the donor-match notification).
        delivered.clear()

        # The requester blocks the donor.
        blocked = await client.post(f"/v1/conversations/{conversation_id}/block", json={"reason": "unsafe"})
        assert blocked.status_code == 200, blocked.text
        assert blocked.json()["action"] == "blocked"

        # The block is persisted as state, not just an audit event.
        async with create_async_engine(pg_url).connect() as conn:
            result = await conn.execute(
                text("SELECT blocker_id, blocked_id FROM conversation_blocks WHERE conversation_id = :cid"),
                {"cid": conversation_id},
            )
            rows = result.all()
        assert len(rows) == 1
        assert rows[0][0] == requester
        assert rows[0][1] == donor

        # The blocked donor can no longer send a message or share contact details.
        current["id"] = donor
        message = await client.post(
            f"/v1/conversations/{conversation_id}/messages", json={"body": "hello?"}
        )
        assert message.status_code == 403, message.text
        share = await client.post(
            f"/v1/conversations/{conversation_id}/contact-shares",
            json={"field": "phone", "value": "+639170000000"},
        )
        assert share.status_code == 403, share.text

        # No push was delivered to the blocker for the rejected attempts.
        assert delivered == []

    run_scenario(pg_url, scenario)


def test_conversation_moderation_is_available_to_either_participant(pg_url):
    """Report and Block are conversation-scoped, so a matched donor can use them
    (the requester-only contact endpoints would return 403 for a donor)."""
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)
        current["id"] = requester
        opened = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/conversation"
        )
        conversation_id = opened.json()["conversation_id"]

        # The donor reports the requester.
        current["id"] = donor
        reported = await client.post(
            f"/v1/conversations/{conversation_id}/report", json={"reason": "spam"}
        )
        assert reported.status_code == 200, reported.text
        assert reported.json()["action"] == "reported"

        # Audit records the moderation action without duplicating user-written
        # report text, which may contain personal or sensitive information.
        audit_rows = await run_sql(
            "SELECT metadata::text FROM audit_events WHERE action = 'conversation_reported'"
        )
        assert audit_rows is not None and len(audit_rows) == 1
        assert "spam" not in audit_rows[0][0]
        assert '"reason_provided": true' in audit_rows[0][0]

        # The donor blocks the requester.
        blocked = await client.post(f"/v1/conversations/{conversation_id}/block", json={})
        assert blocked.status_code == 200, blocked.text

        # The requester is now the blocked party and cannot message the donor.
        current["id"] = requester
        message = await client.post(
            f"/v1/conversations/{conversation_id}/messages", json={"body": "hi"}
        )
        assert message.status_code == 403, message.text

        # A non-participant cannot moderate the conversation.
        current["id"] = new_user("stranger")
        denied = await client.post(f"/v1/conversations/{conversation_id}/block", json={})
        assert denied.status_code == 403

    run_scenario(pg_url, scenario)


def test_donor_message_notifies_the_requester(pg_url, monkeypatch):
    """When the donor sends a message, the push must go to the requester, even
    when the donor row id differs from the donor's user id."""
    from app import main_postgres

    delivered = []

    async def capture(recipients, title, body, data):
        delivered.append(list(recipients))

    monkeypatch.setattr(main_postgres, "send_push_safely", capture)

    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)
        current["id"] = requester
        opened = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/conversation"
        )
        conversation_id = opened.json()["conversation_id"]

        # The requester needs a profile row to hold a push token.
        current["id"] = requester
        profile = await client.put(
            "/v1/profile",
            json={"role": "requester", "display_name": "Test Requester", "can_request": True, "can_donate": False},
        )
        assert profile.status_code == 200, profile.text
        await run_sql(
            f"UPDATE lifelink_profiles SET fcm_token = 'requester-device' WHERE user_id = '{requester}'"
        )

        # The donor row id differs from the donor's user id.
        await run_sql(f"UPDATE donors SET user_id = '{donor}-user' WHERE id = '{donor}'")

        delivered.clear()
        current["id"] = donor
        sent = await client.post(
            f"/v1/conversations/{conversation_id}/messages", json={"body": "on my way"}
        )
        assert sent.status_code == 201, sent.text

        # The push went to the requester, not the donor's own token.
        assert delivered == [[(requester, "requester-device")]]

    run_scenario(pg_url, scenario)


def test_migration_008_applies_cleanly(pg_url):
    from pathlib import Path

    async def main():
        engine = create_async_engine(pg_url)
        async with engine.begin() as conn:
            await conn.execute(text("DROP SCHEMA public CASCADE"))
            await conn.execute(text("CREATE SCHEMA public"))
            await conn.run_sync(db_models.Base.metadata.create_all)
        sql = (
            Path(__file__).resolve().parents[1] / "sql" / "008_conversation_blocks.sql"
        ).read_text()
        async with engine.begin() as conn:
            for statement in [s.strip() for s in sql.split(";") if s.strip()]:
                await conn.execute(text(statement))
            tables = await conn.execute(
                text("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
            )
            assert "conversation_blocks" in {row[0] for row in tables.all()}
        await engine.dispose()

    asyncio.run(main())


def test_block_is_one_way_and_does_not_silence_the_blocker(pg_url):
    """A block silences only the blocked participant.

    Regression: ``is_blocked`` used to match on ``blocked_id`` alone, so a
    requester who blocked a donor also locked themselves out of messaging and
    contact sharing. The blocker must keep full access.
    """
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)
        current["id"] = requester
        opened = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/conversation"
        )
        conversation_id = opened.json()["conversation_id"]

        # The requester blocks the donor.
        blocked = await client.post(
            f"/v1/conversations/{conversation_id}/block", json={"reason": "unsafe"}
        )
        assert blocked.status_code == 200, blocked.text

        # The blocker is NOT silenced: they can still message and share contact.
        sent = await client.post(
            f"/v1/conversations/{conversation_id}/messages", json={"body": "Please stop."}
        )
        assert sent.status_code == 201, sent.text
        share = await client.post(
            f"/v1/conversations/{conversation_id}/contact-shares",
            json={"field": "phone", "value": "+639170000000"},
        )
        assert share.status_code == 201, share.text

        # The blocked donor is silenced.
        current["id"] = donor
        denied = await client.post(
            f"/v1/conversations/{conversation_id}/messages", json={"body": "hello?"}
        )
        assert denied.status_code == 403, denied.text

    run_scenario(pg_url, scenario)


def test_donor_with_distinct_user_id_can_use_the_conversation(pg_url):
    """A donor whose owning user id differs from the donor row id is a participant.

    Regression: ``conversation_for_participant`` gated on the donor *row* id, so
    such a donor was rejected with 403 before the identity-aware helpers ran.
    """
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)
        current["id"] = requester
        opened = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/conversation"
        )
        assert opened.status_code == 200, opened.text
        conversation_id = opened.json()["conversation_id"]

        # Give the donor row an owning user id that differs from its row id.
        donor_user = new_user("donor-user")
        await run_sql(f"UPDATE donors SET user_id = '{donor_user}' WHERE id = '{donor}'")

        # The donor, acting as the owning user, can read, message, report, block.
        current["id"] = donor_user
        assert (
            await client.get(f"/v1/conversations/{conversation_id}/messages")
        ).status_code == 200
        sent = await client.post(
            f"/v1/conversations/{conversation_id}/messages", json={"body": "On my way."}
        )
        assert sent.status_code == 201, sent.text
        reported = await client.post(
            f"/v1/conversations/{conversation_id}/report", json={"reason": "spam"}
        )
        assert reported.status_code == 200, reported.text
        blocked = await client.post(f"/v1/conversations/{conversation_id}/block", json={})
        assert blocked.status_code == 200, blocked.text

        # The requester is now the blocked party and cannot message the donor.
        current["id"] = requester
        denied = await client.post(
            f"/v1/conversations/{conversation_id}/messages", json={"body": "hi"}
        )
        assert denied.status_code == 403, denied.text

    run_scenario(pg_url, scenario)
