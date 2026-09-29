"""Persistence and policy for the confirmed LifeLink privacy behavior.

The store is the single place that decides whether exact donor coordinates may
be disclosed. Every read path re-checks that an active, unexpired location
share exists for the exact (request, donor) pair, so a stale client cannot keep
showing a pin after the donor revokes it or the request ends.
"""
from __future__ import annotations

import math
import re
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from .db_models import (
    AuditEvent,
    ContactShare,
    Conversation,
    ConversationBlock,
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

# Contact details are only accepted in a plausible shape, so a typo cannot be
# broadcast to the other participant as a "shared" phone/email.
_EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
_PHONE_RE = re.compile(r"^\+?[0-9][0-9\s().-]{5,}$")


def _enum_value(value):
    """Return the raw value of an enum member, or the value itself if not an enum."""
    return getattr(value, "value", value)


def _as_utc(value: datetime) -> datetime:
    """Return ``value`` as a timezone-aware UTC datetime, assuming UTC if naive."""
    return value if value.tzinfo else value.replace(tzinfo=timezone.utc)


def _coarsen(value: float) -> float:
    """Snap a coordinate to a ~1 km grid so the map cannot pinpoint a donor.

    Rounding to two decimals is not a real grid: it can move a point by up to
    ~1.1 km and, near a cell boundary, two donors a few metres apart can land on
    different cells. Snapping to a fixed 0.01-degree lattice keeps every donor
    inside the same ~1 km cell together and never reveals sub-cell precision.
    """
    return math.floor(value * 100) / 100


class SqlAlchemyPrivacyStore:
    def __init__(self, session: AsyncSession) -> None:
        """Bind the store to an open async SQLAlchemy session."""
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
        freshness = func.coalesce(DonorRow.availability_updated_at, DonorRow.created_at)
        rows = await self.session.scalars(
            select(DonorRow).where(
                DonorRow.map_visible.is_(True),
                DonorRow.available.is_(True),
                DonorRow.profile_visible.is_(True),
                # A stale snapshot is not a current location: never show it.
                freshness >= now - timedelta(minutes=max_age),
            )
        )
        entries: list[DonorMapEntry] = []
        for row in rows.all():
            freshness_at = _as_utc(row.availability_updated_at or row.created_at)
            age_minutes = max(0, int((now - freshness_at).total_seconds() // 60))
            entries.append(
                DonorMapEntry(
                    area_label="Approximate donor area",
                    latitude=_coarsen(float(row.latitude)),
                    longitude=_coarsen(float(row.longitude)),
                    radius_meters=max(MAP_GRID_METERS, int(row.last_location_precision_meters or MAP_GRID_METERS)),
                    blood_type=_enum_value(row.blood_type),
                    availability="available",
                    freshness_at=freshness_at,
                    freshness_age_minutes=age_minutes,
                    is_stale=False,
                )
            )
        return entries

    async def set_map_visibility(self, donor_id: str, payload: DonorMapVisibilityIn) -> DonorRow:
        """Persist a donor's map opt-in and revoke live shares when hiding.

        Turning the map off (or disabling exact-location sharing) immediately
        revokes every active location share for the donor, so hiding is real
        rather than cosmetic.
        """
        row = await self._donor_row(donor_id)
        if row is None:
            raise KeyError(donor_id)
        now = datetime.now(timezone.utc)
        row.map_visible = payload.map_visible
        row.exact_location_sharing_enabled = payload.exact_location_sharing_enabled
        row.map_visibility_updated_at = now
        if not payload.map_visible or not payload.exact_location_sharing_enabled:
            # Hiding from the map, or turning off exact-location sharing, must
            # withdraw any live exact-location share immediately.
            await self._revoke_all_shares_for_donor(row.id, now)
        await self.session.commit()
        await self.record_audit_async(
            row.user_id or row.id,
            "donor_map_visibility_updated",
            donor_id=row.id,
            metadata={
                "map_visible": payload.map_visible,
                "exact_location_sharing_enabled": payload.exact_location_sharing_enabled,
            },
        )
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
        """Mark a donor's location share for a request as revoked.

        Raises ``KeyError`` when no share exists for the (request, donor) pair.
        """
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
        freshness_at = _as_utc(donor.availability_updated_at or donor.created_at)
        freshness_age_minutes = max(0.0, (now - freshness_at).total_seconds() / 60)
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
        elif not await self.donor_matched_to_request(request_id, donor.id):
            reason = "The donor is not matched to this request"
        elif not donor.exact_location_sharing_enabled:
            reason = "The donor disabled exact location sharing"
        elif freshness_age_minutes > donor_location_max_age_minutes():
            reason = "The donor's location is no longer fresh"
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
            "freshness_at": freshness_at,
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
            await self.record_audit_async(
                "system",
                "location_shares_expired",
                request_id=request_id,
                metadata={"expired_count": count},
            )
        return count

    async def expire_stale_shares(self) -> int:
        """Expire every live share whose window or request deadline has passed.

        Called by the background sweeper so a request that simply times out
        (without an explicit cancel/fulfil) still closes its exact-location
        shares. Returns the number of shares closed.
        """
        now = datetime.now(timezone.utc)
        shares = await self.session.scalars(
            select(DonorLocationShare).where(DonorLocationShare.status == "active")
        )
        count = 0
        for share in shares.all():
            request = await self.session.get(RequestRow, share.request_id)
            request_ended = request is None or (
                _enum_value(request.status) not in ACTIVE_REQUEST_STATUSES
                or is_request_expired(_enum_value(request.status), request.response_deadline, now)
            )
            if _as_utc(share.expires_at) <= now or request_ended:
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
        """Return the conversation for a matched (request, donor), creating it once.

        Raises ``KeyError`` when the donor does not exist and ``PermissionError``
        when the donor is not matched to the request.
        """
        donor = await self._donor_row(donor_id)
        if donor is None:
            raise KeyError(donor_id)
        if not await self.donor_matched_to_request(request_id, donor.id):
            raise PermissionError("Donor is not matched to this request")
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
        """Return the conversation if ``user_id`` is one of its two participants.

        ``conversation.donor_id`` is the donor *row* id, which may differ from the
        donor's owning user id, so the donor is accepted under either identity.
        Raises ``KeyError`` when the conversation does not exist and
        ``PermissionError`` when the caller is not a participant.
        """
        conversation = await self.session.get(Conversation, conversation_id)
        if conversation is None:
            raise KeyError(conversation_id)
        donor = await self.session.get(DonorRow, conversation.donor_id)
        donor_user_id = donor.user_id if donor and donor.user_id else conversation.donor_id
        if user_id not in {conversation.requester_id, conversation.donor_id, donor_user_id}:
            raise PermissionError("Not a participant in this conversation")
        return conversation

    async def list_conversations_for_user(self, user_id: str) -> list[Conversation]:
        """List a user's conversations, newest activity first."""
        rows = await self.session.scalars(
            select(Conversation)
            .where((Conversation.requester_id == user_id) | (Conversation.donor_id == user_id))
            .order_by(Conversation.updated_at.desc())
        )
        return list(rows.all())

    async def list_messages(self, conversation_id: str) -> list[Message]:
        """List a conversation's messages in chronological order."""
        rows = await self.session.scalars(
            select(Message)
            .where(Message.conversation_id == conversation_id)
            .order_by(Message.created_at.asc())
        )
        return list(rows.all())

    async def add_message(self, conversation: Conversation, sender_id: str, payload: MessageIn) -> Message:
        """Persist a message and bump the conversation's activity timestamps.

        Raises ``ValueError`` when the trimmed body is empty.
        """
        body = payload.body.strip()
        if not body:
            raise ValueError("Message body cannot be empty")
        now = datetime.now(timezone.utc)
        message = Message(
            id=f"msg_{uuid4().hex}",
            conversation_id=conversation.id,
            sender_id=sender_id,
            body=body,
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
        """Record an explicit phone/email disclosure inside a conversation.

        Raises ``ValueError`` when the value is not a plausible email or phone.
        """
        value = payload.value.strip()
        if payload.field == "email" and not _EMAIL_RE.match(value):
            raise ValueError("Enter a valid email address")
        if payload.field == "phone" and not _PHONE_RE.match(value):
            raise ValueError("Enter a valid phone number")
        share = ContactShare(
            id=f"share_{uuid4().hex}",
            conversation_id=conversation.id,
            request_id=conversation.request_id,
            donor_id=conversation.donor_id,
            requester_id=conversation.requester_id,
            shared_by=shared_by,
            field=payload.field,
            value=value,
        )
        self.session.add(share)
        await self.session.commit()
        return share

    async def list_contact_shares(self, conversation_id: str) -> list[ContactShare]:
        """List a conversation's contact disclosures in chronological order."""
        rows = await self.session.scalars(
            select(ContactShare)
            .where(ContactShare.conversation_id == conversation_id)
            .order_by(ContactShare.created_at.asc())
        )
        return list(rows.all())

    # ---------------------------------------------------------------- blocks
    async def other_participant_user_id(self, conversation: Conversation, user_id: str) -> str | None:
        """The other participant's user id, accepting either donor identity.

        ``conversation.donor_id`` is the donor row id, which may differ from the
        owning user id, so both are accepted as the donor's identity.
        """
        donor = await self.session.get(DonorRow, conversation.donor_id)
        donor_user_id = donor.user_id if donor and donor.user_id else conversation.donor_id
        if user_id == conversation.requester_id:
            return donor_user_id
        if user_id in {donor_user_id, conversation.donor_id}:
            return conversation.requester_id
        return None

    async def block_participant(
        self, conversation: Conversation, blocker_id: str, blocked_id: str, reason: str = ""
    ) -> ConversationBlock:
        """Persist an enforceable block. Idempotent for the same (blocker, blocked) pair."""
        existing = await self.session.scalar(
            select(ConversationBlock).where(
                ConversationBlock.conversation_id == conversation.id,
                ConversationBlock.blocker_id == blocker_id,
                ConversationBlock.blocked_id == blocked_id,
            )
        )
        if existing is not None:
            return existing
        block = ConversationBlock(
            id=f"block_{uuid4().hex}",
            conversation_id=conversation.id,
            request_id=conversation.request_id,
            blocker_id=blocker_id,
            blocked_id=blocked_id,
            reason=reason,
        )
        self.session.add(block)
        await self.session.commit()
        return block

    async def is_blocked(self, conversation: Conversation, sender_id: str) -> bool:
        """True when ``sender_id`` has been blocked by the other participant.

        A block is one-way: only the *blocked* participant is silenced. The
        sender must therefore be the blocked party and must not be the blocker,
        otherwise a requester who blocks a donor would also lock themselves out
        of messaging and contact sharing. The donor may be identified by either
        the donor row id or the owning user id, so every identity of the sender
        is checked against the stored block.
        """
        donor = await self.session.get(DonorRow, conversation.donor_id)
        donor_user_id = donor.user_id if donor and donor.user_id else conversation.donor_id
        # Resolve the sender's own identities. A block silences the sender only
        # when the sender is the *blocked* party, so the check must be scoped to
        # the sender's identities rather than to both participants.
        if sender_id == conversation.requester_id:
            sender_identities = {conversation.requester_id}
        elif sender_id in {conversation.donor_id, donor_user_id}:
            sender_identities = {conversation.donor_id, donor_user_id}
        else:
            return False
        block = await self.session.scalar(
            select(ConversationBlock)
            .where(
                ConversationBlock.conversation_id == conversation.id,
                ConversationBlock.blocked_id.in_(sender_identities),
            )
            .limit(1)
        )
        return block is not None

    # ------------------------------------------------------------- internals
    async def record_audit_async(
        self,
        actor_id: str,
        action: str,
        request_id: str | None = None,
        donor_id: str | None = None,
        metadata: dict | None = None,
    ) -> None:
        """Append an audit event and commit it immediately."""
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
        """True when the donor row is one of the request's matches."""
        match = await self.session.scalar(
            select(RequestMatch).where(
                RequestMatch.request_id == request_id,
                RequestMatch.donor_id == donor_row_id,
            )
        )
        return match is not None

    async def donor_row(self, donor_id: str) -> DonorRow | None:
        """Public resolver for a donor row by primary key or owning user id."""
        return await self._donor_row(donor_id)

    async def _donor_row(self, donor_id: str) -> DonorRow | None:
        """Resolve a donor row by primary key, falling back to the owning user id."""
        row = await self.session.get(DonorRow, donor_id)
        if row is not None:
            return row
        return await self.session.scalar(
            select(DonorRow).where(DonorRow.user_id == donor_id).limit(1)
        )

    async def _revoke_all_shares_for_donor(self, donor_row_id: str, now: datetime) -> None:
        """Revoke every active location share for a donor (used when hiding)."""
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
