"""Request/response models for the confirmed LifeLink privacy behavior.

These models are the API contract for:

* the donor map (approximate area + freshness only, never exact pins),
* donor-controlled map visibility and exact-location sharing,
* matched-requester-only exact location with expiry and revocation,
* in-app conversations scoped to the requester and donor of one request,
* explicit, audited phone/email sharing.

The FastAPI service remains the policy boundary. Nothing here returns exact
donor coordinates unless an active, unexpired location share exists.
"""
from __future__ import annotations

from datetime import datetime
from pydantic import BaseModel, Field

from .main import BloodType


class DonorMapEntry(BaseModel):
    """One approximate donor area on the map.

    Deliberately excludes donor identity and exact coordinates. ``latitude`` and
    ``longitude`` are coarsened to a grid of at least one kilometre so the map
    cannot be used to pinpoint a donor.
    """

    area_label: str
    latitude: float
    longitude: float
    radius_meters: int
    blood_type: BloodType
    availability: str
    freshness_at: datetime
    freshness_age_minutes: int
    is_stale: bool


class DonorMapOut(BaseModel):
    generated_at: datetime
    approximate_only: bool = True
    entries: list[DonorMapEntry] = Field(default_factory=list)


class DonorMapVisibilityIn(BaseModel):
    map_visible: bool
    exact_location_sharing_enabled: bool = False


class DonorMapVisibilityOut(BaseModel):
    donor_id: str
    map_visible: bool
    exact_location_sharing_enabled: bool
    map_visibility_updated_at: datetime | None = None
    freshness_at: datetime | None = None


class LocationShareOut(BaseModel):
    request_id: str
    donor_id: str
    status: str
    shared_at: datetime | None = None
    expires_at: datetime | None = None
    revoked_at: datetime | None = None


class ExactLocationOut(BaseModel):
    """Exact donor location, returned only to the matched requester.

    When ``shared`` is false the coordinate fields are always null, so a client
    can never render a pin it was not authorized to see.
    """

    request_id: str
    donor_id: str
    shared: bool
    latitude: float | None = None
    longitude: float | None = None
    precision_meters: int | None = None
    freshness_at: datetime | None = None
    expires_at: datetime | None = None
    reason: str | None = None


class ConversationOut(BaseModel):
    conversation_id: str
    request_id: str
    donor_id: str
    requester_id: str
    last_message_at: datetime | None = None
    created_at: datetime


class MessageIn(BaseModel):
    body: str = Field(min_length=1, max_length=2000)


class MessageOut(BaseModel):
    message_id: str
    conversation_id: str
    sender_id: str
    body: str
    created_at: datetime


class ContactShareIn(BaseModel):
    field: str = Field(pattern="^(phone|email)$")
    value: str = Field(min_length=3, max_length=320)


class ContactShareOut(BaseModel):
    share_id: str
    conversation_id: str
    shared_by: str
    field: str
    value: str
    created_at: datetime
