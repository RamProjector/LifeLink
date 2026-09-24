from __future__ import annotations

import os
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Annotated
from uuid import uuid4

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from .db import create_all_tables, get_db_session
from .db_models import Donor as DonorRow, EmergencyRequest as EmergencyRequestRow, Facility, LifeLinkProfile, LifeLinkRoleEnum
from .fcm import send_push_safely
from .main import (
    EmergencyRequestIn,
    EmergencyRequestOut,
    EmergencyRequestStatusOut,
    RequestActionOut,
    ContactSelectedDonorsIn,
    ContactSelectedDonorsOut,
    ManualFallbackOut,
    RequestStatus,
    RequestHistoryItemOut,
    score_donor,
    validate_business_rules,
)
from .repositories import SqlAlchemyDonorRepository, SqlAlchemyRequestStore, create_request_record
from .donor_api import (
    DonorAvailability,
    DonorAvailabilityIn,
    DonorInboxItem,
    DonorProfileIn,
    DonorProfileOut,
    DonorResponseIn,
    DonorResponseOut,
    profile_to_out,
)
from .donor_repositories import SqlAlchemyDonorStore
from .main import Donor
from .security import Principal, get_postgres_principal
from .rate_limit import enforce_rate_limit

@asynccontextmanager
async def lifespan(_: FastAPI):
    await create_all_tables()
    yield


app = FastAPI(title="LifeLink Matching Service — PostgreSQL", lifespan=lifespan)
logger = logging.getLogger("lifelink.api")


def donor_setup_complete(row: DonorRow) -> bool:
    return (
        len(row.display_name.strip()) >= 2
        and row.blood_type.value != "UNKNOWN"
        and -90 <= float(row.latitude) <= 90
        and -180 <= float(row.longitude) <= 180
        and 0 < float(row.service_radius_km) <= 100
    )


@app.exception_handler(IntegrityError)
async def integrity_error_handler(request: Request, exc: IntegrityError) -> JSONResponse:
    logger.exception("Database constraint failure on %s", request.url.path)
    return JSONResponse(status_code=409, content={"detail": "This request conflicts with current server data. Refresh and submit again."})


@app.exception_handler(SQLAlchemyError)
async def sqlalchemy_error_handler(request: Request, exc: SQLAlchemyError) -> JSONResponse:
    logger.exception("Database failure on %s", request.url.path)
    return JSONResponse(status_code=503, content={"detail": "LifeLink could not save the request right now. Please retry."})


async def _tokens_for_donors(session: AsyncSession, donor_ids: list[str]) -> list[str]:
    if not donor_ids:
        return []
    result = await session.execute(
        select(LifeLinkProfile.fcm_token)
        .join(DonorRow, DonorRow.user_id == LifeLinkProfile.user_id)
        .where(DonorRow.id.in_(donor_ids), LifeLinkProfile.fcm_token.is_not(None))
    )
    return [token for (token,) in result.all() if token]


async def _token_for_user(session: AsyncSession, user_id: str) -> list[str]:
    token = await session.scalar(select(LifeLinkProfile.fcm_token).where(LifeLinkProfile.user_id == user_id))
    return [token] if token else []


class ProfileIn(BaseModel):
    role: str = Field(pattern="^(requester|donor)$")
    display_name: str | None = Field(default=None, max_length=160)


class ProfileOut(BaseModel):
    user_id: str
    email: str
    role: str
    display_name: str | None = None


class PushTokenIn(BaseModel):
    token: str = Field(min_length=1, max_length=4096)
    platform: str = Field(default="android", pattern="^android$")


class RequesterContactOut(BaseModel):
    donor_id: str
    display_name: str
    status: str
    accepted_at: datetime | None = None
    contact_shared_at: datetime | None = None
    updated_at: datetime | None = None
    contact_email: str | None = None


class ContactStatusUpdateIn(BaseModel):
    status: str = Field(pattern="^(contact_shared|meeting_arranged|fulfilled|cancelled)$")


class ContactModerationIn(BaseModel):
    reason: str = Field(default="", max_length=500)


class ContactModerationOut(BaseModel):
    request_id: str
    donor_id: str
    action: str
    accepted: bool = True


