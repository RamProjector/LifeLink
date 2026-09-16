from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from math import atan2, cos, radians, sin, sqrt
from typing import Annotated, Protocol
from uuid import uuid4

from fastapi import Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field, field_validator
from .security import Principal, get_principal, require_owner


app = FastAPI(
    title="LifeLink Matching Service",
    version="1.0.0",
    description=(
        "Emergency blood-request intake and explainable donor prioritization. "
        "Blood eligibility is rule-based; ranking is operational prioritization only."
    ),
)


class BloodType(str, Enum):
    A_POS = "A+"
    A_NEG = "A-"
    B_POS = "B+"
    B_NEG = "B-"
    AB_POS = "AB+"
    AB_NEG = "AB-"
    O_POS = "O+"
    O_NEG = "O-"
    UNKNOWN = "UNKNOWN"


class Urgency(str, Enum):
    CRITICAL = "critical"
    URGENT = "urgent"
    PLANNED = "planned"


class ContactMethod(str, Enum):
    IN_APP = "in_app"
    PHONE = "phone"


class RequestStatus(str, Enum):
    MATCHING = "matching"
    AWAITING_RESPONSES = "awaiting_responses"
    MANUAL_BROADCAST = "manual_broadcast"
    PARTIALLY_FULFILLED = "partially_fulfilled"
    FULFILLED = "fulfilled"
    EXPIRED = "expired"
    CANCELLED = "cancelled"


class RequestLocation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    facility_id: str = Field(min_length=1, max_length=128)
    facility_name: str = Field(min_length=1, max_length=200)
    area: str = Field(min_length=1, max_length=120)
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    verified: bool


class EmergencyRequestIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requester_id: str = Field(min_length=1, max_length=128)
    blood_type: BloodType
    units: int = Field(ge=1, le=20)
    urgency: Urgency
    response_deadline: datetime
    location: RequestLocation
    contact_method: ContactMethod
    note: str = Field(default="", max_length=180)
    genuine_request_confirmed: bool
    sharing_consent_confirmed: bool
    ai_matching_enabled: bool = True
    idempotency_key: str = Field(min_length=16, max_length=128)

    @field_validator("response_deadline")
    @classmethod
    def deadline_must_be_future(cls, value: datetime) -> datetime:
        now = datetime.now(timezone.utc)
        normalized = value if value.tzinfo else value.replace(tzinfo=timezone.utc)
        if normalized <= now:
            raise ValueError("response_deadline must be in the future")
        return normalized

    @field_validator("note")
    @classmethod
    def note_must_not_contain_obvious_sensitive_data(cls, value: str) -> str:
        blocked_terms = ("patient name", "diagnosis", "medical record", "dob:")
        lowered = value.lower()
        if any(term in lowered for term in blocked_terms):
            raise ValueError("note must not contain patient-identifying or diagnostic information")
        return value.strip()


class Donor(BaseModel):
    model_config = ConfigDict(extra="forbid")

    donor_id: str
    display_name: str
    blood_type: BloodType
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    available: bool
    availability_updated_at: datetime
    verified: bool
    service_radius_km: float = Field(gt=0, le=500)
    estimated_response_probability: float = Field(ge=0, le=1)


class MatchExplanation(BaseModel):
    eligible: bool
    factors: list[str]
    score_breakdown: dict[str, float]


class DonorMatch(BaseModel):
    donor_id: str
    display_name: str
    blood_type: BloodType
    distance_km: float
    estimated_travel_minutes: int
    score: float
    explanation: MatchExplanation


class EmergencyRequestOut(BaseModel):
    request_id: str
    status: RequestStatus
    created_at: datetime
    expires_at: datetime
    matches: list[DonorMatch]
    matching_version: str
    notifications_created: int


class ManualFallbackOut(BaseModel):
    request_id: str
    status: RequestStatus = RequestStatus.MANUAL_BROADCAST
    reason: str
    eligible_audience_filter: dict[str, str]


class EmergencyRequestStatusOut(BaseModel):
    request_id: str
    status: RequestStatus
    notifications_created: int
    matches_responded: int
    reason: str | None = None


class RequestActionOut(BaseModel):
    request_id: str
    status: RequestStatus
    reason: str | None = None


class ContactSelectedDonorsIn(BaseModel):
    donor_ids: list[str] = Field(min_length=1, max_length=10)


