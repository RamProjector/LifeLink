"""Persistence for the separate donor-profile flow.

The account (``user_id``) is the identity; the operational ``donors`` row the
matching engine reads is kept in sync here so matching logic is never
duplicated. Matching itself is delegated to the existing
``SqlAlchemyDonorStore.recompute_matches_for_donor`` and ``score_donor``.

Gating rule (the point of this flow): a donor profile only becomes matchable
when it is **verified** *and* its availability is **available**. An unverified
profile is never marked available on the operational row, so it can never be
matched, even if the user toggles availability on.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from decimal import Decimal
from uuid import uuid4

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .db_models import Donor as DonorRow
from .db_models import DonorProfile as DonorProfileRow
from .db_models import LifeLinkProfile
from .donor_api import DonorAvailability, DonorProfileIn
from .donor_profile_api import DonorProfileMeOut, DonorProfileUpsertIn
from .donor_repositories import SqlAlchemyDonorStore

logger = logging.getLogger("lifelink.donor_profile_repositories")


def _enum_value(value: object) -> object:
    """Return an enum's underlying value, leaving plain values unchanged."""
    return getattr(value, "value", value)


def effective_available(availability_status: str, verified: bool) -> bool:
    """A profile donor is only matchable when verified *and* available.

    This is the single place the gating rule lives. The operational ``donors``
    row's ``available`` flag is derived from it, so the existing matching
    predicate (which already requires ``available``) enforces the rule without
    any duplicated eligibility logic.
    """
    return availability_status == DonorAvailability.AVAILABLE.value and bool(verified)


