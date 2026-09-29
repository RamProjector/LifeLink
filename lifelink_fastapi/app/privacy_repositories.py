"""Persistence and policy for the confirmed LifeLink privacy behavior.

The store is the single place that decides whether exact donor coordinates may
be disclosed. Every read path re-checks that an active, unexpired location
share exists for the exact (request, donor) pair, so a stale client cannot keep
showing a pin after the donor revokes it or the request ends.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from uuid import uuid4

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .db_models import (
    AuditEvent,
    ContactShare,
    Conversation,
    Donor as DonorRow,
    DonorLocationShare,
    EmergencyRequest as RequestRow,
    Message,
    RequestMatch,
)
from .expiry import ACTIVE_REQUEST_STATUSES, is_request_expired
from .main import donor_location_max_age_minutes
from .privacy_api import (
    ContactShareIn,
    DonorMapEntry,
    DonorMapVisibilityIn,
    MessageIn,
)

# Exact location is disclosed for a bounded window even while the request is
# open, so a donor who stops refreshing is not exposed indefinitely.
DEFAULT_LOCATION_SHARE_MINUTES = 120
# The map never shows a finer grid than this, regardless of device accuracy.
MAP_GRID_METERS = 1000


def _enum_value(value):
    return getattr(value, "value", value)


def _as_utc(value: datetime) -> datetime:
    return value if value.tzinfo else value.replace(tzinfo=timezone.utc)


def _coarsen(value: float) -> float:
    """Snap a coordinate to a ~1 km grid so the map cannot pinpoint a donor."""
    return round(value, 2)


class SqlAlchemyPrivacyStore:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    # ------------------------------------------------------------------ map
    async def donor_map_entries(self) -> list[DonorMapEntry]:
        """Approximate donor areas for the map.

        Only donors who explicitly opted in (``map_visible``) and are currently
        available and profile-visible appear. Coordinates are coarsened and no
        donor identity is returned.
        """
        now = datetime.now(timezone.utc)
        max_age = donor_location_max_age_minutes()
        rows = await self.session.scalars(
            select(DonorRow).where(
                DonorRow.map_visible.is_(True),
                DonorRow.available.is_(True),
                DonorRow.profile_visible.is_(True),
            )
        )
        entries: list[DonorMapEntry] = []
        for row in rows.all():
            freshness = _as_utc(row.availability_updated_at)
            age_minutes = max(0, int((now - freshness).total_seconds() // 60))
            entries.append(
                DonorMapEntry(
                    area_label="Approximate donor area",
                    latitude=_coarsen(float(row.latitude)),
                    longitude=_coarsen(float(row.longitude)),
                    radius_meters=max(MAP_GRID_METERS, int(row.last_location_precision_meters or MAP_GRID_METERS)),
                    blood_type=_enum_value(row.blood_type),
                    availability="available",
                    freshness_at=freshness,
                    freshness_age_minutes=age_minutes,
                    is_stale=age_minutes > max_age,
                )
            )
        return entries

    async def set_map_visibility(self, donor_id: str, payload: DonorMapVisibilityIn) -> DonorRow:
        row = await self._donor_row(donor_id)
        if row is None:
            raise KeyError(donor_id)
        now = datetime.now(timezone.utc)
        row.map_visible = payload.map_visible
        row.exact_location_sharing_enabled = payload.exact_location_sharing_enabled
        row.map_visibility_updated_at = now
        if not payload.map_visible:
            # Hiding from the map also withdraws any live exact-location share.
            await self._revoke_all_shares_for_donor(row.id, now)
        await self.session.commit()
        return row

    # -------------------------------------------------------- location share
    async def ensure_location_share(
        self, request_id: str, donor_id: str, requester_id: str, expires_at: datetime
    ) -> DonorLocationShare:
        """Create or refresh the matched-requester-only exact location share.

        Called only after the request has matched the donor. The share expires
        with the request deadline, or sooner if the donor disabled sharing.
        """
        now = datetime.now(timezone.utc)
        share = await self.session.scalar(
            select(DonorLocationShare).where(
                DonorLocationShare.request_id == request_id,
                DonorLocationShare.donor_id == donor_id,
            )
        )
        window_end = min(_as_utc(expires_at), now + timedelta(minutes=DEFAULT_LOCATION_SHARE_MINUTES))
        if share is None:
            share = DonorLocationShare(
                id=f"locshare_{uuid4().hex}",
                request_id=request_id,
                donor_id=donor_id,
                requester_id=requester_id,
                status="active",
                shared_at=now,
                expires_at=window_end,
            )
            self.session.add(share)
        else:
            share.requester_id = requester_id
            share.status = "active"
            share.revoked_at = None
            share.shared_at = now
            share.expires_at = window_end
            share.updated_at = now
        await self.session.commit()
        return share

    async def revoke_location_share(self, request_id: str, donor_id: str) -> DonorLocationShare:
        share = await self.session.scalar(
            select(DonorLocationShare).where(
                DonorLocationShare.request_id == request_id,
                DonorLocationShare.donor_id == donor_id,
            )
        )
        if share is None:
            raise KeyError(request_id)
        now = datetime.now(timezone.utc)
        share.status = "revoked"
        share.revoked_at = now
        share.updated_at = now
        await self.session.commit()
        return share

    async def exact_location_for_requester(
        self, request_id: str, donor_id: str, requester_id: str
    ) -> dict:
        """Return exact coordinates only while a live share authorizes them."""
        request = await self.session.get(RequestRow, request_id)
        if request is None:
            raise KeyError(request_id)
        if request.requester_id != requester_id:
            raise PermissionError("Not the requester for this request")
        donor = await self._donor_row(donor_id)
        if donor is None:
            raise KeyError(donor_id)
        share = await self.session.scalar(
            select(DonorLocationShare).where(
                DonorLocationShare.request_id == request_id,
                DonorLocationShare.donor_id == donor.id,
            )
        )
        now = datetime.now(timezone.utc)
        reason = None
        live = False
        if share is None:
            reason = "No location share exists for this request and donor"
        elif share.status != "active":
            reason = "The donor revoked exact location sharing"
        elif _as_utc(share.expires_at) <= now:
            reason = "Exact location sharing has expired"
        elif is_request_expired(_enum_value(request.status), request.response_deadline, now):
            reason = "The request has expired"
        elif _enum_value(request.status) not in ACTIVE_REQUEST_STATUSES:
            reason = "The request is no longer active"
        elif not donor.exact_location_sharing_enabled:
            reason = "The donor disabled exact location sharing"
        else:
            live = True

        if not live:
            if share is not None and share.status == "active" and _as_utc(share.expires_at) <= now:
                share.status = "expired"
                share.updated_at = now
                await self.session.commit()
            return {
                "request_id": request_id,
                "donor_id": donor.id,
                "shared": False,
                "reason": reason,
            }
        return {
            "request_id": request_id,
            "donor_id": donor.id,
            "shared": True,
            "latitude": float(donor.latitude),
            "longitude": float(donor.longitude),
            "precision_meters": int(donor.last_location_precision_meters or 500),
            "freshness_at": _as_utc(donor.availability_updated_at),
            "expires_at": _as_utc(share.expires_at),
        }

    async def expire_shares_for_request(self, request_id: str) -> int:
        """Expire every live share for a request that has ended."""
        now = datetime.now(timezone.utc)
        shares = await self.session.scalars(
            select(DonorLocationShare).where(
                DonorLocationShare.request_id == request_id,
                DonorLocationShare.status == "active",
            )
        )
        count = 0
        for share in shares.all():
            share.status = "expired"
            share.updated_at = now
            count += 1
        if count:
            await self.session.commit()
        return count

    # --------------------------------------------------------- conversations
    async def get_or_create_conversation(
        self, request_id: str, donor_id: str, requester_id: str
    ) -> Conversation:
        donor = await self._donor_row(donor_id)
        if donor is None:
            raise KeyError(donor_id)
        conversation = await self.session.scalar(
            select(Conversation).where(
                Conversation.request_id == request_id,
                Conversation.donor_id == donor.id,
            )
        )
        if conversation is None:
            conversation = Conversation(
                id=f"conv_{uuid4().hex}",
                request_id=request_id,
                donor_id=donor.id,
                requester_id=requester_id,
            )
            self.session.add(conversation)
            await self.session.commit()
        return conversation

    async def conversation_for_participant(self, conversation_id: str, user_id: str) -> Conversation:
        conversation = await self.session.get(Conversation, conversation_id)
        if conversation is None:
            raise KeyError(conversation_id)
        if user_id not in {conversation.requester_id, conversation.donor_id}:
            raise PermissionError("Not a participant in this conversation")
        return conversation

    async def list_conversations_for_user(self, user_id: str) -> list[Conversation]:
        rows = await self.session.scalars(
            select(Conversation)
            .where((Conversation.requester_id == user_id) | (Conversation.donor_id == user_id))
            .order_by(Conversation.updated_at.desc())
        )
        return list(rows.all())

    async def list_messages(self, conversation_id: str) -> list[Message]:
        rows = await self.session.scalars(
            select(Message)
            .where(Message.conversation_id == conversation_id)
            .order_by(Message.created_at.asc())
        )
        return list(rows.all())

    async def add_message(self, conversation: Conversation, sender_id: str, payload: MessageIn) -> Message:
        now = datetime.now(timezone.utc)
        message = Message(
            id=f"msg_{uuid4().hex}",
            conversation_id=conversation.id,
            sender_id=sender_id,
            body=payload.body.strip(),
        )
        self.session.add(message)
        conversation.last_message_at = now
        conversation.updated_at = now
        await self.session.commit()
        return message

    # -------------------------------------------------------- contact shares
    async def add_contact_share(
        self, conversation: Conversation, shared_by: str, payload: ContactShareIn
    ) -> ContactShare:
        share = ContactShare(
            id=f"share_{uuid4().hex}",
            conversation_id=conversation.id,
            request_id=conversation.request_id,
            donor_id=conversation.donor_id,
            requester_id=conversation.requester_id,
            shared_by=shared_by,
            field=payload.field,
            value=payload.value.strip(),
        )
        self.session.add(share)
        await self.session.commit()
        return share

    async def list_contact_shares(self, conversation_id: str) -> list[ContactShare]:
        rows = await self.session.scalars(
            select(ContactShare)
            .where(ContactShare.conversation_id == conversation_id)
            .order_by(ContactShare.created_at.asc())
        )
        return list(rows.all())

    # ------------------------------------------------------------- internals
    async def record_audit_async(
        self,
        actor_id: str,
        action: str,
        request_id: str | None = None,
        donor_id: str | None = None,
        metadata: dict | None = None,
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

    async def donor_matched_to_request(self, request_id: str, donor_row_id: str) -> bool:
        match = await self.session.scalar(
            select(RequestMatch).where(
                RequestMatch.request_id == request_id,
                RequestMatch.donor_id == donor_row_id,
            )
        )
        return match is not None

    async def _donor_row(self, donor_id: str) -> DonorRow | None:
        row = await self.session.get(DonorRow, donor_id)
        if row is not None:
            return row
        return await self.session.scalar(
            select(DonorRow).where(DonorRow.user_id == donor_id).limit(1)
        )

    async def _revoke_all_shares_for_donor(self, donor_row_id: str, now: datetime) -> None:
        shares = await self.session.scalars(
            select(DonorLocationShare).where(
                DonorLocationShare.donor_id == donor_row_id,
                DonorLocationShare.status == "active",
            )
        )
        for share in shares.all():
            share.status = "revoked"
            share.revoked_at = now
            share.updated_at = now
