"""Request/response models for the separate donor-profile flow.

A user creates a normal account first and then opts in to donating through
these models. The account identity always comes from the authenticated
principal, never from the request body, so a caller can only ever read or
write their own donor profile.
"""

from __future__ import annotations

from datetime import date, datetime

from pydantic import BaseModel, Field

from .donor_api import DonorAvailability
from .main import BloodType


class DonorProfileUpsertIn(BaseModel):
    """Opt-in / update payload for the current user's donor profile."""

    blood_type: BloodType
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    area: str = Field(default="", max_length=120)
    service_radius_km: float = Field(default=15, gt=0, le=500)
    availability_status: DonorAvailability = DonorAvailability.OFFLINE
    last_donation_date: date | None = None
    notifications_enabled: bool = True
    display_name: str | None = Field(default=None, max_length=160)
    donor_note: str = Field(default="", max_length=500)
    preferred_contact_method: str = Field(default="in_app", pattern="^(in_app|phone)$")
    profile_visible: bool = True


class DonorAvailabilityToggleIn(BaseModel):
    availability_status: DonorAvailability


class DonorProfileMeOut(BaseModel):
    """The current user's own donor profile, including server-controlled fields."""

    user_id: str
    donor_id: str
    blood_type: BloodType
    latitude: float
    longitude: float
    area: str
    service_radius_km: float
    availability_status: DonorAvailability
    last_donation_date: date | None = None
    verified: bool
    notifications_enabled: bool
    display_name: str
    donor_note: str
    preferred_contact_method: str
    profile_visible: bool
    created_at: datetime | None = None
    updated_at: datetime | None = None
