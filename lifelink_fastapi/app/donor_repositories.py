from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal

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


def apply_donor_response_to_contact(contact, response: DonorResponseIn, now: datetime):
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

    async def set_availability(self, donor_id: str, availability: DonorAvailability) -> DonorRow:
        row = await self.get_by_identity(donor_id)
        if row is None:
            raise KeyError(donor_id)
        row.available = availability == DonorAvailability.AVAILABLE
        row.availability_updated_at = datetime.now(UTC)
        await self.session.commit()
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
