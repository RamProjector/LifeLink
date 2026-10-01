from __future__ import annotations

import logging
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any
from uuid import uuid4

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import joinedload, selectinload

from .db_models import (
    AuditEvent,
    DonorContactRequest,
    LifeLinkProfile,
    MatchStatusEnum,
    RequestStatusEnum,
)
from .db_models import (
    Donor as DonorRow,
)
from .db_models import (
    EmergencyRequest as EmergencyRequestRow,
)
from .db_models import (
    RequestMatch as RequestMatchRow,
)
from .expiry import ACTIVE_REQUEST_STATUSES, is_request_expired
from .main import (
    Donor,
    DonorMatch,
    DonorRepository,
    EmergencyRequestIn,
    MatchExplanation,
    RequestRecord,
    RequestStatus,
    RequestStore,
    require_verified_donors,
)

logger = logging.getLogger("lifelink.repositories")


def _enum_value(value):
    """Enum column values are members when loaded but plain strings on rows changed in-session."""
    return getattr(value, "value", value)


MATCHING_VERSION = "v1-explainable-weighted"
CONTACT_EMAIL_VISIBLE_STATUSES = {"contact_shared", "meeting_arranged", "fulfilled"}

# Hard ceiling for a single expiry sweep. A misconfigured
# LIFELINK_REQUEST_SWEEP_LIMIT must not be able to pull an unbounded number of
# rows into one transaction, so the store clamps whatever it is handed.
MAX_SWEEP_LIMIT = 5000


