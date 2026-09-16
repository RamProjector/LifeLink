from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from pydantic import BaseModel, Field

from .main import BloodType, Donor, DonorMatch, RequestStatus


class DonorAvailability(str, Enum):
    AVAILABLE = "available"
    PAUSED = "paused"
    OFFLINE = "offline"


class DonorProfileIn(BaseModel):
    donor_id: str = Field(min_length=1, max_length=128)
    display_name: str = Field(min_length=1, max_length=160)
    blood_type: BloodType
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    service_radius_km: float = Field(gt=0, le=500)
    verified: bool = False


class DonorAvailabilityIn(BaseModel):
    availability: DonorAvailability


class DonorProfileOut(DonorProfileIn):
    availability: DonorAvailability
    availability_updated_at: datetime


class DonorInboxItem(BaseModel):
    request_id: str
    blood_type: BloodType
    units: int
    urgency: str
    facility_name: str
    area: str
    distance_km: float
    status: str
    responded_at: datetime | None = None


class DonorResponseIn(BaseModel):
    response: str = Field(pattern="^(accepted|declined|arrived)$")


class DonorResponseOut(BaseModel):
    request_id: str
    donor_id: str
    response: str
    responded_at: datetime


def profile_to_out(profile: Donor, availability: DonorAvailability) -> DonorProfileOut:
    return DonorProfileOut(
        donor_id=profile.donor_id,
        display_name=profile.display_name,
        blood_type=profile.blood_type,
        latitude=profile.latitude,
        longitude=profile.longitude,
        service_radius_km=profile.service_radius_km,
        verified=profile.verified,
        availability=availability,
        availability_updated_at=profile.availability_updated_at,
    )
