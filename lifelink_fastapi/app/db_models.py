from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from enum import Enum
from typing import Any

from sqlalchemy import (
    Boolean,
    DateTime,
    Enum as SqlEnum,
    ForeignKey,
    Index,
    Integer,
    JSON,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship
from sqlalchemy.types import UserDefinedType


class Base(DeclarativeBase):
    pass


class BloodTypeEnum(str, Enum):
    A_POS = "A+"
    A_NEG = "A-"
    B_POS = "B+"
    B_NEG = "B-"
    AB_POS = "AB+"
    AB_NEG = "AB-"
    O_POS = "O+"
    O_NEG = "O-"
    UNKNOWN = "UNKNOWN"


class UrgencyEnum(str, Enum):
    CRITICAL = "critical"
    URGENT = "urgent"
    PLANNED = "planned"


class ContactMethodEnum(str, Enum):
    IN_APP = "in_app"
    PHONE = "phone"


class RequestStatusEnum(str, Enum):
    DRAFT = "draft"
    MATCHING = "matching"
    AWAITING_RESPONSES = "awaiting_responses"
    PARTIALLY_FULFILLED = "partially_fulfilled"
    FULFILLED = "fulfilled"
    EXPIRED = "expired"
    CANCELLED = "cancelled"
    MANUAL_BROADCAST = "manual_broadcast"


class MatchStatusEnum(str, Enum):
    RANKED = "ranked"
    NOTIFIED = "notified"
    CONSIDERING = "considering"
    CONFIRMED = "confirmed"
    DECLINED = "declined"
    WITHDRAWN = "withdrawn"


class LifeLinkRoleEnum(str, Enum):
    REQUESTER = "requester"
    DONOR = "donor"


def enum_values(enum_cls: type[Enum]) -> list[str]:
    """Persist the SQL migration values, not Python enum member names."""
    return [member.value for member in enum_cls]


class GeographyPoint(UserDefinedType):
    """PostGIS geography(Point, 4326) type.

    PostGIS is recommended for production distance queries. The application can
    still use latitude/longitude values for API serialization and fallback math.
    """

    cache_ok = True

    def get_col_spec(self, **kw: Any) -> str:
        return "geography(POINT,4326)"


class Facility(Base):
    __tablename__ = "facilities"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    area: Mapped[str] = mapped_column(String(120), nullable=False)
    latitude: Mapped[Decimal] = mapped_column(Numeric(9, 6), nullable=False)
    longitude: Mapped[Decimal] = mapped_column(Numeric(9, 6), nullable=False)
    location: Mapped[Any | None] = mapped_column(GeographyPoint(), nullable=True)
    verified: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    requests: Mapped[list[EmergencyRequest]] = relationship(back_populates="facility")


class Donor(Base):
    __tablename__ = "donors"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    user_id: Mapped[str | None] = mapped_column(String(128), nullable=True, unique=True)
    display_name: Mapped[str] = mapped_column(String(160), nullable=False)
    blood_type: Mapped[BloodTypeEnum] = mapped_column(
        SqlEnum(BloodTypeEnum, name="blood_type_enum", native_enum=True, values_callable=enum_values), nullable=False
    )
    latitude: Mapped[Decimal] = mapped_column(Numeric(9, 6), nullable=False)
    longitude: Mapped[Decimal] = mapped_column(Numeric(9, 6), nullable=False)
    location: Mapped[Any | None] = mapped_column(GeographyPoint(), nullable=True)
    available: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    availability_updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    verified: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    service_radius_km: Mapped[Decimal] = mapped_column(Numeric(6, 2), nullable=False, default=15)
    last_location_precision_meters: Mapped[int] = mapped_column(Integer, nullable=False, default=500)
    estimated_response_probability: Mapped[Decimal] = mapped_column(
        Numeric(4, 3), nullable=False, default=Decimal("0.50")
    )
    donor_note: Mapped[str] = mapped_column(Text, nullable=False, default="")
    preferred_contact_method: Mapped[str] = mapped_column(String(32), nullable=False, default="in_app")
    pause_reason: Mapped[str | None] = mapped_column(String(240), nullable=True)
    profile_visible: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    # Donor map visibility is opt-in and defaults to hidden. The map only ever
    # exposes an approximate area plus a freshness timestamp, never exact pins.
    map_visible: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    map_visibility_updated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # When enabled, a matched requester may see this donor's exact location while
    # an active, unexpired donor_location_shares row exists for their request.
    exact_location_sharing_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    matches: Mapped[list[RequestMatch]] = relationship(back_populates="donor")

    __table_args__ = (
        Index("ix_donors_active_blood_type", "available", "verified", "blood_type"),
        Index("ix_donors_availability_updated_at", "availability_updated_at"),
    )


class LifeLinkProfile(Base):
    __tablename__ = "lifelink_profiles"

    user_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    email: Mapped[str] = mapped_column(String(320), nullable=False, default="")
    role: Mapped[LifeLinkRoleEnum] = mapped_column(
        SqlEnum(LifeLinkRoleEnum, name="lifelink_role_enum", native_enum=True, values_callable=enum_values), nullable=False
    )
    can_request: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    can_donate: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    display_name: Mapped[str | None] = mapped_column(String(160), nullable=True)
    fcm_token: Mapped[str | None] = mapped_column(String(4096), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )


class EmergencyRequest(Base):
    __tablename__ = "emergency_requests"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    requester_id: Mapped[str] = mapped_column(String(128), nullable=False, index=True)
    facility_id: Mapped[str | None] = mapped_column(ForeignKey("facilities.id"), nullable=True)
    requester_latitude: Mapped[Decimal] = mapped_column(Numeric(9, 6), nullable=False)
    requester_longitude: Mapped[Decimal] = mapped_column(Numeric(9, 6), nullable=False)
    location_precision_meters: Mapped[int] = mapped_column(Integer, nullable=False, default=100)
    blood_type: Mapped[BloodTypeEnum] = mapped_column(
        SqlEnum(BloodTypeEnum, name="blood_type_enum", native_enum=True, values_callable=enum_values), nullable=False
    )
    units: Mapped[int] = mapped_column(Integer, nullable=False)
    urgency: Mapped[UrgencyEnum] = mapped_column(
        SqlEnum(UrgencyEnum, name="urgency_enum", native_enum=True, values_callable=enum_values), nullable=False
    )
    response_deadline: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    contact_method: Mapped[ContactMethodEnum] = mapped_column(
        SqlEnum(ContactMethodEnum, name="contact_method_enum", native_enum=True, values_callable=enum_values), nullable=False
    )
    note: Mapped[str] = mapped_column(Text, nullable=False, default="")
    genuine_request_confirmed: Mapped[bool] = mapped_column(Boolean, nullable=False)
    sharing_consent_confirmed: Mapped[bool] = mapped_column(Boolean, nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(128), nullable=False, unique=True)
    status: Mapped[RequestStatusEnum] = mapped_column(
        SqlEnum(RequestStatusEnum, name="request_status_enum", native_enum=True, values_callable=enum_values), nullable=False
    )
    matching_version: Mapped[str] = mapped_column(String(64), nullable=False, default="v1-explainable-weighted")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    facility: Mapped[Facility | None] = relationship(back_populates="requests")
    matches: Mapped[list[RequestMatch]] = relationship(
        back_populates="request", cascade="all, delete-orphan"
    )

    __table_args__ = (
        Index("ix_requests_status_deadline", "status", "response_deadline"),
        Index("ix_requests_requester_created", "requester_id", "created_at"),
    )


class RequestMatch(Base):
    __tablename__ = "request_matches"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    request_id: Mapped[str] = mapped_column(ForeignKey("emergency_requests.id", ondelete="CASCADE"), nullable=False)
    donor_id: Mapped[str] = mapped_column(ForeignKey("donors.id"), nullable=False)
    rank: Mapped[int] = mapped_column(Integer, nullable=False)
    score: Mapped[Decimal] = mapped_column(Numeric(7, 2), nullable=False)
    distance_km: Mapped[Decimal] = mapped_column(Numeric(8, 2), nullable=False)
    estimated_travel_minutes: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[MatchStatusEnum] = mapped_column(
        SqlEnum(MatchStatusEnum, name="match_status_enum", native_enum=True, values_callable=enum_values), nullable=False
    )
    explanation: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False, default=dict)
    notified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    responded_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    request: Mapped[EmergencyRequest] = relationship(back_populates="matches")
    donor: Mapped[Donor] = relationship(back_populates="matches")

    __table_args__ = (
        UniqueConstraint("request_id", "donor_id", name="uq_request_matches_request_donor"),
        Index("ix_request_matches_request_rank", "request_id", "rank"),
        Index("ix_request_matches_donor_status", "donor_id", "status"),
    )


