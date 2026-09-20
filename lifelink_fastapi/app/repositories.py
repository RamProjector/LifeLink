from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from typing import Any
from uuid import uuid4

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import joinedload

from .db_models import (
    BloodTypeEnum,
    Donor as DonorRow,
    EmergencyRequest as EmergencyRequestRow,
    RequestMatch as RequestMatchRow,
    RequestStatusEnum,
    DonorContactRequest,
    LifeLinkProfile,
    AuditEvent,
)
from .main import (
    Donor,
    DonorRepository,
    EmergencyRequestIn,
    MatchExplanation,
    DonorMatch,
    RequestRecord,
    RequestStore,
    RequestStatus,
)
from .expiry import is_request_expired


MATCHING_VERSION = "v1-explainable-weighted"


class SqlAlchemyDonorRepository(DonorRepository):
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def list_active_donors(self) -> list[Donor]:
        result = await self.session.scalars(
            select(DonorRow).where(
                DonorRow.available.is_(True),
                DonorRow.verified.is_(True),
            )
        )
        rows = result.all()
        return [
            Donor(
                donor_id=row.id,
                display_name=row.display_name,
                blood_type=row.blood_type.value,
                latitude=float(row.latitude),
                longitude=float(row.longitude),
                available=row.available,
                availability_updated_at=row.availability_updated_at,
                verified=row.verified,
                service_radius_km=float(row.service_radius_km),
                estimated_response_probability=float(row.estimated_response_probability),
            )
            for row in rows
        ]


