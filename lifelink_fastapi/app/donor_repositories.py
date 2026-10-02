from __future__ import annotations

import logging
from datetime import UTC, datetime, timezone
from decimal import Decimal
from uuid import uuid4

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import joinedload

from .db_models import (
    Donor as DonorRow,
)
from .db_models import (
    DonorContactRequest,
    MatchStatusEnum,
    RequestStatusEnum,
)
from .db_models import (
    EmergencyRequest as RequestRow,
)
from .db_models import (
    RequestMatch as MatchRow,
)
from .donor_api import DonorAvailability, DonorProfileIn, DonorResponseIn
from .expiry import ACTIVE_REQUEST_STATUSES, is_request_expired
from .main import Donor, EmergencyRequestIn, score_donor

logger = logging.getLogger("lifelink.donor_repositories")


def _enum_value(value: object) -> object:
    """Enum columns are members when loaded but plain strings on rows changed in-session."""
    return getattr(value, "value", value)


def apply_donor_response_to_contact(
    contact: DonorContactRequest,
    response: DonorResponseIn,
    now: datetime,
) -> DonorContactRequest:
    """Persist donor consent without treating acceptance as contact disclosure."""
    contact.status = response.response
    contact.updated_at = now
    if response.response in {"accepted", "arrived"}:
        contact.accepted_at = contact.accepted_at or now
    return contact