class ContactSelectedDonorsOut(BaseModel):
    request_id: str
    donor_ids: list[str]
    status: str = "contact_requested"


class RequestRecord(BaseModel):
    request_id: str
    payload: EmergencyRequestIn
    status: RequestStatus
    created_at: datetime
    expires_at: datetime
    matches: list[DonorMatch] = Field(default_factory=list)


class RequestStore(Protocol):
    def get_by_idempotency_key(self, key: str) -> RequestRecord | None: ...

    def save(self, record: RequestRecord) -> None: ...


class DonorRepository(Protocol):
    def list_active_donors(self) -> list[Donor]: ...


class InMemoryRequestStore:
    def __init__(self) -> None:
        self.records: dict[str, RequestRecord] = {}
        self.by_idempotency: dict[str, str] = {}

    def get_by_idempotency_key(self, key: str) -> RequestRecord | None:
        request_id = self.by_idempotency.get(key)
        return self.records.get(request_id) if request_id else None

    def save(self, record: RequestRecord) -> None:
        self.records[record.request_id] = record
        self.by_idempotency[record.payload.idempotency_key] = record.request_id


class DemoDonorRepository:
    def __init__(self) -> None:
        now = datetime.now(timezone.utc)
        self.donors = [
            Donor(
                donor_id="donor-dana",
                display_name="Donor Dana",
                blood_type=BloodType.O_NEG,
                latitude=14.6466,
                longitude=121.0437,
                available=True,
                availability_updated_at=now,
                verified=True,
                service_radius_km=15,
                estimated_response_probability=0.86,
            ),
            Donor(
                donor_id="donor-mika",
                display_name="Donor Mika",
                blood_type=BloodType.O_POS,
                latitude=14.5995,
                longitude=120.9842,
                available=True,
                availability_updated_at=now,
                verified=True,
                service_radius_km=20,
                estimated_response_probability=0.70,
            ),
            Donor(
                donor_id="donor-lee",
                display_name="Donor Lee",
                blood_type=BloodType.A_NEG,
                latitude=14.6760,
                longitude=121.0437,
                available=False,
                availability_updated_at=now,
                verified=True,
                service_radius_km=12,
                estimated_response_probability=0.25,
            ),
        ]

    def list_active_donors(self) -> list[Donor]:
        return list(self.donors)


from .donor_api import (
    DonorAvailability,
    DonorAvailabilityIn,
    DonorInboxItem,
    DonorProfileIn,
    DonorProfileOut,
    DonorResponseIn,
    DonorResponseOut,
    profile_to_out,
)


request_store = InMemoryRequestStore()
donor_repository = DemoDonorRepository()
donor_profiles: dict[str, Donor] = {donor.donor_id: donor for donor in donor_repository.donors}
donor_availability: dict[str, DonorAvailability] = {
    donor.donor_id: DonorAvailability.AVAILABLE if donor.available else DonorAvailability.OFFLINE
    for donor in donor_repository.donors
}


def get_request_store() -> RequestStore:
    return request_store


def get_donor_repository() -> DonorRepository:
    return donor_repository


@app.put("/v1/donors/{donor_id}", response_model=DonorProfileOut)
def register_donor(donor_id: str, payload: DonorProfileIn, principal: Principal = Depends(get_principal)) -> DonorProfileOut:
    require_owner(principal, donor_id)
    if donor_id != payload.donor_id:
        raise HTTPException(status_code=400, detail="Path donor_id must match payload donor_id")
    now = datetime.now(timezone.utc)
    donor = Donor(
        donor_id=payload.donor_id,
        display_name=payload.display_name,
        blood_type=payload.blood_type,
        latitude=payload.latitude,
        longitude=payload.longitude,
        available=False,
        availability_updated_at=now,
        verified=payload.verified,
        service_radius_km=payload.service_radius_km,
        estimated_response_probability=0.50,
    )
    donor_profiles[donor_id] = donor
    donor_repository.donors = [existing for existing in donor_repository.donors if existing.donor_id != donor_id] + [donor]
    donor_availability[donor_id] = DonorAvailability.OFFLINE
    return profile_to_out(donor, DonorAvailability.OFFLINE)