class DonorContactRequest(Base):
    __tablename__ = "donor_contact_requests"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    request_id: Mapped[str] = mapped_column(ForeignKey("emergency_requests.id", ondelete="CASCADE"), nullable=False)
    donor_id: Mapped[str] = mapped_column(ForeignKey("donors.id", ondelete="CASCADE"), nullable=False)
    requester_id: Mapped[str] = mapped_column(String(128), nullable=False)
    status: Mapped[str] = mapped_column(String(24), nullable=False, default="pending")
    contact_shared_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    accepted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    __table_args__ = (
        UniqueConstraint("request_id", "donor_id", name="uq_donor_contact_requests_request_donor"),
        Index("ix_donor_contact_requests_donor_status", "donor_id", "status"),
        Index("ix_donor_contact_requests_request_status", "request_id", "status"),
    )


class AuditEvent(Base):
    __tablename__ = "audit_events"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    actor_id: Mapped[str] = mapped_column(String(128), nullable=False, index=True)
    action: Mapped[str] = mapped_column(String(64), nullable=False)
    request_id: Mapped[str | None] = mapped_column(String(128), nullable=True, index=True)
    donor_id: Mapped[str | None] = mapped_column(String(128), nullable=True, index=True)
    event_metadata: Mapped[dict[str, Any]] = mapped_column("metadata", JSON, nullable=False, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)


