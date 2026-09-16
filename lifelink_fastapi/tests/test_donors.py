from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient

from app.main import app, request_store

client = TestClient(app)


def request_payload(key: str = "donor-request-key-0001"):
    return {
        "requester_id": "coordinator-1",
        "blood_type": "O-",
        "units": 1,
        "urgency": "critical",
        "response_deadline": (datetime.now(timezone.utc) + timedelta(minutes=90)).isoformat(),
        "location": {
            "facility_id": "facility-1",
            "facility_name": "St. Luke’s Medical Center",
            "area": "Quezon City",
            "latitude": 14.6466,
            "longitude": 121.0437,
            "verified": True,
        },
        "contact_method": "in_app",
        "note": "Verified blood bank request.",
        "genuine_request_confirmed": True,
        "sharing_consent_confirmed": True,
        "idempotency_key": key,
    }


def test_donor_can_register_and_change_availability(monkeypatch):
    monkeypatch.setenv("LIFELINK_AUTH_REQUIRED", "true")
    unauthorized = client.put(
        "/v1/donors/donor-new",
        json={
            "donor_id": "donor-new", "display_name": "Donor Nova", "blood_type": "O-",
            "latitude": 14.6466, "longitude": 121.0437, "service_radius_km": 15, "verified": True,
        },
    )
    assert unauthorized.status_code == 401
    response = client.put(
        "/v1/donors/donor-new",
        json={
            "donor_id": "donor-new",
            "display_name": "Donor Nova",
            "blood_type": "O-",
            "latitude": 14.6466,
            "longitude": 121.0437,
            "service_radius_km": 15,
            "verified": True,
        },
        headers={"Authorization": "Bearer donor-new"},
    )
    assert response.status_code == 200
    assert response.json()["availability"] == "offline"

    response = client.patch(
        "/v1/donors/donor-new/availability",
        json={"availability": "available"},
        headers={"Authorization": "Bearer donor-new"},
    )
    assert response.status_code == 200
    assert response.json()["availability"] == "available"


def test_authenticated_donor_cannot_mutate_another_profile(monkeypatch):
    monkeypatch.setenv("LIFELINK_AUTH_REQUIRED", "true")
    response = client.patch(
        "/v1/donors/donor-dana/availability",
        json={"availability": "offline"},
        headers={"Authorization": "Bearer donor-other"},
    )
    assert response.status_code == 403


def test_eligible_donor_sees_request_and_can_accept():
    created = client.post("/v1/emergency-requests", json=request_payload()).json()
    request_id = created["request_id"]
    donor_id = created["matches"][0]["donor_id"]

    inbox = client.get(f"/v1/donors/{donor_id}/requests")
    assert inbox.status_code == 200
    assert any(item["request_id"] == request_id for item in inbox.json())

    response = client.post(
        f"/v1/donors/{donor_id}/requests/{request_id}/response",
        json={"response": "accepted"},
    )
    assert response.status_code == 200
    assert response.json()["response"] == "accepted"

    status = client.get(f"/v1/emergency-requests/{request_id}")
    assert status.status_code == 200
    assert status.json()["matches_responded"] == 1

    cancelled = client.post(f"/v1/emergency-requests/{request_id}/cancel")
    assert cancelled.status_code == 200
    assert cancelled.json()["status"] == "cancelled"


def teardown_function():
    request_store.records.clear()
    request_store.by_idempotency.clear()