@app.patch("/v1/donors/{donor_id}/availability", response_model=DonorProfileOut)
def update_donor_availability(donor_id: str, payload: DonorAvailabilityIn, principal: Principal = Depends(get_principal)) -> DonorProfileOut:
    require_owner(principal, donor_id)
    donor = donor_profiles.get(donor_id)
    if donor is None:
        raise HTTPException(status_code=404, detail="Donor not found")
    donor_profiles[donor_id] = donor.model_copy(
        update={
            "available": payload.availability == DonorAvailability.AVAILABLE,
            "availability_updated_at": datetime.now(timezone.utc),
        }
    )
    donor_repository.donors = [
        donor_profiles.get(existing.donor_id, existing)
        for existing in donor_repository.donors
    ]
    donor_availability[donor_id] = payload.availability
    return profile_to_out(donor_profiles[donor_id], payload.availability)


@app.get("/v1/donors/{donor_id}/requests", response_model=list[DonorInboxItem])
def donor_request_inbox(donor_id: str, principal: Principal = Depends(get_principal)) -> list[DonorInboxItem]:
    require_owner(principal, donor_id)
    donor = donor_profiles.get(donor_id)
    if donor is None:
        raise HTTPException(status_code=404, detail="Donor not found")
    items: list[DonorInboxItem] = []
    for record in request_store.records.values():
        for match in record.matches:
            if match.donor_id == donor_id and record.status not in {RequestStatus.CANCELLED, RequestStatus.EXPIRED}:
                items.append(DonorInboxItem(
                    request_id=record.request_id,
                    blood_type=record.payload.blood_type,
                    units=record.payload.units,
                    urgency=record.payload.urgency.value,
                    facility_name=record.payload.location.facility_name,
                    area=record.payload.location.area,
                    distance_km=match.distance_km,
                    status=match_status(record.request_id, donor_id),
                ))
    return items