class SqlAlchemyDonorProfileStore:
    """Persistence for the donor-profile flow and its operational donor row."""

    def __init__(self, session: AsyncSession) -> None:
        """Store the async session used for all reads and writes."""
        self.session = session

    async def get(self, user_id: str) -> DonorProfileRow | None:
        """Return the donor profile for ``user_id``, or ``None`` when absent."""
        return await self.session.get(DonorProfileRow, user_id)

    async def _resolve_display_name(self, user_id: str, requested: str | None) -> str:
        """Pick a display name from the request, the account profile, or a fallback."""
        if requested and requested.strip():
            return requested.strip()
        profile = await self.session.get(LifeLinkProfile, user_id)
        if profile is not None and profile.display_name and profile.display_name.strip():
            return profile.display_name.strip()
        if profile is not None and profile.email:
            return profile.email.split("@", 1)[0]
        return "LifeLink donor"

    async def _sync_operational_donor(
        self,
        user_id: str,
        *,
        blood_type: str,
        latitude: float,
        longitude: float,
        service_radius_km: float,
        display_name: str,
        donor_note: str,
        preferred_contact_method: str,
        profile_visible: bool,
        verified: bool,
        availability_status: str,
    ) -> DonorRow:
        """Create/update the operational donor row the matching engine reads."""
        store = SqlAlchemyDonorStore(self.session)
        row = await store.upsert_profile(
            user_id,
            DonorProfileIn(
                donor_id=user_id,
                display_name=display_name,
                blood_type=blood_type,
                latitude=latitude,
                longitude=longitude,
                service_radius_km=service_radius_km,
                verified=verified,
                donor_note=donor_note,
                preferred_contact_method=preferred_contact_method,
                profile_visible=profile_visible,
            ),
        )
        # `upsert_profile` deliberately never trusts a client-supplied verified
        # flag. The donor-profile row is the server-controlled source of truth,
        # so mirror it onto the operational row here.
        row.verified = bool(verified)
        row.available = effective_available(availability_status, verified)
        row.availability_updated_at = datetime.now(UTC)
        await self.session.commit()
        return row

    async def upsert(self, user_id: str, payload: DonorProfileUpsertIn) -> DonorProfileMeOut:
        """Opt in / update the current user's donor profile."""
        now = datetime.now(UTC)
        row = await self.session.get(DonorProfileRow, user_id)
        display_name = await self._resolve_display_name(user_id, payload.display_name)
        if row is None:
            row = DonorProfileRow(
                user_id=user_id,
                donor_id=user_id,
                blood_type=payload.blood_type.value,
                latitude=Decimal(str(payload.latitude)),
                longitude=Decimal(str(payload.longitude)),
                area=payload.area,
                availability_status=payload.availability_status.value,
                last_donation_date=payload.last_donation_date,
                verified=False,  # server-controlled; never set by the client
                notifications_enabled=payload.notifications_enabled,
                service_radius_km=Decimal(str(payload.service_radius_km)),
            )
            self.session.add(row)
        else:
            row.blood_type = payload.blood_type.value
            row.latitude = Decimal(str(payload.latitude))
            row.longitude = Decimal(str(payload.longitude))
            row.area = payload.area
            row.availability_status = payload.availability_status.value
            row.last_donation_date = payload.last_donation_date
            row.notifications_enabled = payload.notifications_enabled
            row.service_radius_km = Decimal(str(payload.service_radius_km))
            # `verified` is intentionally left as stored: re-saving a profile
            # must not un-verify an already verified donor.
            row.updated_at = now
        await self.session.commit()

        await self._sync_operational_donor(
            user_id,
            blood_type=payload.blood_type.value,
            latitude=payload.latitude,
            longitude=payload.longitude,
            service_radius_km=payload.service_radius_km,
            display_name=display_name,
            donor_note=payload.donor_note,
            preferred_contact_method=payload.preferred_contact_method,
            profile_visible=payload.profile_visible,
            verified=row.verified,
            availability_status=row.availability_status,
        )
        await self._recompute(user_id)
        return self.to_out(row, display_name, payload)

    async def set_availability(self, user_id: str, availability: DonorAvailability) -> DonorProfileMeOut:
        """Persist availability, mirror it onto the operational row, and re-match."""
        row = await self.session.get(DonorProfileRow, user_id)
        if row is None:
            raise KeyError(user_id)
        row.availability_status = availability.value
        row.updated_at = datetime.now(UTC)
        await self.session.commit()

        donor_row = await SqlAlchemyDonorStore(self.session).get_by_identity(user_id)
        if donor_row is not None:
            donor_row.available = effective_available(availability.value, row.verified)
            donor_row.availability_updated_at = datetime.now(UTC)
            await self.session.commit()
        await self._recompute(user_id)
        return self.to_out(row, donor_row.display_name if donor_row else "LifeLink donor", None)

    async def delete(self, user_id: str) -> None:
        """Opt out: remove the profile and stop the donor from being matched."""
        row = await self.session.get(DonorProfileRow, user_id)
        if row is not None:
            await self.session.delete(row)
        donor_row = await SqlAlchemyDonorStore(self.session).get_by_identity(user_id)
        if donor_row is not None:
            donor_row.available = False
            donor_row.profile_visible = False
            donor_row.availability_updated_at = datetime.now(UTC)
        await self.session.commit()
        await self._recompute(user_id)

    async def _recompute(self, user_id: str) -> None:
        """Re-evaluate matching with the existing engine; best-effort."""
        try:
            await SqlAlchemyDonorStore(self.session).recompute_matches_for_donor(user_id)
        except Exception:
            await self.session.rollback()
            logger.exception("Donor match recompute failed after donor-profile change for user_id=%s", user_id)

    def to_out(
        self, row: DonorProfileRow, display_name: str, payload: DonorProfileUpsertIn | None
    ) -> DonorProfileMeOut:
        """Map a stored profile row to the API response model."""
        return DonorProfileMeOut(
            user_id=row.user_id,
            donor_id=row.donor_id,
            blood_type=_enum_value(row.blood_type),
            latitude=float(row.latitude),
            longitude=float(row.longitude),
            area=row.area,
            service_radius_km=float(row.service_radius_km),
            availability_status=row.availability_status,
            last_donation_date=row.last_donation_date,
            verified=bool(row.verified),
            notifications_enabled=bool(row.notifications_enabled),
            display_name=display_name,
            donor_note=payload.donor_note if payload is not None else "",
            preferred_contact_method=payload.preferred_contact_method if payload is not None else "in_app",
            profile_visible=payload.profile_visible if payload is not None else True,
            created_at=row.created_at,
            updated_at=row.updated_at,
        )


def new_donor_id() -> str:
    """Return a fresh, unique donor identifier."""
    return f"donor_{uuid4().hex}"


async def donor_profile_exists(session: AsyncSession, user_id: str) -> bool:
    """Return whether ``user_id`` already has a donor profile."""
    return (await session.scalar(select(DonorProfileRow.user_id).where(DonorProfileRow.user_id == user_id))) is not None
