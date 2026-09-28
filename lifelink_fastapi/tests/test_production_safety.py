from fastapi import HTTPException
from datetime import datetime, timezone
import asyncio
from sqlalchemy.dialects import postgresql

from app.rate_limit import enforce_rate_limit
from app.main_postgres import RequesterContactOut
from app.main_postgres import enum_value
from app.donor_api import DonorResponseIn
from app.donor_repositories import apply_donor_response_to_contact
from app.donor_repositories import SqlAlchemyDonorStore
from app.repositories import CONTACT_EMAIL_VISIBLE_STATUSES


def test_rate_limit_rejects_after_threshold():
    key = "test-production-safety"
    for _ in range(2):
        enforce_rate_limit(key, 2, 300)
    try:
        enforce_rate_limit(key, 2, 300)
    except HTTPException as exc:
        assert exc.status_code == 429
    else:
        raise AssertionError("Expected the third request to be rate limited")


def test_contact_response_preserves_lifecycle_timestamps():
    accepted_at = datetime.now(timezone.utc)
    shared_at = datetime.now(timezone.utc)
    response = RequesterContactOut(
        donor_id="donor-1",
        display_name="Donor",
        status="contact_shared",
        accepted_at=accepted_at,
        contact_shared_at=shared_at,
        updated_at=shared_at,
        contact_email="donor@example.test",
    )

    assert response.contact_shared_at == shared_at
    assert response.updated_at == shared_at


def test_contact_email_is_hidden_until_contact_is_shared():
    assert "accepted" not in CONTACT_EMAIL_VISIBLE_STATUSES
    assert CONTACT_EMAIL_VISIBLE_STATUSES == {"contact_shared", "meeting_arranged", "fulfilled"}


def test_donor_acceptance_does_not_mark_contact_as_shared():
    accepted_at = datetime.now(timezone.utc)
    contact = type("Contact", (), {"contact_shared_at": None, "accepted_at": None})()

    apply_donor_response_to_contact(contact, DonorResponseIn(response="accepted"), accepted_at)

    assert contact.status == "accepted"
    assert contact.accepted_at == accepted_at
    assert contact.contact_shared_at is None


def test_donor_arrival_preserves_prior_acceptance_without_marking_contact_shared():
    accepted_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    arrived_at = datetime(2026, 1, 1, 0, 5, tzinfo=timezone.utc)
    contact = type("Contact", (), {"contact_shared_at": None, "accepted_at": accepted_at})()

    apply_donor_response_to_contact(contact, DonorResponseIn(response="arrived"), arrived_at)

    assert contact.status == "arrived"
    assert contact.accepted_at == accepted_at
    assert contact.contact_shared_at is None


def test_donor_inbox_query_excludes_terminal_and_expired_requests():
    class Result:
        def unique(self):
            return self

        def all(self):
            return []

    class Session:
        statement = None

        async def execute(self, statement):
            self.statement = statement
            return Result()

    session = Session()
    asyncio.run(SqlAlchemyDonorStore(session).inbox("donor-1"))
    compiled = session.statement.compile(dialect=postgresql.dialect())
    sql = str(compiled)
    assert "emergency_requests.status IN" in sql
    assert "emergency_requests.response_deadline >" in sql
    assert "emergency_requests.requester_id !=" in sql
    status_values = next(value for key, value in compiled.params.items() if key.startswith("status"))
    assert set(status_values) == {"matching", "awaiting_responses", "partially_fulfilled", "manual_broadcast"}


def test_postgres_enum_value_handles_plain_and_enum_values():
    assert enum_value("O+") == "O+"

    class BloodTypeLike:
        value = "O+"

    assert enum_value(BloodTypeLike()) == "O+"