class SqlAlchemyDonorStore:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def get_by_identity(self, donor_id: str) -> DonorRow | None:
        """Resolve both current user-keyed rows and legacy donor rows."""
        row = await self.session.get(DonorRow, donor_id)
        if row is not None:
            return row
        return await self.session.scalar(
            select(DonorRow).where(DonorRow.user_id == donor_id).limit(1)
        )

    async def upsert_profile(self, donor_id: str, payload: DonorProfileIn) -> DonorRow:
        row = await self.get_by_identity(donor_id)
        now = datetime.now(UTC)
        if row is None:
            row = DonorRow(
                id=donor_id,
                user_id=donor_id,
                display_name=payload.display_name,
                blood_type=payload.blood_type.value,
                latitude=Decimal(str(payload.latitude)),
                longitude=Decimal(str(payload.longitude)),
                available=False,
                availability_updated_at=now,
                verified=False,  # set by the server/admin, never by the client
                service_radius_km=Decimal(str(payload.service_radius_km)),
                estimated_response_probability=Decimal("0.50"),
                donor_note=payload.donor_note,
                preferred_contact_method=payload.preferred_contact_method,
                pause_reason=payload.pause_reason,
                profile_visible=payload.profile_visible,
            )
            self.session.add(row)
        else:
            row.user_id = donor_id
            row.display_name = payload.display_name
            row.blood_type = payload.blood_type.value
            row.latitude = Decimal(str(payload.latitude))
            row.longitude = Decimal(str(payload.longitude))
            row.service_radius_km = Decimal(str(payload.service_radius_km))
            # `verified` is intentionally left as stored: clients cannot verify themselves,
            # and re-saving a profile must not un-verify an already verified donor.
            row.donor_note = payload.donor_note
            row.preferred_contact_method = payload.preferred_contact_method
            row.pause_reason = payload.pause_reason
            row.profile_visible = payload.profile_visible
            row.updated_at = now
        await self.session.commit()
        return row

    async def recompute_matches_for_donor(self, donor_id: str) -> None:
        """Re-evaluate this donor's matches against every active request.

        Matches are normally created only when a request is submitted, so a
        donor who later changes their blood type (or location/radius) would
        keep seeing the old set. Re-running the eligibility predicate here
        makes a blood-type change take effect immediately: newly-compatible
        requests appear in the inbox and requests that no longer match are
        removed.
        """
        row = await self.get_by_identity(donor_id)
        if row is None:
            return
        donor = Donor(
            donor_id=row.id,
            display_name=row.display_name,
            blood_type=_enum_value(row.blood_type),
            latitude=float(row.latitude),
            longitude=float(row.longitude),
            available=row.available,
            availability_updated_at=row.availability_updated_at,
            verified=row.verified,
            service_radius_km=float(row.service_radius_km),
            estimated_response_probability=float(row.estimated_response_probability),
        )
        now = datetime.now(timezone.utc)
        result = await self.session.scalars(
            select(RequestRow)
            .options(joinedload(RequestRow.facility))
            .where(
                RequestRow.requester_id != row.id,
                RequestRow.status.in_(ACTIVE_REQUEST_STATUSES),
                RequestRow.response_deadline > now,
            )
        )
        active_requests = list(result.unique().all())

        # Mirror request-time matching: a donor only matches while available and
        # profile-visible, and only when the eligibility predicate accepts them.
        donor_visible = bool(row.profile_visible)
        eligible: dict[str, object] = {}
        for request_row in active_requests:
            try:
                payload = EmergencyRequestIn.model_validate({
                    "requester_id": request_row.requester_id,
                    "blood_type": _enum_value(request_row.blood_type),
                    "units": request_row.units,
                    "urgency": _enum_value(request_row.urgency),
                    "response_deadline": request_row.response_deadline,
                    "location": {
                        "facility_id": request_row.facility_id,
                        "facility_name": request_row.facility.name if request_row.facility else "Requester location",
                        "area": request_row.facility.area if request_row.facility else "Approximate area",
                        "latitude": float(request_row.requester_latitude),
                        "longitude": float(request_row.requester_longitude),
                        "precision_meters": request_row.location_precision_meters,
                        "verified": request_row.facility.verified if request_row.facility else False,
                    },
                    "contact_method": _enum_value(request_row.contact_method),
                    "note": request_row.note,
                    "genuine_request_confirmed": request_row.genuine_request_confirmed,
                    "sharing_consent_confirmed": request_row.sharing_consent_confirmed,
                    "ai_matching_enabled": True,
                    "idempotency_key": request_row.idempotency_key,
                }, context={"stored": True})
            except Exception:
                logger.exception(
                    "Skipping unreadable request row id=%s during donor match recompute", request_row.id
                )
                continue
            scored = score_donor(payload, donor, now) if donor_visible else None
            if scored is not None:
                eligible[request_row.id] = scored

        existing_matches = list(
            (await self.session.scalars(select(MatchRow).where(MatchRow.donor_id == row.id))).all()
        )
        existing_by_request = {match.request_id: match for match in existing_matches}

        for request_id, scored in eligible.items():
            if request_id in existing_by_request:
                continue
            self.session.add(
                MatchRow(
                    id=f"match_{uuid4().hex}",
                    request_id=request_id,
                    donor_id=row.id,
                    rank=1,
                    score=Decimal(str(scored.score)),
                    distance_km=Decimal(str(scored.distance_km)),
                    estimated_travel_minutes=scored.estimated_travel_minutes,
                    status="ranked",
                    explanation=scored.explanation.model_dump(mode="json"),
                )
            )

        for match in existing_matches:
            if match.request_id in eligible:
                continue
            # Never drop a match the donor already acted on, or one that has a
            # live contact request: that would break the contact lifecycle.
            if _enum_value(match.status) not in {"ranked", "notified"}:
                continue
            contact = await self.session.scalar(
                select(DonorContactRequest).where(
                    DonorContactRequest.request_id == match.request_id,
                    DonorContactRequest.donor_id == row.id,
                )
            )
            if contact is not None:
                continue
            await self.session.delete(match)

        await self.session.commit()

    async def set_availability(self, donor_id: str, availability: DonorAvailability) -> DonorRow:
        row = await self.get_by_identity(donor_id)
        if row is None:
            raise KeyError(donor_id)
        row.available = availability == DonorAvailability.AVAILABLE
        row.availability_updated_at = datetime.now(UTC)
        await self.session.commit()
        # Matching runs when a request is submitted, so a donor who was offline
        # at that moment has no stored match. Going available must re-evaluate
        # the still-open requests, otherwise a donor who turns on availability
        # never receives anything until a brand-new request is created.
        # Best-effort: the availability change is already committed, so a
        # recompute failure must not turn a saved availability into an error.
        if row.available:
            try:
                await self.recompute_matches_for_donor(donor_id)
            except Exception:
                await self.session.rollback()
                logger.exception("Donor match recompute failed after availability change for donor_id=%s", donor_id)
        return row

    async def inbox(self, donor_id: str) -> list[tuple[RequestRow, MatchRow, DonorContactRequest | None]]:
        result = await self.session.execute(
            select(RequestRow, MatchRow, DonorContactRequest)
            .join(MatchRow, MatchRow.request_id == RequestRow.id)
            .outerjoin(
                DonorContactRequest,
                (DonorContactRequest.request_id == RequestRow.id) & (DonorContactRequest.donor_id == donor_id),
            )
            .options(joinedload(RequestRow.facility))
            .where(
                MatchRow.donor_id == donor_id,
                RequestRow.requester_id != donor_id,
                RequestRow.status.in_(ACTIVE_REQUEST_STATUSES),
                RequestRow.response_deadline > datetime.now(UTC),
            )
            .order_by(RequestRow.created_at.desc())
        )
        return list(result.unique().all())

    async def respond(self, donor_id: str, request_id: str, response: DonorResponseIn) -> MatchRow:
        result = await self.session.execute(
            select(MatchRow).where(MatchRow.donor_id == donor_id, MatchRow.request_id == request_id)
        )
        match = result.scalar_one_or_none()
        if match is None:
            raise KeyError(request_id)
        request = await self.session.get(RequestRow, request_id)
        if request is None:
            raise KeyError(request_id)
        if request.requester_id == donor_id:
            raise ValueError("You cannot accept or decline your own request")
        current_status = getattr(request.status, "value", request.status)
        if is_request_expired(current_status, request.response_deadline):
            request.status = RequestStatusEnum.EXPIRED
            await self.session.commit()
            raise ValueError("This request has expired and is no longer accepting donor responses")
        if current_status in {"cancelled", "expired", "fulfilled"}:
            raise ValueError("This request is no longer accepting donor responses")
        match.status = {
            "accepted": MatchStatusEnum.CONFIRMED,
            "declined": MatchStatusEnum.DECLINED,
            "arrived": MatchStatusEnum.CONFIRMED,
        }[response.response]
        now = datetime.now(UTC)
        match.responded_at = now
        contact = await self.session.scalar(
            select(DonorContactRequest).where(
                DonorContactRequest.request_id == request_id,
                DonorContactRequest.donor_id == donor_id,
            )
        )
        if contact is not None:
            apply_donor_response_to_contact(contact, response, now)
        await self.session.commit()
        return match