@app.post("/v1/donors/{donor_id}/requests/{request_id}/response", response_model=DonorResponseOut)
def donor_respond(donor_id: str, request_id: str, payload: DonorResponseIn, principal: Principal = Depends(get_principal)) -> DonorResponseOut:
    require_owner(principal, donor_id)
    record = request_store.records.get(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    match = next((item for item in record.matches if item.donor_id == donor_id), None)
    if match is None:
        raise HTTPException(status_code=403, detail="Donor is not eligible for this request")
    match_statuses.setdefault((request_id, donor_id), {})["response"] = payload.response
    match_statuses[(request_id, donor_id)]["responded_at"] = datetime.now(timezone.utc)
    return DonorResponseOut(
        request_id=request_id,
        donor_id=donor_id,
        response=payload.response,
        responded_at=match_statuses[(request_id, donor_id)]["responded_at"],
    )


match_statuses: dict[tuple[str, str], dict[str, object]] = {}


def match_status(request_id: str, donor_id: str) -> str:
    return str(match_statuses.get((request_id, donor_id), {}).get("response", "not_responded"))


# Recipient -> compatible donor blood types.
COMPATIBLE_DONORS: dict[BloodType, set[BloodType]] = {
    BloodType.A_POS: {BloodType.A_POS, BloodType.A_NEG, BloodType.O_POS, BloodType.O_NEG},
    BloodType.A_NEG: {BloodType.A_NEG, BloodType.O_NEG},
    BloodType.B_POS: {BloodType.B_POS, BloodType.B_NEG, BloodType.O_POS, BloodType.O_NEG},
    BloodType.B_NEG: {BloodType.B_NEG, BloodType.O_NEG},
    BloodType.AB_POS: {
        BloodType.A_POS, BloodType.A_NEG, BloodType.B_POS, BloodType.B_NEG,
        BloodType.AB_POS, BloodType.AB_NEG, BloodType.O_POS, BloodType.O_NEG,
    },
    BloodType.AB_NEG: {BloodType.A_NEG, BloodType.B_NEG, BloodType.AB_NEG, BloodType.O_NEG},
    BloodType.O_POS: {BloodType.O_POS, BloodType.O_NEG},
    BloodType.O_NEG: {BloodType.O_NEG},
    BloodType.UNKNOWN: set(),
}


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    earth_radius_km = 6371.0
    d_lat = radians(lat2 - lat1)
    d_lon = radians(lon2 - lon1)
    a = sin(d_lat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(d_lon / 2) ** 2
    return earth_radius_km * 2 * atan2(sqrt(a), sqrt(1 - a))


def estimate_travel_minutes(distance_km: float) -> int:
    # Replace with a routing provider in production. This is deliberately conservative.
    average_speed_kmh = 22.0
    return max(4, round(distance_km / average_speed_kmh * 60))


def score_donor(request: EmergencyRequestIn, donor: Donor, now: datetime) -> DonorMatch | None:
    if request.blood_type == BloodType.UNKNOWN:
        return None
    if donor.blood_type not in COMPATIBLE_DONORS[request.blood_type]:
        return None
    if not donor.available or not donor.verified:
        return None

    distance_km = haversine_km(
        request.location.latitude,
        request.location.longitude,
        donor.latitude,
        donor.longitude,
    )
    if distance_km > donor.service_radius_km:
        return None

    travel_minutes = estimate_travel_minutes(distance_km)
    age_minutes = max(0.0, (now - donor.availability_updated_at).total_seconds() / 60)
    freshness_score = max(0.0, 1.0 - min(age_minutes, 240.0) / 240.0)
    distance_score = max(0.0, 1.0 - min(distance_km, donor.service_radius_km) / donor.service_radius_km)
    travel_score = max(0.0, 1.0 - min(travel_minutes, 120) / 120.0)
    verification_score = 1.0 if donor.verified else 0.0
    urgency_weight = {Urgency.CRITICAL: 1.0, Urgency.URGENT: 0.85, Urgency.PLANNED: 0.65}[request.urgency]

    # This score ranks operational fit; it does not replace medical eligibility rules.
    breakdown = {
        "distance": round(distance_score * 30, 2),
        "travel_time": round(travel_score * 25, 2),
        "availability_freshness": round(freshness_score * 15, 2),
        "verification": round(verification_score * 15, 2),
        "response_likelihood": round(donor.estimated_response_probability * 10, 2),
        "urgency_priority": round(urgency_weight * 5, 2),
    }
    score = round(sum(breakdown.values()), 2)
    factors = [
        f"Blood type {donor.blood_type.value} is eligible for {request.blood_type.value}",
        f"Estimated travel time is {travel_minutes} minutes",
        f"Donor is approximately {distance_km:.1f} km from the facility",
        "Availability was recently confirmed",
        "Donor verification is complete",
    ]

    return DonorMatch(
        donor_id=donor.donor_id,
        display_name=donor.display_name,
        blood_type=donor.blood_type,
        distance_km=round(distance_km, 2),
        estimated_travel_minutes=travel_minutes,
        score=score,
        explanation=MatchExplanation(
            eligible=True,
            factors=factors,
            score_breakdown=breakdown,
        ),
    )


def validate_business_rules(payload: EmergencyRequestIn) -> None:
    if not payload.location.verified:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Requests must target a verified facility.",
        )
    if not payload.genuine_request_confirmed or not payload.sharing_consent_confirmed:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Both request confirmation and donor-sharing consent are required.",
        )
    if payload.urgency == Urgency.CRITICAL:
        remaining_seconds = (payload.response_deadline - datetime.now(timezone.utc)).total_seconds()
        if remaining_seconds > 2 * 60 * 60 + 60:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Critical requests must have a response deadline within approximately two hours.",
            )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "lifelink-matching"}