class DonorLocationShare(Base):
    """Matched-requester-only exact location disclosure for one (request, donor).

    A row is only created after the requester's active request has matched the
    donor. It expires with the request and can be revoked by the donor at any
    time. The API never returns exact coordinates without an active row.
    """

    __tablename__ = "donor_location_shares"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    request_id: Mapped[str] = mapped_column(ForeignKey("emergency_requests.id", ondelete="CASCADE"), nullable=False)
    donor_id: Mapped[str] = mapped_column(ForeignKey("donors.id", ondelete="CASCADE"), nullable=False)
    requester_id: Mapped[str] = mapped_column(String(128), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="active")
    shared_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    __table_args__ = (
        UniqueConstraint("request_id", "donor_id", name="uq_donor_location_shares_request_donor"),
        Index("ix_donor_location_shares_request_donor", "request_id", "donor_id", "status"),
        Index("ix_donor_location_shares_requester", "requester_id", "status"),
    )


class Conversation(Base):
    """In-app conversation between the requester and donor of one request."""

    __tablename__ = "conversations"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    request_id: Mapped[str] = mapped_column(ForeignKey("emergency_requests.id", ondelete="CASCADE"), nullable=False)
    donor_id: Mapped[str] = mapped_column(ForeignKey("donors.id", ondelete="CASCADE"), nullable=False)
    requester_id: Mapped[str] = mapped_column(String(128), nullable=False)
    last_message_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    __table_args__ = (
        UniqueConstraint("request_id", "donor_id", name="uq_conversations_request_donor"),
        Index("ix_conversations_requester", "requester_id"),
        Index("ix_conversations_donor", "donor_id"),
    )


class Message(Base):
    """Server-side message. Readable only by the two conversation participants."""

    __tablename__ = "messages"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    conversation_id: Mapped[str] = mapped_column(ForeignKey("conversations.id", ondelete="CASCADE"), nullable=False)
    sender_id: Mapped[str] = mapped_column(String(128), nullable=False)
    body: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)

    __table_args__ = (
        Index("ix_messages_conversation_created", "conversation_id", "created_at"),
    )


class ContactShare(Base):
    """Explicit, audited phone/email disclosure inside a conversation."""

    __tablename__ = "contact_shares"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    conversation_id: Mapped[str] = mapped_column(ForeignKey("conversations.id", ondelete="CASCADE"), nullable=False)
    request_id: Mapped[str] = mapped_column(String(128), nullable=False)
    donor_id: Mapped[str] = mapped_column(String(128), nullable=False)
    requester_id: Mapped[str] = mapped_column(String(128), nullable=False)
    shared_by: Mapped[str] = mapped_column(String(128), nullable=False)
    field: Mapped[str] = mapped_column(String(16), nullable=False)
    value: Mapped[str] = mapped_column(String(320), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)

    __table_args__ = (
        Index("ix_contact_shares_conversation", "conversation_id", "created_at"),
    )


class ConversationBlock(Base):
    """Enforceable block between the two participants of one conversation.

    A block is persisted state, not just an audit event: while a row exists the
    blocked participant cannot send messages or share contact details, and no
    push is delivered to the blocker. Either participant may block the other.
    """

    __tablename__ = "conversation_blocks"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    conversation_id: Mapped[str] = mapped_column(
        ForeignKey("conversations.id", ondelete="CASCADE"), nullable=False
    )
    request_id: Mapped[str] = mapped_column(String(128), nullable=False)
    blocker_id: Mapped[str] = mapped_column(String(128), nullable=False)
    blocked_id: Mapped[str] = mapped_column(String(128), nullable=False)
    reason: Mapped[str] = mapped_column(Text, nullable=False, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)

    __table_args__ = (
        UniqueConstraint("conversation_id", "blocker_id", "blocked_id", name="uq_conversation_blocks_pair"),
        Index("ix_conversation_blocks_conversation", "conversation_id"),
        Index("ix_conversation_blocks_blocked", "blocked_id"),
    )


class PendingSubmission(Base):
    __tablename__ = "pending_submissions"

    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    idempotency_key: Mapped[str] = mapped_column(String(128), nullable=False, unique=True)
    payload: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    last_error: Mapped[str | None] = mapped_column(Text, nullable=True)
    next_attempt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class RateLimitCounter(Base):
    """Shared fixed-window rate-limit counter (see app/rate_limit.py).

    Kept in the database so limits hold across every worker and survive a
    restart, unlike the in-process fallback.
    """

    __tablename__ = "rate_limit_counters"

    bucket_key: Mapped[str] = mapped_column(String(256), primary_key=True)
    window_start: Mapped[int] = mapped_column(Integer, primary_key=True)
    hits: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
