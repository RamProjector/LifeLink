"""End-to-end tests for the production (PostgreSQL) API against a real Postgres.

These reproduce what the mock-backed tests cannot: request persistence,
history restore after sign-in, request lifecycle actions, and donor matching
on real enum and timestamptz columns.

Run them with `pip install pgserver` (a pip-installable PostgreSQL). Without
it the whole module is skipped, so CI and Docker builds are unaffected.
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

from app import db_models, main_postgres
from app.db import get_db_session
from app.main_postgres import app
from app.security import Principal, get_postgres_principal

# The hosted database uses PostGIS geography columns; a plain Postgres has no
# such type, and no test here reads those columns.
db_models.GeographyPoint.get_col_spec = lambda self, **kw: "TEXT"  # type: ignore[method-assign]


@pytest.fixture(scope="module")
def pg_url() -> str:
    server = pgserver.get_server(tempfile.mkdtemp(prefix="lifelink-pg-"))
    host = parse_qs(urlsplit(server.get_uri()).query)["host"][0]
    yield f"postgresql+asyncpg://postgres@/postgres?host={host}"
    server.cleanup()


def run_scenario(pg_url: str, scenario):
    """Fresh schema, real API app, per-request DB sessions like production."""

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


async def setup_donor(client, current, donor_id: str, *, claim_verified: bool = False, blood_type: str = "O+") -> None:
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
            "latitude": 11.2440,
            "longitude": 125.0010,
            "service_radius_km": 15,
            "verified": claim_verified,
        },
    )
    assert saved.status_code == 200, saved.text
    available = await client.patch(f"/v1/donors/{donor_id}/availability", json={"availability": "available"})
    assert available.status_code == 200, available.text


async def submit_request(client, current, requester_id: str, **kwargs) -> dict:
    current["id"] = requester_id
    response = await client.post("/v1/emergency-requests", json=request_payload(requester_id, **kwargs))
    assert response.status_code == 201, response.text
    return response.json()


def test_self_registered_donor_receives_the_matched_request(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)

        created = await submit_request(client, current, requester)
        # Submitting must not broadcast: matches are stored for review, but no
        # donor notification is created until the requester contacts them.
        assert created["notifications_created"] == 0
        assert [m["donor_id"] for m in created["matches"]] == [donor]

        current["id"] = donor
        inbox = await client.get(f"/v1/donors/{donor}/requests")
        assert inbox.status_code == 200, inbox.text
        assert [item["request_id"] for item in inbox.json()] == [created["request_id"]]

    run_scenario(pg_url, scenario)


def test_submit_does_not_broadcast_but_contact_does(pg_url, monkeypatch):
    pushes: list[dict] = []

    async def capture(recipients, title, body, data):
        pushes.append({"recipients": list(recipients), "data": dict(data)})

    monkeypatch.setattr(main_postgres, "send_push_safely", capture)

    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)

        pushes.clear()
        created = await submit_request(client, current, requester)
        assert created["notifications_created"] == 0
        assert pushes == [], "submitting a request must not broadcast to donors"

        current["id"] = requester
        contacted = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/contact",
            json={"donor_ids": [donor]},
        )
        assert contacted.status_code == 200, contacted.text
        assert pushes, "contacting a selected donor is the intended broadcast trigger"
        assert pushes[-1]["data"]["type"] == "contact_request"

    run_scenario(pg_url, scenario)


def test_cancel_stops_the_donor_search_broadcast(pg_url):
    """Cancelling a request must stop its donor search, not just flip its status.

    The broadcast is the donor-search result set: the matches created at submit
    time. Cancel must withdraw them so the donor inbox stops showing the request
    and the requester no longer sees a live match for a cancelled request.
    """

    async def scenario(client, current, run_sql):
        """Submit, verify the broadcast is live, cancel, then verify it is stopped."""
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)

        created = await submit_request(client, current, requester)
        assert [m["donor_id"] for m in created["matches"]] == [donor]

        # The donor search is live: the donor sees the request in their inbox.
        current["id"] = donor
        inbox = await client.get(f"/v1/donors/{donor}/requests")
        assert inbox.status_code == 200, inbox.text
        assert [item["request_id"] for item in inbox.json()] == [created["request_id"]]

        # Cancel the request.
        current["id"] = requester
        cancelled = await client.post(f"/v1/emergency-requests/{created['request_id']}/cancel")
        assert cancelled.status_code == 200, cancelled.text
        assert cancelled.json()["status"] == "cancelled"

        # The donor search is stopped: the request is gone from the donor inbox.
        current["id"] = donor
        inbox_after = await client.get(f"/v1/donors/{donor}/requests")
        assert inbox_after.status_code == 200, inbox_after.text
        assert inbox_after.json() == [], "cancel must stop the donor search broadcast"

        # The match the broadcast created is withdrawn, so the requester no longer
        # sees it as a live match for the cancelled request.
        current["id"] = requester
        status = await client.get(f"/v1/emergency-requests/{created['request_id']}")
        assert status.status_code == 200, status.text
        assert status.json()["matches"] == [], "cancel must withdraw the donor-search matches"

    run_scenario(pg_url, scenario)


def test_sweeper_expires_requests_past_their_deadline(pg_url):
    """An open request must expire on its own once its deadline passes.

    The lazy read paths only expire a request that something reads. The
    background sweeper must close a timed-out request even when nothing reads
    it, so its status reflects the deadline without any client action.
    """

    async def scenario(client, current, run_sql):
        """Age a request, run the store sweep, and verify persisted expiry via SQL."""
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)

        # The deadline passes with no further cancel/fulfil and no read.
        await run_sql(
            f"UPDATE emergency_requests SET response_deadline = now() - interval '1 hour' "
            f"WHERE id = '{created['request_id']}'"
        )

        # A second request that is still inside its deadline must be left alone.
        still_open = await submit_request(client, current, requester)

        from app.repositories import SqlAlchemyRequestStore

        engine = create_async_engine(pg_url)
        sessions = async_sessionmaker(engine, expire_on_commit=False, autoflush=False)
        async with sessions() as session:
            expired = await SqlAlchemyRequestStore(session).expire_timed_out_requests_async()
        await engine.dispose()
        assert expired >= 1

        async with create_async_engine(pg_url).connect() as conn:
            result = await conn.execute(
                text(
                    "SELECT id, status::text, updated_at > created_at "
                    "FROM emergency_requests WHERE id IN (:rid, :open)"
                ),
                {"rid": created["request_id"], "open": still_open["request_id"]},
            )
            rows = {row[0]: (row[1], row[2]) for row in result.all()}
        assert rows[created["request_id"]][0] == "expired"
        # The sweep stamps updated_at so the transition is observable.
        assert rows[created["request_id"]][1] is True
        # A request still inside its deadline is untouched.
        assert rows[still_open["request_id"]][0] in {
            "matching",
            "awaiting_responses",
            "partially_fulfilled",
            "manual_broadcast",
        }

    run_scenario(pg_url, scenario)


def test_sweeper_limit_bounds_a_single_sweep(pg_url):
    """A single sweep must not transition more than ``limit`` requests.

    A large backlog has to be drained across ticks instead of loading every row
    into one transaction, so the bound is part of the sweeper's contract.
    """

    async def scenario(client, current, run_sql):
        requester = new_user("requester")
        created = [await submit_request(client, current, requester) for _ in range(3)]
        ids = ", ".join(f"'{item['request_id']}'" for item in created)
        await run_sql(
            f"UPDATE emergency_requests SET response_deadline = now() - interval '1 hour' "
            f"WHERE id IN ({ids})"
        )

        from app.repositories import SqlAlchemyRequestStore

        engine = create_async_engine(pg_url)
        sessions = async_sessionmaker(engine, expire_on_commit=False, autoflush=False)
        async with sessions() as session:
            first = await SqlAlchemyRequestStore(session).expire_timed_out_requests_async(limit=2)
        async with sessions() as session:
            second = await SqlAlchemyRequestStore(session).expire_timed_out_requests_async(limit=2)
        await engine.dispose()

        assert first == 2, "the sweep must stop at the requested limit"
        assert second == 1, "the next tick must pick up the remainder"

    run_scenario(pg_url, scenario)


def test_sweeper_filters_statuses_orders_oldest_first_and_is_idempotent(pg_url, monkeypatch):
    """Exercise SQL eligibility and batch ordering, which a mocked session cannot prove."""
    from app import repositories
    from app.repositories import SqlAlchemyRequestStore

    now = datetime.now(UTC)
    previous_update = now - timedelta(days=2)

    class FrozenDateTime(datetime):
        @classmethod
        def now(cls, tz=None):
            return now.astimezone(tz) if tz else now.replace(tzinfo=None)

    monkeypatch.setattr(repositories, "datetime", FrozenDateTime)

    async def scenario(client, current, run_sql):
        # Deliberately insert out of deadline order, with terminal and future
        # rows mixed in. The boundary row is due at exactly the sweep's clock.
        cases = [
            ("matching", now - timedelta(minutes=1), True),
            ("fulfilled", now - timedelta(days=1), False),
            ("awaiting_responses", now - timedelta(minutes=3), True),
            ("cancelled", now - timedelta(days=1), False),
            ("partially_fulfilled", now - timedelta(minutes=2), True),
            ("expired", now - timedelta(days=1), False),
            ("manual_broadcast", now, True),
            ("draft", now - timedelta(days=1), False),
            ("awaiting_responses", now + timedelta(microseconds=1), False),
        ]
        # The sweep is global; distinct owners also avoid the per-user create limit.
        ids = [(await submit_request(client, current, new_user("requester")))["request_id"] for _ in cases]
        engine = create_async_engine(pg_url)
        sessions = async_sessionmaker(engine, expire_on_commit=False, autoflush=False)
        try:
            async with engine.begin() as conn:
                for request_id, (status, deadline, _) in zip(ids, cases, strict=True):
                    await conn.execute(
                        text("UPDATE emergency_requests SET status = :status, response_deadline = :deadline, "
                             "updated_at = :updated WHERE id = :id"),
                        {"id": request_id, "status": status, "deadline": deadline, "updated": previous_update},
                    )

            async with sessions() as session:
                assert await SqlAlchemyRequestStore(session).expire_timed_out_requests_async(limit=2) == 2

            async with engine.connect() as conn:
                rows = (await conn.execute(text("SELECT id FROM emergency_requests WHERE updated_at = :now"), {"now": now})).all()
                assert {row[0] for row in rows} == {ids[2], ids[4]}

            async with sessions() as session:
                assert await SqlAlchemyRequestStore(session).expire_timed_out_requests_async(limit=2) == 2
            async with sessions() as session:
                assert await SqlAlchemyRequestStore(session).expire_timed_out_requests_async(limit=2) == 0

            async with engine.connect() as conn:
                rows = (await conn.execute(text("SELECT id, status::text, updated_at FROM emergency_requests"))).all()
            persisted = {row[0]: (row[1], row[2]) for row in rows}
            for request_id, (status, _, eligible) in zip(ids, cases, strict=True):
                expected = ("expired", now) if eligible else (status, previous_update)
                assert persisted[request_id] == expected
        finally:
            await engine.dispose()

    run_scenario(pg_url, scenario)


def test_cancel_resets_request_and_allows_a_new_one(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)

        current["id"] = requester
        selected = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/contact",
            json={"donor_ids": [donor]},
        )
        assert selected.status_code == 200, selected.text

        cancelled = await client.post(f"/v1/emergency-requests/{created['request_id']}/cancel")
        assert cancelled.status_code == 200, cancelled.text
        assert cancelled.json()["status"] == "cancelled"

        # The cancelled request's open contacts are closed, not left pending.
        contacts = await client.get(f"/v1/emergency-requests/{created['request_id']}/contacts")
        assert contacts.status_code == 200, contacts.text
        assert contacts.json()[0]["status"] == "cancelled"

        # The donor no longer sees the cancelled request in their inbox.
        current["id"] = donor
        inbox = await client.get(f"/v1/donors/{donor}/requests")
        assert inbox.status_code == 200, inbox.text
        assert inbox.json() == []

        # A brand-new request can still be created afterwards.
        current["id"] = requester
        second = await submit_request(client, current, requester)
        assert second["request_id"] != created["request_id"]
        history = await client.get("/v1/emergency-requests")
        assert history.status_code == 200, history.text
        statuses = {item["request_id"]: item["status"] for item in history.json()}
        assert statuses[created["request_id"]] == "cancelled"
        assert statuses[second["request_id"]] == "awaiting_responses"

    run_scenario(pg_url, scenario)


def test_blood_type_change_immediately_rematches_active_requests(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        # The donor starts as A+, which is not compatible with an O+ request.
        await setup_donor(client, current, donor, blood_type="A+")

        created = await submit_request(client, current, requester)
        assert created["matches"] == []

        current["id"] = donor
        inbox = await client.get(f"/v1/donors/{donor}/requests")
        assert inbox.status_code == 200, inbox.text
        assert inbox.json() == []

        # Changing the blood type to O+ must surface the active O+ request at once.
        saved = await client.put(
            f"/v1/donors/{donor}",
            json={
                "donor_id": donor,
                "display_name": "Test Donor",
                "blood_type": "O+",
                "latitude": 11.2440,
                "longitude": 125.0010,
                "service_radius_km": 15,
                "verified": False,
            },
        )
        assert saved.status_code == 200, saved.text

        inbox = await client.get(f"/v1/donors/{donor}/requests")
        assert inbox.status_code == 200, inbox.text
        assert [item["request_id"] for item in inbox.json()] == [created["request_id"]]

        # Changing away again removes the now-incompatible request.
        saved = await client.put(
            f"/v1/donors/{donor}",
            json={
                "donor_id": donor,
                "display_name": "Test Donor",
                "blood_type": "A+",
                "latitude": 11.2440,
                "longitude": 125.0010,
                "service_radius_km": 15,
                "verified": False,
            },
        )
        assert saved.status_code == 200, saved.text
        inbox = await client.get(f"/v1/donors/{donor}/requests")
        assert inbox.status_code == 200, inbox.text
        assert inbox.json() == []

    run_scenario(pg_url, scenario)


def test_donor_inbox_excludes_expired_and_closed_requests(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)

        active = await submit_request(client, current, requester)
        expired = await submit_request(client, current, requester)
        cancelled = await submit_request(client, current, requester)
        fulfilled = await submit_request(client, current, requester)
        await run_sql(
            "UPDATE emergency_requests SET response_deadline = now() - interval '5 minutes' "
            f"WHERE id = '{expired['request_id']}'"
        )
        await run_sql(
            f"UPDATE emergency_requests SET status = 'cancelled' WHERE id = '{cancelled['request_id']}'"
        )
        await run_sql(
            f"UPDATE emergency_requests SET status = 'fulfilled' WHERE id = '{fulfilled['request_id']}'"
        )

        current["id"] = donor
        inbox = await client.get(f"/v1/donors/{donor}/requests")
        assert inbox.status_code == 200, inbox.text
        assert [item["request_id"] for item in inbox.json()] == [active["request_id"]]

    run_scenario(pg_url, scenario)


def test_request_history_survives_the_deadline_passing(pg_url):
    async def scenario(client, current, run_sql):
        requester = new_user("requester")
        created = await submit_request(client, current, requester)

        # "Sign out, sign back in": history is the only thing the app has to restore from.
        history = await client.get("/v1/emergency-requests")
        assert history.status_code == 200, history.text
        assert [item["request_id"] for item in history.json()] == [created["request_id"]]

        # Time passes: the deadline is now behind us.
        await run_sql("UPDATE emergency_requests SET response_deadline = now() - interval '5 minutes'")

        history = await client.get("/v1/emergency-requests")
        assert history.status_code == 200, history.text
        items = history.json()
        assert [item["request_id"] for item in items] == [created["request_id"]]
        assert items[0]["status"] == "expired"

        status = await client.get(f"/v1/emergency-requests/{created['request_id']}")
        assert status.status_code == 200, status.text
        assert status.json()["status"] == "expired"

        # An old expired request must not stop a new one from being saved or listed.
        second = await submit_request(client, current, requester)
        history = await client.get("/v1/emergency-requests")
        assert history.status_code == 200, history.text
        assert {item["request_id"] for item in history.json()} == {created["request_id"], second["request_id"]}

    run_scenario(pg_url, scenario)


def test_cancel_fulfill_and_manual_broadcast_succeed(pg_url):
    async def scenario(client, current, run_sql):
        requester = new_user("requester")

        first = await submit_request(client, current, requester)
        cancelled = await client.post(f"/v1/emergency-requests/{first['request_id']}/cancel")
        assert cancelled.status_code == 200, cancelled.text
        assert cancelled.json()["status"] == "cancelled"

        second = await submit_request(client, current, requester)
        fulfilled = await client.post(f"/v1/emergency-requests/{second['request_id']}/fulfill")
        assert fulfilled.status_code == 200, fulfilled.text
        assert fulfilled.json()["status"] == "fulfilled"

        third = await submit_request(client, current, requester)
        broadcast = await client.post(f"/v1/emergency-requests/{third['request_id']}/manual-broadcast")
        assert broadcast.status_code == 200, broadcast.text

        history = await client.get("/v1/emergency-requests")
        assert history.status_code == 200, history.text
        statuses = {item["request_id"]: item["status"] for item in history.json()}
        assert statuses[first["request_id"]] == "cancelled"
        assert statuses[second["request_id"]] == "fulfilled"
        assert statuses[third["request_id"]] == "manual_broadcast"

    run_scenario(pg_url, scenario)


def test_donor_response_is_saved_and_survives_a_new_session(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)
        restored = await client.get(f"/v1/emergency-requests/{created['request_id']}")
        assert restored.status_code == 200, restored.text
        assert [match["donor_id"] for match in restored.json()["matches"]] == [donor]
        assert "latitude" not in restored.json()["matches"][0]
        assert "longitude" not in restored.json()["matches"][0]

        current["id"] = donor
        accepted = await client.post(
            f"/v1/donors/{donor}/requests/{created['request_id']}/response", json={"response": "accepted"}
        )
        assert accepted.status_code == 200, accepted.text

        inbox = await client.get(f"/v1/donors/{donor}/requests")
        assert inbox.status_code == 200, inbox.text
        assert inbox.json()[0]["status"] == "confirmed"

    run_scenario(pg_url, scenario)


def test_donor_arrival_does_not_block_requester_contact_completion(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)

        current["id"] = requester
        selected = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/contact",
            json={"donor_ids": [donor]},
        )
        assert selected.status_code == 200, selected.text

        current["id"] = donor
        accepted = await client.post(
            f"/v1/donors/{donor}/requests/{created['request_id']}/response", json={"response": "accepted"}
        )
        assert accepted.status_code == 200, accepted.text
        arrived = await client.post(
            f"/v1/donors/{donor}/requests/{created['request_id']}/response", json={"response": "arrived"}
        )
        assert arrived.status_code == 200, arrived.text

        current["id"] = requester
        contacts = await client.get(f"/v1/emergency-requests/{created['request_id']}/contacts")
        assert contacts.status_code == 200, contacts.text
        assert contacts.json()[0]["status"] == "arrived"
        assert contacts.json()[0]["accepted_at"] is not None
        shared = await client.patch(
            f"/v1/emergency-requests/{created['request_id']}/contacts/{donor}",
            json={"status": "contact_shared"},
        )
        assert shared.status_code == 200, shared.text
        assert shared.json()["status"] == "contact_shared"

        repeated = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/contact",
            json={"donor_ids": [donor]},
        )
        assert repeated.status_code == 200, repeated.text
        assert repeated.json()["donor_ids"] == []
        contacts_after_repeat = await client.get(f"/v1/emergency-requests/{created['request_id']}/contacts")
        assert contacts_after_repeat.status_code == 200, contacts_after_repeat.text
        assert contacts_after_repeat.json()[0]["status"] == "contact_shared"
        assert contacts_after_repeat.json()[0]["contact_shared_at"] is not None

        current["id"] = donor
        inbox = await client.get(f"/v1/donors/{donor}/requests")
        assert inbox.status_code == 200, inbox.text
        assert inbox.json()[0]["status"] == "contact_shared"

        current["id"] = requester
        history = await client.get("/v1/emergency-requests")
        assert history.status_code == 200, history.text
        assert history.json()[0]["matches_responded"] == 1
        status = await client.get(f"/v1/emergency-requests/{created['request_id']}")
        assert status.status_code == 200, status.text
        assert status.json()["matches_responded"] == 1

    run_scenario(pg_url, scenario)


def test_donor_response_before_requester_selection_is_preserved(pg_url):
    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)

        current["id"] = donor
        accepted = await client.post(
            f"/v1/donors/{donor}/requests/{created['request_id']}/response", json={"response": "accepted"}
        )
        assert accepted.status_code == 200, accepted.text

        current["id"] = requester
        selected = await client.post(
            f"/v1/emergency-requests/{created['request_id']}/contact",
            json={"donor_ids": [donor]},
        )
        assert selected.status_code == 200, selected.text
        assert selected.json()["donor_ids"] == [donor]
        contacts = await client.get(f"/v1/emergency-requests/{created['request_id']}/contacts")
        assert contacts.status_code == 200, contacts.text
        assert contacts.json()[0]["status"] == "accepted"
        assert contacts.json()[0]["accepted_at"] is not None

    run_scenario(pg_url, scenario)


def test_strict_mode_only_matches_verified_donors(pg_url, monkeypatch):
    monkeypatch.setenv("LIFELINK_REQUIRE_VERIFIED_DONORS", "true")

    async def scenario(client, current, run_sql):
        donor, requester = new_user("donor"), new_user("requester")
        await setup_donor(client, current, donor)
        created = await submit_request(client, current, requester)
        assert created["notifications_created"] == 0
        assert created["matches"] == []

        await run_sql(f"UPDATE donors SET verified = TRUE WHERE id = '{donor}'")
        created = await submit_request(client, current, requester)
        assert created["notifications_created"] == 0
        assert [m["donor_id"] for m in created["matches"]] == [donor]

    run_scenario(pg_url, scenario)


def test_a_client_cannot_mark_itself_verified(pg_url):
    async def scenario(client, current, run_sql):
        donor = new_user("donor")
        await setup_donor(client, current, donor, claim_verified=True)
        current["id"] = donor
        profile = await client.get(f"/v1/donors/{donor}")
        assert profile.status_code == 200, profile.text
        assert profile.json()["verified"] is False

    run_scenario(pg_url, scenario)