@app.post("/v1/emergency-requests", response_model=EmergencyRequestOut | ManualFallbackOut, status_code=201)
def create_emergency_request(
    payload: EmergencyRequestIn,
    idempotency_header: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
    store: RequestStore = Depends(get_request_store),
    donors: DonorRepository = Depends(get_donor_repository),
    principal: Principal = Depends(get_principal),
) -> EmergencyRequestOut | ManualFallbackOut:
    validate_business_rules(payload)
    if principal.subject != "development-user":
        require_owner(principal, payload.requester_id)

    if idempotency_header and idempotency_header != payload.idempotency_key:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Idempotency-Key header must match payload.idempotency_key.",
        )

    existing = store.get_by_idempotency_key(payload.idempotency_key)
    if existing:
        return EmergencyRequestOut(
            request_id=existing.request_id,
            status=existing.status,
            created_at=existing.created_at,
            expires_at=existing.expires_at,
            matches=existing.matches,
            matching_version="v1-explainable-weighted",
            notifications_created=len(existing.matches),
        )

    now = datetime.now(timezone.utc)
    request_id = f"req_{uuid4().hex}"
    matches = [
        scored
        for donor in donors.list_active_donors()
        if (scored := score_donor(payload, donor, now)) is not None
    ]
    if payload.ai_matching_enabled:
        matches.sort(key=lambda item: (-item.score, item.estimated_travel_minutes, item.distance_km))
        matching_version = "v1-explainable-weighted"
    else:
        matches.sort(key=lambda item: (item.distance_km, item.estimated_travel_minutes, -item.score))
        matching_version = "v1-distance-only"
    matches = matches[:10]

    if payload.blood_type == BloodType.UNKNOWN:
        record = RequestRecord(
            request_id=request_id,
            payload=payload,
            status=RequestStatus.MANUAL_BROADCAST,
            created_at=now,
            expires_at=payload.response_deadline,
        )
        store.save(record)
        return ManualFallbackOut(
            request_id=request_id,
            reason="Blood type must be verified by a blood-bank professional before automatic eligibility matching.",
            eligible_audience_filter={"verification": "verified", "status": "manual_review"},
        )

    record = RequestRecord(
        request_id=request_id,
        payload=payload,
        status=RequestStatus.AWAITING_RESPONSES,
        created_at=now,
        expires_at=payload.response_deadline,
        matches=matches,
    )
    store.save(record)

    return EmergencyRequestOut(
        request_id=request_id,
        status=RequestStatus.AWAITING_RESPONSES,
        created_at=now,
        expires_at=payload.response_deadline,
        matches=matches,
        matching_version=matching_version,
        notifications_created=len(matches),
    )


@app.post("/v1/emergency-requests/{request_id}/contact", response_model=ContactSelectedDonorsOut)
def contact_selected_donors(
    request_id: str,
    payload: ContactSelectedDonorsIn,
    principal: Principal = Depends(get_principal),
) -> ContactSelectedDonorsOut:
    record = request_store.records.get(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    require_owner(principal, record.payload.requester_id)
    eligible_ids = {match.donor_id for match in record.matches}
    unknown = [donor_id for donor_id in payload.donor_ids if donor_id not in eligible_ids]
    if unknown:
        raise HTTPException(status_code=400, detail="One or more selected donors are not eligible for this request")
    for donor_id in payload.donor_ids:
        match_statuses.setdefault((request_id, donor_id), {})["contact_requested"] = True
    return ContactSelectedDonorsOut(request_id=request_id, donor_ids=payload.donor_ids)


@app.post("/v1/emergency-requests/{request_id}/manual-broadcast", response_model=ManualFallbackOut)
def manual_broadcast(
    request_id: str,
    store: RequestStore = Depends(get_request_store),
) -> ManualFallbackOut:
    record = next((r for r in request_store.records.values() if r.request_id == request_id), None) \
        if isinstance(store, InMemoryRequestStore) else None
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    record.status = RequestStatus.MANUAL_BROADCAST
    store.save(record)
    return ManualFallbackOut(
        request_id=request_id,
        reason="Automatic ranking is unavailable or the request requires manual blood-bank review.",
        eligible_audience_filter={
            "blood_type": record.payload.blood_type.value,
            "verification": "verified",
            "area": record.payload.location.area,
        },
    )


@app.get("/v1/emergency-requests/{request_id}", response_model=EmergencyRequestStatusOut)
def emergency_request_status(request_id: str, principal: Principal = Depends(get_principal)) -> EmergencyRequestStatusOut:
    record = next((r for r in request_store.records.values() if r.request_id == request_id), None)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    require_owner(principal, record.payload.requester_id)
    responded = sum(1 for (rid, _), value in match_statuses.items() if rid == request_id and value.get("response") in {"accepted", "arrived"})
    return EmergencyRequestStatusOut(
        request_id=request_id,
        status=record.status,
        notifications_created=len(record.matches),
        matches_responded=responded,
        reason="Manual review is required." if record.status == RequestStatus.MANUAL_BROADCAST else None,
    )


@app.post("/v1/emergency-requests/{request_id}/cancel", response_model=RequestActionOut)
def cancel_emergency_request(request_id: str, principal: Principal = Depends(get_principal)) -> RequestActionOut:
    record = next((r for r in request_store.records.values() if r.request_id == request_id), None)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    require_owner(principal, record.payload.requester_id)
    record.status = RequestStatus.CANCELLED
    request_store.save(record)
    return RequestActionOut(request_id=request_id, status=RequestStatus.CANCELLED, reason="Cancelled by coordinator")