@app.put("/v1/profile", response_model=ProfileOut)
async def upsert_profile(
    payload: ProfileIn,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    if principal.subject == "development-user":
        raise HTTPException(status_code=401, detail="An authenticated user is required")
    row = await session.get(LifeLinkProfile, principal.subject)
    if row is None:
        row = LifeLinkProfile(
            user_id=principal.subject,
            email=principal.email or "",
            role=LifeLinkRoleEnum(payload.role),
            display_name=payload.display_name,
        )
        session.add(row)
    else:
        row.email = principal.email or row.email
        row.role = LifeLinkRoleEnum(payload.role)
        row.display_name = payload.display_name
    await session.commit()
    return ProfileOut(
        user_id=row.user_id,
        email=row.email,
        role=row.role.value,
        display_name=row.display_name,
        can_request=row.can_request,
        can_donate=row.can_donate,
    )


@app.get("/v1/profile", response_model=ProfileOut)
async def get_profile(
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    if principal.subject == "development-user":
        raise HTTPException(status_code=401, detail="An authenticated user is required")
    row = await session.get(LifeLinkProfile, principal.subject)
    if row is None:
        raise HTTPException(status_code=404, detail="Profile not found")
    return ProfileOut(
        user_id=row.user_id,
        email=row.email,
        role=row.role.value,
        display_name=row.display_name,
        can_request=row.can_request,
        can_donate=row.can_donate,
    )


@app.put("/v1/push-token", status_code=204)
async def register_push_token(
    payload: PushTokenIn,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    if principal.subject == "development-user":
        raise HTTPException(status_code=401, detail="An authenticated user is required")
    row = await session.get(LifeLinkProfile, principal.subject)
    if row is None:
        raise HTTPException(status_code=404, detail="Profile not found")
    row.fcm_token = payload.token
    await session.commit()


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "lifelink-matching-postgres"}


@app.get("/auth/confirmed", response_class=HTMLResponse)
async def auth_confirmed() -> str:
    """Landing page for Supabase email confirmation links."""
    return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>LifeLink email confirmed</title>
<style>body{font-family:system-ui,sans-serif;margin:0;padding:3rem 1.5rem;background:#fff8f8;color:#241a1c}main{max-width:30rem;margin:auto;background:#fff;padding:2rem;border-radius:1rem;box-shadow:0 8px 30px #3b171714}h1{color:#bd123f}p{line-height:1.6}strong{color:#8b1232}.open{display:block;margin-top:1.5rem;padding:1rem;text-align:center;border-radius:.75rem;background:#bd123f;color:#fff;text-decoration:none;font-weight:700}</style>
</head><body><main><h1>Email confirmed</h1>
<p>Your LifeLink account email has been confirmed.</p>
<p>Open LifeLink to finish account authentication, or choose <strong>Sign in</strong> using the email and password you registered with.</p>
<a id="open-app" class="open" href="lifelink://auth/confirm">Open LifeLink</a>
</main><script>const target=document.getElementById('open-app');const recovery=new URLSearchParams(window.location.search).get('type')==='recovery';const path=recovery?'/recovery':'/confirm';target.href='lifelink://auth'+path+window.location.search+window.location.hash;</script></body></html>"""


@app.post("/v1/emergency-requests", response_model=EmergencyRequestOut | ManualFallbackOut, status_code=201)
async def create_emergency_request_postgres(
    payload: EmergencyRequestIn,
    idempotency_header: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    validate_business_rules(payload)
    if principal.subject != "development-user" and principal.subject != payload.requester_id:
        raise HTTPException(status_code=403, detail="requester_id must match the authenticated user")
    enforce_rate_limit(f"request-create:{principal.subject}", 5, 300)
    if idempotency_header and idempotency_header != payload.idempotency_key:
        raise HTTPException(status_code=400, detail="Idempotency-Key header must match payload.idempotency_key.")

    # Android drafts can outlive a facility seed or migration. Preserve the
    # coordinates but clear an unknown facility foreign key instead of failing
    # the entire request on the hosted database.
    facility_id = payload.location.facility_id
    if facility_id:
        facility = await session.scalar(select(Facility).where(Facility.id == facility_id))
        if facility is None:
            logger.warning("Unknown facility_id=%s; using approximate location", facility_id)
            payload = payload.model_copy(update={
                "location": payload.location.model_copy(update={"facility_id": None, "verified": False})
            })

    store = SqlAlchemyRequestStore(session)
    existing = await store.get_by_idempotency_key_async(payload.idempotency_key)
    if existing:
        return EmergencyRequestOut(
            request_id=existing.request_id,
            status=existing.status,
            created_at=existing.created_at,
            expires_at=existing.expires_at,
            matches=existing.matches,
            matching_version="v1-explainable-weighted",
            notifications_created=len(existing.matches),
        )

    now = datetime.now(timezone.utc)
    request_id = f"req_{uuid4().hex}"
    donor_repository = SqlAlchemyDonorRepository(session)
    donors = await donor_repository.list_active_donors(excluded_user_id=principal.subject)
    matches = [
        scored
        for donor in donors
        if (scored := score_donor(payload, donor, now)) is not None
    ]
    if payload.ai_matching_enabled:
        matches.sort(key=lambda item: (-item.score, item.estimated_travel_minutes, item.distance_km))
        matching_version = "v1-explainable-weighted"
    else:
        matches.sort(key=lambda item: (item.distance_km, item.estimated_travel_minutes, -item.score))
        matching_version = "v1-distance-only"
    matches = matches[:10]

    if payload.blood_type.value == "UNKNOWN":
        await create_request_record(
            session=session,
            payload=payload,
            matches=[],
            request_id=request_id,
            status=RequestStatus.MANUAL_BROADCAST,
        )
        return ManualFallbackOut(
            request_id=request_id,
            reason="Blood type must be verified by a blood-bank professional before automatic eligibility matching.",
            eligible_audience_filter={"verification": "verified", "status": "manual_review"},
        )

    record = await create_request_record(
        session=session,
        payload=payload,
        matches=matches,
        request_id=request_id,
        status=RequestStatus.AWAITING_RESPONSES,
    )
    await send_push_safely(
        await _tokens_for_donors(session, [match.donor_id for match in record.matches]),
        "LifeLink donor match",
        f"A {record.payload.blood_type.value} blood request needs a response near {record.payload.location.area}.",
        {"type": "donor_match", "request_id": record.request_id},
    )
    return EmergencyRequestOut(
        request_id=record.request_id,
        status=record.status,
        created_at=record.created_at,
        expires_at=record.expires_at,
        matches=record.matches,
        matching_version=matching_version,
        notifications_created=len(record.matches),
    )


@app.post("/v1/emergency-requests/{request_id}/contact", response_model=ContactSelectedDonorsOut)
async def contact_selected_donors_postgres(
    request_id: str,
    payload: ContactSelectedDonorsIn,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    store = SqlAlchemyRequestStore(session)
    record = await store.get_by_id_async(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    if principal.subject != "development-user" and principal.subject != record.payload.requester_id:
        raise HTTPException(status_code=403, detail="Not allowed to contact donors for this request")
    if principal.subject in payload.donor_ids:
        raise HTTPException(status_code=403, detail="You cannot contact your own donor profile")
    enforce_rate_limit(f"contact-request:{principal.subject}", 20, 300)
    try:
        await store.contact_selected_donors_async(request_id, payload.donor_ids)
    except KeyError:
        raise HTTPException(status_code=404, detail="Request not found") from None
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    await store.record_audit_async(principal.subject, "contact_requested", request_id, metadata={"donor_count": len(payload.donor_ids)})
    await send_push_safely(
        await _tokens_for_donors(session, payload.donor_ids),
        "LifeLink contact request",
        "A requester selected you for contact. Open LifeLink to review the request.",
        {"type": "contact_request", "request_id": request_id},
    )
    return ContactSelectedDonorsOut(request_id=request_id, donor_ids=payload.donor_ids)


@app.get("/v1/emergency-requests/{request_id}/contacts", response_model=list[RequesterContactOut])
async def requester_contacts_postgres(
    request_id: str,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    store = SqlAlchemyRequestStore(session)
    record = await store.get_by_id_async(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    if principal.subject != "development-user" and principal.subject != record.payload.requester_id:
        raise HTTPException(status_code=403, detail="Not allowed to view contacts for this request")
    return [RequesterContactOut(**item) for item in await store.requester_contacts_async(request_id, record.payload.requester_id)]


@app.patch("/v1/emergency-requests/{request_id}/contacts/{donor_id}", response_model=RequesterContactOut)
async def update_requester_contact_status(
    request_id: str,
    donor_id: str,
    payload: ContactStatusUpdateIn,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    store = SqlAlchemyRequestStore(session)
    record = await store.get_by_id_async(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    if principal.subject != "development-user" and principal.subject != record.payload.requester_id:
        raise HTTPException(status_code=403, detail="Not allowed to update this contact")
    enforce_rate_limit(f"contact-status:{principal.subject}", 30, 300)
    try:
        item = await store.update_contact_status_async(request_id, donor_id, record.payload.requester_id, payload.status)
    except KeyError:
        raise HTTPException(status_code=404, detail="Contact not found") from None
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    contacts = await store.requester_contacts_async(request_id, record.payload.requester_id)
    return RequesterContactOut(**next(contact for contact in contacts if contact["donor_id"] == donor_id))


@app.post("/v1/emergency-requests/{request_id}/contacts/{donor_id}/report", response_model=ContactModerationOut)
async def report_requester_contact(
    request_id: str,
    donor_id: str,
    payload: ContactModerationIn,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    store = SqlAlchemyRequestStore(session)
    record = await store.get_by_id_async(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    if principal.subject != "development-user" and principal.subject != record.payload.requester_id:
        raise HTTPException(status_code=403, detail="Not allowed to report this contact")
    contacts = await store.requester_contacts_async(request_id, record.payload.requester_id)
    if not any(contact["donor_id"] == donor_id for contact in contacts):
        raise HTTPException(status_code=404, detail="Contact not found")
    enforce_rate_limit(f"contact-report:{principal.subject}", 10, 300)
    await store.record_audit_async(principal.subject, "contact_reported", request_id, donor_id, {"reason": payload.reason})
    return ContactModerationOut(request_id=request_id, donor_id=donor_id, action="reported")


@app.post("/v1/emergency-requests/{request_id}/contacts/{donor_id}/block", response_model=ContactModerationOut)
async def block_requester_contact(
    request_id: str,
    donor_id: str,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    store = SqlAlchemyRequestStore(session)
    record = await store.get_by_id_async(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    if principal.subject != "development-user" and principal.subject != record.payload.requester_id:
        raise HTTPException(status_code=403, detail="Not allowed to block this contact")
    contacts = await store.requester_contacts_async(request_id, record.payload.requester_id)
    if not any(contact["donor_id"] == donor_id for contact in contacts):
        raise HTTPException(status_code=404, detail="Contact not found")
    enforce_rate_limit(f"contact-block:{principal.subject}", 10, 300)
    await store.record_audit_async(principal.subject, "contact_blocked", request_id, donor_id)
    return ContactModerationOut(request_id=request_id, donor_id=donor_id, action="blocked")


@app.post("/v1/emergency-requests/{request_id}/manual-broadcast", response_model=ManualFallbackOut)
async def manual_broadcast_postgres(
    request_id: str,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    store = SqlAlchemyRequestStore(session)
    record = await store.get_by_id_async(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    if principal.subject != "development-user" and principal.subject != record.payload.requester_id:
        raise HTTPException(status_code=403, detail="Not allowed to broadcast this request")
    enforce_rate_limit(f"manual-broadcast:{principal.subject}", 5, 300)
    try:
        record = await store.set_manual_broadcast_async(request_id)
    except KeyError:
        raise HTTPException(status_code=404, detail="Request not found") from None
    await store.record_audit_async(principal.subject, "request_manual_broadcast", request_id)

    return ManualFallbackOut(
        request_id=request_id,
        reason="Automatic ranking is unavailable or the request requires manual blood-bank review.",
        eligible_audience_filter={
            "blood_type": record.payload.blood_type.value,
            "verification": "verified",
            "area": record.payload.location.area,
        },
    )


@app.get("/v1/emergency-requests", response_model=list[RequestHistoryItemOut])
async def list_emergency_request_history(
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    if principal.subject == "development-user":
        return []
    store = SqlAlchemyRequestStore(session)
    records = await store.list_by_requester_async(principal.subject)
    items: list[RequestHistoryItemOut] = []
    for record in records:
        contacts = await store.requester_contacts_async(record.request_id, principal.subject)
        items.append(RequestHistoryItemOut(
            request_id=record.request_id,
            status=record.status,
            created_at=record.created_at,
            expires_at=record.expires_at,
            blood_type=record.payload.blood_type,
            units=record.payload.units,
            urgency=record.payload.urgency,
            facility_name=record.payload.location.facility_name,
            area=record.payload.location.area,
            notifications_created=len(record.matches),
            matches_responded=sum(1 for contact in contacts if contact["status"] in {"accepted", "declined"}),
            contact_statuses=[contact["status"] for contact in contacts],
        ))
    return items


@app.get("/v1/emergency-requests/{request_id}", response_model=EmergencyRequestStatusOut)
async def get_emergency_request_status(
    request_id: str,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    record = await SqlAlchemyRequestStore(session).get_by_id_async(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    if principal.subject != "development-user" and principal.subject != record.payload.requester_id:
        raise HTTPException(status_code=403, detail="Not allowed to view this request")
    return EmergencyRequestStatusOut(
        request_id=record.request_id,
        status=record.status,
        notifications_created=len(record.matches),
        matches_responded=0,
        reason=(
            "Automatic matching is unavailable; manual review is required."
            if record.status == RequestStatus.MANUAL_BROADCAST
            else None
        ),
    )


@app.post("/v1/emergency-requests/{request_id}/cancel", response_model=RequestActionOut)
async def cancel_emergency_request(
    request_id: str,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    store = SqlAlchemyRequestStore(session)
    record = await store.get_by_id_async(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    if principal.subject != "development-user" and principal.subject != record.payload.requester_id:
        raise HTTPException(status_code=403, detail="Not allowed to cancel this request")
    try:
        record = await store.set_cancelled_async(request_id)
    except KeyError:
        raise HTTPException(status_code=404, detail="Request not found") from None
    await store.record_audit_async(principal.subject, "request_cancelled", request_id)
    return RequestActionOut(
        request_id=record.request_id,
        status=record.status,
        reason="Cancelled by coordinator",
    )


@app.post("/v1/emergency-requests/{request_id}/fulfill", response_model=RequestActionOut)
async def fulfill_emergency_request(
    request_id: str,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    store = SqlAlchemyRequestStore(session)
    record = await store.get_by_id_async(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    if principal.subject != "development-user" and principal.subject != record.payload.requester_id:
        raise HTTPException(status_code=403, detail="Not allowed to fulfill this request")
    try:
        record = await store.set_fulfilled_async(request_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    await store.record_audit_async(principal.subject, "request_fulfilled", request_id)
    return RequestActionOut(request_id=record.request_id, status=record.status, reason="Marked fulfilled by requester")


@app.put("/v1/donors/{donor_id}", response_model=DonorProfileOut)
async def register_donor_postgres(
    donor_id: str,
    payload: DonorProfileIn,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    if principal.subject != "development-user" and principal.subject != donor_id:
        raise HTTPException(status_code=403, detail="donor_id must match the authenticated user")
    if donor_id != payload.donor_id:
        raise HTTPException(status_code=400, detail="Path donor_id must match payload donor_id")
    if payload.blood_type.value == "UNKNOWN":
        raise HTTPException(status_code=400, detail="Select a confirmed blood type before completing donor setup")
    try:
        profile = await session.get(LifeLinkProfile, donor_id)
        if profile is not None:
            profile.can_donate = True
        row = await SqlAlchemyDonorStore(session).upsert_profile(donor_id, payload)
        donor = Donor(
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
            donor_note=row.donor_note,
            preferred_contact_method=row.preferred_contact_method,
            pause_reason=row.pause_reason,
            profile_visible=row.profile_visible,
        )
        return profile_to_out(donor, DonorAvailability.AVAILABLE if row.available else DonorAvailability.OFFLINE)
    except SQLAlchemyError as exc:
        await session.rollback()
        logger.exception("Donor profile save database failure for operation donor_profile_save")
        raise HTTPException(status_code=503, detail="donor_profile_save_database_failure") from exc
    except Exception as exc:
        await session.rollback()
        logger.exception("Donor profile save application failure for operation donor_profile_save")
        raise HTTPException(status_code=500, detail=f"donor_profile_save_application_failure:{type(exc).__name__}") from exc


@app.patch("/v1/donors/{donor_id}/availability", response_model=DonorProfileOut)
async def update_donor_availability_postgres(
    donor_id: str,
    payload: DonorAvailabilityIn,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    if principal.subject != "development-user" and principal.subject != donor_id:
        raise HTTPException(status_code=403, detail="donor_id must match the authenticated user")
    donor_row = await SqlAlchemyDonorStore(session).get_by_identity(donor_id)
    if donor_row is None or not donor_setup_complete(donor_row):
        raise HTTPException(status_code=409, detail="Complete donor setup before choosing availability")
    try:
        row = await SqlAlchemyDonorStore(session).set_availability(donor_id, payload.availability)
    except KeyError:
        raise HTTPException(status_code=404, detail="Donor not found") from None
    donor = Donor(
        donor_id=row.id,
        display_name=row.display_name,
        blood_type=row.blood_type.value,
        latitude=float(row.latitude), longitude=float(row.longitude),
        available=row.available, availability_updated_at=row.availability_updated_at,
        verified=row.verified, service_radius_km=float(row.service_radius_km),
        estimated_response_probability=float(row.estimated_response_probability),
        donor_note=row.donor_note, preferred_contact_method=row.preferred_contact_method,
        pause_reason=row.pause_reason, profile_visible=row.profile_visible,
    )
    return profile_to_out(donor, payload.availability)


@app.get("/v1/donors/{donor_id}/requests", response_model=list[DonorInboxItem])
async def donor_request_inbox_postgres(
    donor_id: str,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    if principal.subject != "development-user" and principal.subject != donor_id:
        raise HTTPException(status_code=403, detail="donor_id must match the authenticated user")
    donor_row = await SqlAlchemyDonorStore(session).get_by_identity(donor_id)
    if donor_row is None or not donor_setup_complete(donor_row):
        return []
    items = []
    for request, match, contact in await SqlAlchemyDonorStore(session).inbox(donor_row.id):
        items.append(DonorInboxItem(
            request_id=request.id,
            blood_type=request.blood_type.value,
            units=request.units,
            urgency=request.urgency.value,
            facility_name=request.facility.name if request.facility else "Approximate request area",
            area=request.facility.area if request.facility else "Location shared after acceptance",
            distance_km=float(match.distance_km),
            status=contact.status if contact is not None else match.status.value,
            responded_at=match.responded_at,
        ))
    return items


@app.post("/v1/donors/{donor_id}/requests/{request_id}/response", response_model=DonorResponseOut)
async def donor_response_postgres(
    donor_id: str,
    request_id: str,
    payload: DonorResponseIn,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_postgres_principal),
):
    if principal.subject != "development-user" and principal.subject != donor_id:
        raise HTTPException(status_code=403, detail="donor_id must match the authenticated user")
    try:
        donor_row = await SqlAlchemyDonorStore(session).get_by_identity(donor_id)
        if donor_row is None:
            raise KeyError(donor_id)
        match = await SqlAlchemyDonorStore(session).respond(donor_row.id, request_id, payload)
    except KeyError:
        raise HTTPException(status_code=403, detail="Donor is not eligible for this request") from None
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    await SqlAlchemyRequestStore(session).record_audit_async(principal.subject, f"donor_response_{payload.response}", request_id, donor_id)
    request = await session.get(EmergencyRequestRow, request_id)
    if request is not None:
        await send_push_safely(
            await _token_for_user(session, request.requester_id),
            "LifeLink donor response",
            f"A donor has {payload.response}ed your request. Open LifeLink to view the update.",
            {"type": "donor_response", "request_id": request_id},
        )
    return DonorResponseOut(
        request_id=request_id,
        donor_id=donor_id,
        response=payload.response,
        responded_at=match.responded_at,
    )
