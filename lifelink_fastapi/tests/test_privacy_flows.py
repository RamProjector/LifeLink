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
from datetime import datetime, timedelta, timezone
from urllib.parse import parse_qs, urlsplit

import pytest

pgserver = pytest.importorskip("pgserver")

import httpx  # noqa: E402
from sqlalchemy import text  # noqa: E402
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine  # noqa: E402

from app import db_models  # noqa: E402
from app.db import get_db_session  # noqa: E402
from app.main_postgres import app  # noqa: E402
from app.security import Principal, get_postgres_principal  # noqa: E402

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
                        await conn.execute(text(statement))

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
        "response_deadline": (datetime.now(timezone.utc) + timedelta(hours=hours)).isoformat(),
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

        current["id"] = requester
        activated = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location-share"
        )
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
        after = await client.get(
            f"/v1/emergency-requests/{created['request_id']}/donors/{donor}/location"
        )
        assert after.json()["shared"] is False
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
