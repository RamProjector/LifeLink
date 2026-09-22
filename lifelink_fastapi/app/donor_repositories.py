from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from uuid import uuid4

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import joinedload

from .db_models import Donor as DonorRow, DonorContactRequest, EmergencyRequest as RequestRow, MatchStatusEnum, RequestMatch as MatchRow
from .donor_api import DonorAvailability, DonorProfileIn, DonorResponseIn
from .expiry import is_request_expired


class SqlAlchemyDonorStore:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def upsert_profile(self, donor_id: str, payload: DonorProfileIn) -> DonorRow:
        row = await self.session.get(DonorRow, donor_id)
        now = datetime.now(timezone.utc)
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
                verified=payload.verified,
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
            row.verified = payload.verified
            row.donor_note = payload.donor_note
            row.preferred_contact_method = payload.preferred_contact_method
            row.pause_reason = payload.pause_reason
            row.profile_visible = payload.profile_visible
            row.updated_at = now
        await self.session.commit()
        return row

    async def set_availability(self, donor_id: str, availability: DonorAvailability) -> DonorRow:
        row = await self.session.get(DonorRow, donor_id)
        if row is None:
            raise KeyError(donor_id)
        row.available = availability == DonorAvailability.AVAILABLE
        row.availability_updated_at = datetime.now(timezone.utc)
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
            .where(MatchRow.donor_id == donor_id, RequestRow.requester_id != donor_id)
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
        if is_request_expired(request.status.value, request.response_deadline):
            request.status = "expired"
            await self.session.commit()
            raise ValueError("This request has expired and is no longer accepting donor responses")
        if request.status.value in {"cancelled", "expired", "fulfilled"}:
            raise ValueError("This request is no longer accepting donor responses")
        match.status = {
            "accepted": MatchStatusEnum.CONFIRMED.value,
            "declined": MatchStatusEnum.DECLINED.value,
            "arrived": MatchStatusEnum.CONFIRMED.value,
        }[response.response]
        now = datetime.now(timezone.utc)
        match.responded_at = now
        contact = await self.session.scalar(
            select(DonorContactRequest).where(
                DonorContactRequest.request_id == request_id,
                DonorContactRequest.donor_id == donor_id,
            )
        )
        if contact is not None:
            contact.status = response.response
            contact.updated_at = now
            if response.response == "accepted":
                contact.accepted_at = now
                contact.contact_shared_at = now
        await self.session.commit()
        return match