class SqlAlchemyRequestStore(RequestStore):
    """Async persistence adapter.

    The original endpoint is synchronous, so an application using this adapter
    should make the route async. The methods below are intentionally explicit
    rather than hiding async I/O behind sync wrappers.
    """

    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def record_audit_async(
        self,
        actor_id: str,
        action: str,
        request_id: str | None = None,
        donor_id: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        self.session.add(AuditEvent(
            id=f"audit_{uuid4().hex}",
            actor_id=actor_id,
            action=action,
            request_id=request_id,
            donor_id=donor_id,
            event_metadata=metadata or {},
        ))
        await self.session.commit()

    async def get_by_idempotency_key_async(self, key: str) -> RequestRecord | None:
        result = await self.session.scalar(
            select(EmergencyRequestRow)
            .options(joinedload(EmergencyRequestRow.matches).joinedload(RequestMatchRow.donor))
            .where(EmergencyRequestRow.idempotency_key == key)
        )
        return self._to_record(result) if result else None

    async def get_by_id_async(self, request_id: str) -> RequestRecord | None:
        result = await self.session.scalar(
            select(EmergencyRequestRow)
            .options(joinedload(EmergencyRequestRow.matches).joinedload(RequestMatchRow.donor))
            .where(EmergencyRequestRow.id == request_id)
        )
        if result is not None:
            await self._expire_if_needed(result)
        return self._to_record(result) if result else None

    async def _expire_if_needed(self, row: EmergencyRequestRow) -> bool:
        if is_request_expired(row.status.value, row.response_deadline):
            row.status = RequestStatusEnum.EXPIRED.value
            await self.session.commit()
            return True
        return False

    async def list_by_requester_async(self, requester_id: str, limit: int = 50) -> list[RequestRecord]:
        result = await self.session.scalars(
            select(EmergencyRequestRow)
            .options(joinedload(EmergencyRequestRow.matches).joinedload(RequestMatchRow.donor))
            .where(EmergencyRequestRow.requester_id == requester_id)
            .order_by(EmergencyRequestRow.created_at.desc())
            .limit(limit)
        )
        return [self._to_record(row) for row in result.unique().all()]

    async def save_async(self, record: RequestRecord) -> None:
        existing = await self.session.get(EmergencyRequestRow, record.request_id)
        if existing is None:
            existing = EmergencyRequestRow(
                id=record.request_id,
                requester_id=record.payload.requester_id,
                facility_id=record.payload.location.facility_id,
                requester_latitude=Decimal(str(record.payload.location.latitude)),
                requester_longitude=Decimal(str(record.payload.location.longitude)),
                location_precision_meters=record.payload.location.precision_meters,
                blood_type=record.payload.blood_type.value,
                units=record.payload.units,
                urgency=record.payload.urgency.value,
                response_deadline=record.payload.response_deadline,
                contact_method=record.payload.contact_method.value,
                note=record.payload.note,
                genuine_request_confirmed=record.payload.genuine_request_confirmed,
                sharing_consent_confirmed=record.payload.sharing_consent_confirmed,
                idempotency_key=record.payload.idempotency_key,
                status=record.status.value,
                matching_version=MATCHING_VERSION,
                created_at=record.created_at,
            )
            self.session.add(existing)
        else:
            existing.status = record.status.value
            existing.response_deadline = record.expires_at

        # Only add matches not already persisted. The unique constraint protects retries.
        existing_match_ids = set()
        if existing.id:
            match_ids = await self.session.scalars(
                select(RequestMatchRow.donor_id).where(RequestMatchRow.request_id == record.request_id)
            )
            existing_match_ids = set(match_ids.all())

        for rank, match in enumerate(record.matches, start=1):
            if match.donor_id in existing_match_ids:
                continue
            self.session.add(
                RequestMatchRow(
                    id=f"match_{uuid4().hex}",
                    request_id=record.request_id,
                    donor_id=match.donor_id,
                    rank=rank,
                    score=Decimal(str(match.score)),
                    distance_km=Decimal(str(match.distance_km)),
                    estimated_travel_minutes=match.estimated_travel_minutes,
                    status="ranked",
                    explanation=match.explanation.model_dump(mode="json"),
                )
            )
        await self.session.commit()

    async def set_manual_broadcast_async(self, request_id: str) -> RequestRecord:
        row = await self.session.get(EmergencyRequestRow, request_id)
        if row is None:
            raise KeyError(request_id)
        if await self._expire_if_needed(row):
            raise ValueError("This request has expired and cannot be broadcast")
        row.status = RequestStatusEnum.MANUAL_BROADCAST.value
        await self.session.commit()
        refreshed = await self.get_by_id_async(request_id)
        if refreshed is None:
            raise KeyError(request_id)
        return refreshed

    async def set_cancelled_async(self, request_id: str) -> RequestRecord:
        row = await self.session.get(EmergencyRequestRow, request_id)
        if row is None:
            raise KeyError(request_id)
        row.status = RequestStatusEnum.CANCELLED.value
        await self.session.commit()
        refreshed = await self.get_by_id_async(request_id)
        if refreshed is None:
            raise KeyError(request_id)
        return refreshed

    async def set_fulfilled_async(self, request_id: str) -> RequestRecord:
        row = await self.session.get(EmergencyRequestRow, request_id)
        if row is None:
            raise KeyError(request_id)
        if await self._expire_if_needed(row):
            raise ValueError("An expired request cannot be fulfilled")
        if row.status.value in {RequestStatusEnum.CANCELLED.value, RequestStatusEnum.EXPIRED.value}:
            raise ValueError("A cancelled or expired request cannot be fulfilled")
        row.status = RequestStatusEnum.FULFILLED.value
        await self.session.commit()
        refreshed = await self.get_by_id_async(request_id)
        if refreshed is None:
            raise KeyError(request_id)
        return refreshed

    async def contact_selected_donors_async(self, request_id: str, donor_ids: list[str]) -> RequestRecord:
        row = await self.session.get(EmergencyRequestRow, request_id)
        if row is None:
            raise KeyError(request_id)
        if await self._expire_if_needed(row):
            raise ValueError("This request has expired and cannot accept contact requests")
        if row.status.value in {RequestStatusEnum.CANCELLED.value, RequestStatusEnum.EXPIRED.value, RequestStatusEnum.FULFILLED.value}:
            raise ValueError("This request is no longer accepting contact requests")
        allowed = {match.donor_id for match in row.matches}
        if any(donor_id not in allowed for donor_id in donor_ids):
            raise ValueError("One or more selected donors are not eligible for this request")
        now = datetime.now(timezone.utc)
        for match in row.matches:
            if match.donor_id in donor_ids:
                match.status = "notified"
                match.notified_at = now
                contact = await self.session.scalar(
                    select(DonorContactRequest).where(
                        DonorContactRequest.request_id == request_id,
                        DonorContactRequest.donor_id == match.donor_id,
                    )
                )
                if contact is None:
                    self.session.add(DonorContactRequest(
                        id=f"contact_{uuid4().hex}",
                        request_id=request_id,
                        donor_id=match.donor_id,
                        requester_id=row.requester_id,
                        status="pending",
                    ))
                elif contact.status not in {"accepted", "cancelled"}:
                    contact.status = "pending"
                    contact.updated_at = now
        await self.session.commit()
        refreshed = await self.get_by_id_async(request_id)
        if refreshed is None:
            raise KeyError(request_id)
        return refreshed

    async def requester_contacts_async(self, request_id: str, requester_id: str) -> list[dict[str, Any]]:
        result = await self.session.execute(
            select(DonorContactRequest, DonorRow, LifeLinkProfile)
            .join(DonorRow, DonorRow.id == DonorContactRequest.donor_id)
            .outerjoin(LifeLinkProfile, LifeLinkProfile.user_id == DonorRow.user_id)
            .where(
                DonorContactRequest.request_id == request_id,
                DonorContactRequest.requester_id == requester_id,
            )
            .order_by(DonorContactRequest.created_at.asc())
        )
        items: list[dict[str, Any]] = []
        for contact, donor, profile in result.all():
            items.append({
                "donor_id": donor.id,
                "display_name": donor.display_name,
                "status": contact.status,
                "accepted_at": contact.accepted_at,
                "contact_email": profile.email if contact.status in {"accepted", "contact_shared", "meeting_arranged", "fulfilled"} and profile else None,
            })
        return items

    async def update_contact_status_async(self, request_id: str, donor_id: str, requester_id: str, status: str) -> dict[str, Any]:
        allowed = {"contact_shared", "meeting_arranged", "fulfilled", "cancelled"}
        if status not in allowed:
            raise ValueError("Unsupported contact lifecycle status")
        request = await self.session.get(EmergencyRequestRow, request_id)
        if request is None:
            raise KeyError(request_id)
        if await self._expire_if_needed(request):
            raise ValueError("This request has expired; contact actions are closed")
        if request.status.value in {RequestStatusEnum.CANCELLED.value, RequestStatusEnum.FULFILLED.value}:
            raise ValueError("This request is terminal; contact actions are closed")
        contact = await self.session.scalar(select(DonorContactRequest).where(
            DonorContactRequest.request_id == request_id,
            DonorContactRequest.donor_id == donor_id,
            DonorContactRequest.requester_id == requester_id,
        ))
        if contact is None:
            raise KeyError(request_id)
        transitions = {
            "accepted": {"contact_shared", "cancelled"},
            "contact_shared": {"meeting_arranged", "fulfilled", "cancelled"},
            "meeting_arranged": {"fulfilled", "cancelled"},
            "fulfilled": set(),
            "cancelled": set(),
        }
        if status not in transitions.get(contact.status, set()):
            raise ValueError(f"Cannot move contact from {contact.status} to {status}")
        now = datetime.now(timezone.utc)
        contact.status = status
        contact.updated_at = now
        if status == "contact_shared":
            contact.contact_shared_at = now
        await self.session.commit()
        await self.record_audit_async(requester_id, f"contact_{status}", request_id, donor_id)
        return {"donor_id": donor_id, "status": status, "accepted_at": contact.accepted_at}

    @staticmethod
    def _to_record(row: EmergencyRequestRow) -> RequestRecord:
        payload = EmergencyRequestIn(
            requester_id=row.requester_id,
            blood_type=row.blood_type.value,
            units=row.units,
            urgency=row.urgency.value,
            response_deadline=row.response_deadline,
            location={
                "facility_id": row.facility_id,
                "facility_name": row.facility.name if row.facility else "Requester location",
                "area": row.facility.area if row.facility else "Approximate area",
                "latitude": float(row.requester_latitude),
                "longitude": float(row.requester_longitude),
                "precision_meters": row.location_precision_meters,
                "verified": row.facility.verified if row.facility else False,
            },
            contact_method=row.contact_method.value,
            note=row.note,
            genuine_request_confirmed=row.genuine_request_confirmed,
            sharing_consent_confirmed=row.sharing_consent_confirmed,
            ai_matching_enabled=True,
            idempotency_key=row.idempotency_key,
        )
        matches = [
            DonorMatch(
                donor_id=match.donor_id,
                display_name=match.donor.display_name,
                blood_type=match.donor.blood_type.value,
                distance_km=float(match.distance_km),
                estimated_travel_minutes=match.estimated_travel_minutes,
                score=float(match.score),
                explanation=MatchExplanation.model_validate(match.explanation),
            )
            for match in sorted(row.matches, key=lambda item: item.rank)
            if match.donor is not None
        ]
        return RequestRecord(
            request_id=row.id,
            payload=payload,
            status=RequestStatus(row.status.value),
            created_at=row.created_at,
            expires_at=row.response_deadline,
            matches=matches,
        )


async def create_request_record(
    session: AsyncSession,
    payload: EmergencyRequestIn,
    matches: list[DonorMatch],
    request_id: str,
    status: RequestStatus = RequestStatus.AWAITING_RESPONSES,
) -> RequestRecord:
    """Convenience function used by an async endpoint after matching."""
    store = SqlAlchemyRequestStore(session)
    record = RequestRecord(
        request_id=request_id,
        payload=payload,
        status=status,
        created_at=datetime.now(timezone.utc),
        expires_at=payload.response_deadline,
        matches=matches,
    )
    await store.save_async(record)
    return record
