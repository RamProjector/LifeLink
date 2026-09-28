"""Regression tests for reading saved requests back from storage.

A saved request must stay readable after its response deadline passes. The
create-time rule "response_deadline must be in the future" applies to new
input only; applying it to stored rows made every read of an expired request
fail, which took the whole request history down with it.
"""
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest
from pydantic import ValidationError

from app.db_models import (
    BloodTypeEnum,
    ContactMethodEnum,
    EmergencyRequest as RequestRow,
    RequestStatusEnum,
    UrgencyEnum,
)
from app.main import Donor, EmergencyRequestIn, score_donor
from app.repositories import SqlAlchemyRequestStore


def make_row(deadline: datetime, status=RequestStatusEnum.AWAITING_RESPONSES) -> RequestRow:
    row = RequestRow(
        id="req_test",
        requester_id="user-1",
        facility_id=None,
        requester_latitude=Decimal("11.2433"),
        requester_longitude=Decimal("125.0"),
        location_precision_meters=100,
        blood_type=BloodTypeEnum.O_POS,
        units=2,
        urgency=UrgencyEnum.URGENT,
        response_deadline=deadline,
        contact_method=ContactMethodEnum.IN_APP,
        note="",
        genuine_request_confirmed=True,
        sharing_consent_confirmed=True,
        idempotency_key="k" * 32,
        status=status,
        matching_version="v1",
        created_at=datetime.now(timezone.utc),
    )
    row.facility = None
    row.matches = []
    return row


def test_stored_request_is_readable_after_its_deadline_passes():
    row = make_row(datetime.now(timezone.utc) - timedelta(minutes=5))
    record = SqlAlchemyRequestStore._to_record(row)
    assert record.request_id == "req_test"
    assert record.payload.blood_type.value == "O+"


def test_stored_request_is_readable_when_status_was_assigned_as_plain_text():
    # Rows changed in-session keep the plain string that was assigned to them
    # (the API session uses expire_on_commit=False), not an enum member.
    row = make_row(datetime.now(timezone.utc) + timedelta(hours=1))
    row.status = "expired"
    row.blood_type = "O+"
    row.urgency = "urgent"
    row.contact_method = "in_app"
    record = SqlAlchemyRequestStore._to_record(row)
    assert record.status.value == "expired"


def test_new_requests_must_still_have_a_future_deadline():
    payload = {
        "requester_id": "user-1",
        "blood_type": "O+",
        "units": 1,
        "urgency": "planned",
        "response_deadline": (datetime.now(timezone.utc) - timedelta(minutes=1)).isoformat(),
        "location": {"latitude": 11.2, "longitude": 125.0},
        "contact_method": "in_app",
        "genuine_request_confirmed": True,
        "sharing_consent_confirmed": True,
        "idempotency_key": "k" * 32,
    }
    with pytest.raises(ValidationError):
        EmergencyRequestIn(**payload)


def _request() -> EmergencyRequestIn:
    return EmergencyRequestIn(
        requester_id="user-1",
        blood_type="O+",
        units=1,
        urgency="urgent",
        response_deadline=datetime.now(timezone.utc) + timedelta(hours=1),
        location={"latitude": 11.2433, "longitude": 125.0},
        contact_method="in_app",
        genuine_request_confirmed=True,
        sharing_consent_confirmed=True,
        idempotency_key="k" * 32,
    )


def _donor(verified: bool) -> Donor:
    return Donor(
        donor_id="donor-1",
        display_name="Test Donor",
        blood_type="O+",
        latitude=11.2440,
        longitude=125.0010,
        available=True,
        availability_updated_at=datetime.now(timezone.utc),
        verified=verified,
        service_radius_km=15,
        estimated_response_probability=0.5,
    )


def test_self_registered_donor_is_matchable_by_default(monkeypatch):
    monkeypatch.delenv("LIFELINK_REQUIRE_VERIFIED_DONORS", raising=False)
    match = score_donor(_request(), _donor(verified=False), datetime.now(timezone.utc))
    assert match is not None
    assert "not yet verified" in " ".join(match.explanation.factors)
    assert match.explanation.score_breakdown["verification"] == 0


def test_strict_mode_excludes_unverified_donors(monkeypatch):
    monkeypatch.setenv("LIFELINK_REQUIRE_VERIFIED_DONORS", "true")
    assert score_donor(_request(), _donor(verified=False), datetime.now(timezone.utc)) is None
    assert score_donor(_request(), _donor(verified=True), datetime.now(timezone.utc)) is not None