class SqlAlchemyDonorRepository(DonorRepository):
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def list_active_donors(self, excluded_user_id: str | None = None) -> list[Donor]:
        conditions = [
            DonorRow.available.is_(True),
            DonorRow.profile_visible.is_(True),
        ]
        if require_verified_donors():
            conditions.append(DonorRow.verified.is_(True))
        if excluded_user_id:
            conditions.append((DonorRow.user_id.is_(None)) | (DonorRow.user_id != excluded_user_id))
        result = await self.session.scalars(
            select(DonorRow).where(*conditions)
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
        result = await self.session.execute(
            select(EmergencyRequestRow)
            .options(joinedload(EmergencyRequestRow.facility))
            .options(joinedload(EmergencyRequestRow.matches).joinedload(RequestMatchRow.donor))
            .where(EmergencyRequestRow.idempotency_key == key)
        )
        row = result.unique().scalar_one_or_none()
        return self._to_record(row) if row else None

    async def get_by_id_async(self, request_id: str) -> RequestRecord | None:
        result = await self.session.execute(
            select(EmergencyRequestRow)
            .options(joinedload(EmergencyRequestRow.facility))
            .options(joinedload(EmergencyRequestRow.matches).joinedload(RequestMatchRow.donor))
            .where(EmergencyRequestRow.id == request_id)
        )
        row = result.unique().scalar_one_or_none()
        if row is not None:
            await self._expire_if_needed(row)
        return self._to_record(row) if row else None

    async def _expire_if_needed(self, row: EmergencyRequestRow) -> bool:
        if is_request_expired(_enum_value(row.status), row.response_deadline):
            row.status = RequestStatusEnum.EXPIRED
            await self.session.commit()
            return True
        return False

    async def expire_timed_out_requests_async(self, limit: int = 500) -> int:
        """Transition open requests at or past their deadline to ``expired``.

        Used by the background sweeper so an open request expires even when no
        read path ever touches it. Applies the same ``is_request_expired`` rule
        as ``_expire_if_needed``, selecting the earliest deadlines first.
        ``updated_at`` is set to the sweep's current UTC time. ``limit`` bounds
        the selected rows and is clamped to ``[1, MAX_SWEEP_LIMIT]``.
        Commits the session when any requests transition and returns their
        count, or zero when none transition. Database query and commit errors
        propagate to the caller.
        """
        now = datetime.now(UTC)
        limit = max(1, min(int(limit), MAX_SWEEP_LIMIT))
        open_requests = await self.session.scalars(
            select(EmergencyRequestRow)
            .where(
                EmergencyRequestRow.status.in_(
                    [RequestStatusEnum(status) for status in ACTIVE_REQUEST_STATUSES]
                ),
                EmergencyRequestRow.response_deadline <= now,
            )
            .order_by(EmergencyRequestRow.response_deadline)
            .limit(limit)
        )
        count = 0
        for row in open_requests.all():
            if is_request_expired(_enum_value(row.status), row.response_deadline, now):
                row.status = RequestStatusEnum.EXPIRED
                row.updated_at = now
                count += 1
        if count:
            await self.session.commit()
        return count

    async def list_by_requester_async(self, requester_id: str, limit: int = 50) -> list[RequestRecord]:
        result = await self.session.scalars(
            select(EmergencyRequestRow)
            .options(joinedload(EmergencyRequestRow.facility))
            .options(joinedload(EmergencyRequestRow.matches).joinedload(RequestMatchRow.donor))
            .where(EmergencyRequestRow.requester_id == requester_id)
            .order_by(EmergencyRequestRow.created_at.desc())
            .limit(limit)
        )
        rows = result.unique().all()
        # Report requests past their deadline as expired, as status polling does.
        newly_expired = False
        for row in rows:
            if is_request_expired(_enum_value(row.status), row.response_deadline):
                row.status = RequestStatusEnum.EXPIRED
                newly_expired = True
        if newly_expired:
            await self.session.commit()
        records: list[RequestRecord] = []
        for row in rows:
            try:
                records.append(self._to_record(row))
            except Exception:
                # One unreadable row must not hide the rest of the account's history.
                logger.exception("Skipping unreadable request row id=%s in history", row.id)
        return records

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
        row.status = RequestStatusEnum.MANUAL_BROADCAST
        await self.session.commit()
        refreshed = await self.get_by_id_async(request_id)
        if refreshed is None:
            raise KeyError(request_id)
        return refreshed

    async def set_cancelled_async(self, request_id: str) -> RequestRecord:
        """Mark the request cancelled and stop its donor search.

        Withdraws every still-open match and cancels every open donor contact
        request so the donor inbox and the requester's match/contact lists both
        reflect that the request is no longer active.
        """
        row = await self.session.get(EmergencyRequestRow, request_id)
        if row is None:
            raise KeyError(request_id)
        row.status = RequestStatusEnum.CANCELLED
        now = datetime.now(UTC)
        # Stop the donor search: withdraw every match the broadcast created so the
        # donor-search result set is no longer live. Without this the matches stayed
        # 'ranked'/'notified' and the search kept looking like it was still running
        # even though the request was cancelled.
        matches = await self.session.scalars(
            select(RequestMatchRow).where(
                RequestMatchRow.request_id == request_id,
                RequestMatchRow.status.notin_(
                    {MatchStatusEnum.WITHDRAWN.value, MatchStatusEnum.DECLINED.value}
                ),
            )
        )
        for match in matches.all():
            match.status = MatchStatusEnum.WITHDRAWN
            match.responded_at = match.responded_at or now
        # Close every open donor contact request so the requester's contact list and
        # the donor inbox both reflect that the request is no longer active. Without
        # this, a cancelled request kept showing pending contacts and donors kept
        # seeing it as live.
        contacts = await self.session.scalars(
            select(DonorContactRequest).where(
                DonorContactRequest.request_id == request_id,
                DonorContactRequest.status.notin_({"cancelled", "fulfilled"}),
            )
        )
        for contact in contacts.all():
            contact.status = "cancelled"
            contact.updated_at = now
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
        if _enum_value(row.status) in {RequestStatusEnum.CANCELLED.value, RequestStatusEnum.EXPIRED.value}:
            raise ValueError("A cancelled or expired request cannot be fulfilled")
        row.status = RequestStatusEnum.FULFILLED
        await self.session.commit()
        refreshed = await self.get_by_id_async(request_id)
        if refreshed is None:
            raise KeyError(request_id)
        return refreshed

    async def contact_selected_donors_async(
        self, request_id: str, donor_ids: list[str]
    ) -> tuple[RequestRecord, list[str], list[str]]:
        row_result = await self.session.execute(
            select(EmergencyRequestRow)
            .options(selectinload(EmergencyRequestRow.matches))
            .where(EmergencyRequestRow.id == request_id)
        )
        row = row_result.scalar_one_or_none()
        if row is None:
            raise KeyError(request_id)
        if await self._expire_if_needed(row):
            raise ValueError("This request has expired and cannot accept contact requests")
        if _enum_value(row.status) in {RequestStatusEnum.CANCELLED.value, RequestStatusEnum.EXPIRED.value, RequestStatusEnum.FULFILLED.value}:
            raise ValueError("This request is no longer accepting contact requests")
        allowed = {match.donor_id for match in row.matches}
        if row.requester_id in donor_ids:
            raise ValueError("You cannot select your own donor profile for this request")
        if any(donor_id not in allowed for donor_id in donor_ids):
            raise ValueError("One or more selected donors are not eligible for this request")
        now = datetime.now(UTC)
        newly_contacted: list[str] = []
        donors_to_notify: list[str] = []
        for match in row.matches:
            if match.donor_id not in donor_ids:
                continue
            contact = await self.session.scalar(
                select(DonorContactRequest).where(
                    DonorContactRequest.request_id == request_id,
                    DonorContactRequest.donor_id == match.donor_id,
                )
            )
            if contact is not None:
                continue
            prior_match_status = _enum_value(match.status)
            if prior_match_status == MatchStatusEnum.CONFIRMED.value:
                contact_status = "accepted"
                accepted_at = match.responded_at
            elif prior_match_status == MatchStatusEnum.DECLINED.value:
                contact_status = "declined"
                accepted_at = None
            else:
                contact_status = "pending"
                accepted_at = None
                match.status = MatchStatusEnum.NOTIFIED
                match.notified_at = now
                donors_to_notify.append(match.donor_id)
            newly_contacted.append(match.donor_id)
            self.session.add(DonorContactRequest(
                id=f"contact_{uuid4().hex}",
                request_id=request_id,
                donor_id=match.donor_id,
                requester_id=row.requester_id,
                status=contact_status,
                accepted_at=accepted_at,
            ))
        await self.session.commit()
        refreshed = await self.get_by_id_async(request_id)
        if refreshed is None:
            raise KeyError(request_id)
        return refreshed, newly_contacted, donors_to_notify

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
                "contact_shared_at": contact.contact_shared_at,
                "updated_at": contact.updated_at,
                "contact_email": profile.email if contact.status in CONTACT_EMAIL_VISIBLE_STATUSES and profile else None,
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
        if _enum_value(request.status) in {RequestStatusEnum.CANCELLED.value, RequestStatusEnum.FULFILLED.value}:
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
            "arrived": {"contact_shared", "cancelled"},
            "contact_shared": {"meeting_arranged", "fulfilled", "cancelled"},
            "meeting_arranged": {"fulfilled", "cancelled"},
            "fulfilled": set(),
            "cancelled": set(),
        }
        if status not in transitions.get(contact.status, set()):
            raise ValueError(f"Cannot move contact from {contact.status} to {status}")
        now = datetime.now(UTC)
        contact.status = status
        contact.updated_at = now
        if status == "contact_shared":
            contact.contact_shared_at = now
        await self.session.commit()
        await self.record_audit_async(requester_id, f"contact_{status}", request_id, donor_id)
        return {"donor_id": donor_id, "status": status, "accepted_at": contact.accepted_at}

    @staticmethod
    def _to_record(row: EmergencyRequestRow) -> RequestRecord:
        """Build a `RequestRecord` from a DB row, excluding withdrawn matches.

        Withdrawn matches belong to a stopped donor search (e.g. a cancelled
        request), so they are left out of the returned matches list.
        """
        payload = EmergencyRequestIn.model_validate({
            "requester_id": row.requester_id,
            "blood_type": _enum_value(row.blood_type),
            "units": row.units,
            "urgency": _enum_value(row.urgency),
            "response_deadline": row.response_deadline,
            "location": {
                "facility_id": row.facility_id,
                "facility_name": row.facility.name if row.facility else "Requester location",
                "area": row.facility.area if row.facility else "Approximate area",
                "latitude": float(row.requester_latitude),
                "longitude": float(row.requester_longitude),
                "precision_meters": row.location_precision_meters,
                "verified": row.facility.verified if row.facility else False,
            },
            "contact_method": _enum_value(row.contact_method),
            "note": row.note,
            "genuine_request_confirmed": row.genuine_request_confirmed,
            "sharing_consent_confirmed": row.sharing_consent_confirmed,
            "ai_matching_enabled": True,
            "idempotency_key": row.idempotency_key,
        }, context={"stored": True})
        matches = [
            DonorMatch(
                donor_id=match.donor_id,
                display_name=match.donor.display_name,
                blood_type=_enum_value(match.donor.blood_type),
                distance_km=float(match.distance_km),
                estimated_travel_minutes=match.estimated_travel_minutes,
                score=float(match.score),
                explanation=MatchExplanation.model_validate(match.explanation),
            )
            for match in sorted(row.matches, key=lambda item: item.rank)
            if match.donor is not None
            # A withdrawn match means the donor search for this request was stopped
            # (e.g. the request was cancelled), so it must not appear as a live match.
            and _enum_value(match.status) != MatchStatusEnum.WITHDRAWN.value
        ]
        return RequestRecord(
            request_id=row.id,
            payload=payload,
            status=RequestStatus(_enum_value(row.status)),
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
        created_at=datetime.now(UTC),
        expires_at=payload.response_deadline,
        matches=matches,
    )
    await store.save_async(record)
    return record
