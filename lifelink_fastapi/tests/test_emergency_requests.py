from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient

from app.main import app, request_store


client = TestClient(app)


def make_payload(**overrides):
    payload = {
        "requester_id": "coordinator-1",
        "blood_type": "O-",
        "units": 2,
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
        "note": "Two units needed for the verified blood bank request.",
        "genuine_request_confirmed": True,
        "sharing_consent_confirmed": True,
        "idempotency_key": "idempotency-key-0001",
    }
    payload.update(overrides)
    return payload


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_request_returns_eligible_ranked_matches():
    response = client.post(
        "/v1/emergency-requests",
        json=make_payload(),
        headers={"Idempotency-Key": "idempotency-key-0001"},
    )
    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "awaiting_responses"
    assert body["matching_version"] == "v1-explainable-weighted"
    assert body["notifications_created"] >= 1
    assert all(match["explanation"]["eligible"] for match in body["matches"])
    assert all("Blood type" in match["explanation"]["factors"][0] for match in body["matches"])
    assert body["matches"] == sorted(
        body["matches"],
        key=lambda match: (-match["score"], match["estimated_travel_minutes"], match["distance_km"]),
    )


def test_idempotency_returns_same_request():
    payload = make_payload(idempotency_key="idempotency-key-0002")
    first = client.post("/v1/emergency-requests", json=payload)
    second = client.post("/v1/emergency-requests", json=payload)

    assert first.status_code == 201
    assert second.status_code == 201
    assert first.json()["request_id"] == second.json()["request_id"]


def test_mismatched_idempotency_header_is_rejected():
    response = client.post(
        "/v1/emergency-requests",
        json=make_payload(idempotency_key="idempotency-key-0003"),
        headers={"Idempotency-Key": "different-key-0000"},
    )
    assert response.status_code == 400
    assert "must match" in response.json()["detail"]


def test_unknown_blood_type_uses_manual_fallback():
    response = client.post(
        "/v1/emergency-requests",
        json=make_payload(blood_type="UNKNOWN", idempotency_key="idempotency-key-0004"),
    )
    assert response.status_code == 201
    assert response.json()["status"] == "manual_broadcast"
    assert "blood type" in response.json()["reason"].lower()


def test_unverified_facility_is_rejected():
    payload = make_payload(idempotency_key="idempotency-key-0005")
    payload["location"]["verified"] = False
    response = client.post("/v1/emergency-requests", json=payload)
    assert response.status_code == 422
    assert "verified facility" in response.json()["detail"]


def test_missing_consent_is_rejected():
    response = client.post(
        "/v1/emergency-requests",
        json=make_payload(
            sharing_consent_confirmed=False,
            idempotency_key="idempotency-key-0006",
        ),
    )
    assert response.status_code == 422
    assert "consent" in response.json()["detail"].lower()


def test_manual_broadcast_updates_request_status():
    payload = make_payload(idempotency_key="idempotency-key-0007")
    created = client.post("/v1/emergency-requests", json=payload).json()
    response = client.post(f"/v1/emergency-requests/{created['request_id']}/manual-broadcast")
    assert response.status_code == 200
    assert response.json()["status"] == "manual_broadcast"


def test_contact_endpoint_contacts_only_selected_eligible_donors():
    payload = make_payload(idempotency_key="idempotency-key-0008")
    created = client.post("/v1/emergency-requests", json=payload).json()
    eligible_id = created["matches"][0]["donor_id"]
    response = client.post(
        f"/v1/emergency-requests/{created['request_id']}/contact",
        json={"donor_ids": [eligible_id]},
    )
    assert response.status_code == 200
    assert response.json()["status"] == "contact_requested"
    assert response.json()["donor_ids"] == [eligible_id]


def test_contact_endpoint_rejects_donor_not_in_matches():
    payload = make_payload(idempotency_key="idempotency-key-0009")
    created = client.post("/v1/emergency-requests", json=payload).json()
    eligible_ids = {match["donor_id"] for match in created["matches"]}
    response = client.post(
        f"/v1/emergency-requests/{created['request_id']}/contact",
        json={"donor_ids": ["donor-not-matched"]},
    )
    assert response.status_code == 400
    assert "not eligible" in response.json()["detail"]


def test_authenticated_contact_requires_request_ownership(monkeypatch):
    monkeypatch.setenv("LIFELINK_AUTH_REQUIRED", "true")
    payload = make_payload(idempotency_key="idempotency-key-0010")
    created = client.post(
        "/v1/emergency-requests",
        json=payload,
        headers={"Authorization": "Bearer coordinator-1"},
    ).json()
    eligible_id = created["matches"][0]["donor_id"]
    response = client.post(
        f"/v1/emergency-requests/{created['request_id']}/contact",
        json={"donor_ids": [eligible_id]},
        headers={"Authorization": "Bearer coordinator-other"},
    )
    assert response.status_code == 403


def teardown_function():
    request_store.records.clear()
    request_store.by_idempotency.clear()
