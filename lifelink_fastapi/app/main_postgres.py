from __future__ import annotations

import os
from datetime import datetime, timezone
from typing import Annotated
from uuid import uuid4

from fastapi import Depends, FastAPI, Header, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

# The in-memory demo remains anonymous by default, but a PostgreSQL deployment
# must not become publicly mutable because its environment was misconfigured.
# An explicit LIFELINK_AUTH_REQUIRED=false can still be used for local testing.
os.environ.setdefault("LIFELINK_AUTH_REQUIRED", "true")

from .db import get_db_session
from .main import (
    EmergencyRequestIn,
    EmergencyRequestOut,
    EmergencyRequestStatusOut,
    RequestActionOut,
    ContactSelectedDonorsIn,
    ContactSelectedDonorsOut,
    ManualFallbackOut,
    RequestStatus,
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
from .security import Principal, get_principal

app = FastAPI(title="LifeLink Matching Service — PostgreSQL")


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "lifelink-matching-postgres"}


@app.post("/v1/emergency-requests", response_model=EmergencyRequestOut | ManualFallbackOut, status_code=201)
async def create_emergency_request_postgres(
    payload: EmergencyRequestIn,
    idempotency_header: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_principal),
):
    validate_business_rules(payload)
    if principal.subject != "development-user" and principal.subject != payload.requester_id:
        raise HTTPException(status_code=403, detail="requester_id must match the authenticated user")
    if idempotency_header and idempotency_header != payload.idempotency_key:
        raise HTTPException(status_code=400, detail="Idempotency-Key header must match payload.idempotency_key.")

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
    donors = await donor_repository.list_active_donors()
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
    principal: Principal = Depends(get_principal),
):
    store = SqlAlchemyRequestStore(session)
    record = await store.get_by_id_async(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
    if principal.subject != "development-user" and principal.subject != record.payload.requester_id:
        raise HTTPException(status_code=403, detail="Not allowed to contact donors for this request")
    try:
        await store.contact_selected_donors_async(request_id, payload.donor_ids)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return ContactSelectedDonorsOut(request_id=request_id, donor_ids=payload.donor_ids)


@app.post("/v1/emergency-requests/{request_id}/manual-broadcast", response_model=ManualFallbackOut)
async def manual_broadcast_postgres(
    request_id: str,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_principal),
):
    store = SqlAlchemyRequestStore(session)
    try:
        record = await store.set_manual_broadcast_async(request_id)
    except KeyError:
        raise HTTPException(status_code=404, detail="Request not found") from None

    return ManualFallbackOut(
        request_id=request_id,
        reason="Automatic ranking is unavailable or the request requires manual blood-bank review.",
        eligible_audience_filter={
            "blood_type": record.payload.blood_type.value,
            "verification": "verified",
            "area": record.payload.location.area,
        },
    )


@app.get("/v1/emergency-requests/{request_id}", response_model=EmergencyRequestStatusOut)
async def get_emergency_request_status(
    request_id: str,
    session: AsyncSession = Depends(get_db_session),
):
    record = await SqlAlchemyRequestStore(session).get_by_id_async(request_id)
    if record is None:
        raise HTTPException(status_code=404, detail="Request not found")
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
    principal: Principal = Depends(get_principal),
):
    store = SqlAlchemyRequestStore(session)
    try:
        record = await store.set_cancelled_async(request_id)
    except KeyError:
        raise HTTPException(status_code=404, detail="Request not found") from None
    return RequestActionOut(
        request_id=record.request_id,
        status=record.status,
        reason="Cancelled by coordinator",
    )


@app.put("/v1/donors/{donor_id}", response_model=DonorProfileOut)
async def register_donor_postgres(
    donor_id: str,
    payload: DonorProfileIn,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_principal),
):
    if principal.subject != "development-user" and principal.subject != donor_id:
        raise HTTPException(status_code=403, detail="donor_id must match the authenticated user")
    if donor_id != payload.donor_id:
        raise HTTPException(status_code=400, detail="Path donor_id must match payload donor_id")
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
    )
    return profile_to_out(donor, DonorAvailability.AVAILABLE if row.available else DonorAvailability.OFFLINE)


@app.patch("/v1/donors/{donor_id}/availability", response_model=DonorProfileOut)
async def update_donor_availability_postgres(
    donor_id: str,
    payload: DonorAvailabilityIn,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_principal),
):
    if principal.subject != "development-user" and principal.subject != donor_id:
        raise HTTPException(status_code=403, detail="donor_id must match the authenticated user")
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
    )
    return profile_to_out(donor, payload.availability)


@app.get("/v1/donors/{donor_id}/requests", response_model=list[DonorInboxItem])
async def donor_request_inbox_postgres(
    donor_id: str,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_principal),
):
    if principal.subject != "development-user" and principal.subject != donor_id:
        raise HTTPException(status_code=403, detail="donor_id must match the authenticated user")
    items = []
    for request, match in await SqlAlchemyDonorStore(session).inbox(donor_id):
        items.append(DonorInboxItem(
            request_id=request.id,
            blood_type=request.blood_type.value,
            units=request.units,
            urgency=request.urgency.value,
            facility_name=request.facility.name if request.facility else "Approximate request area",
            area=request.facility.area if request.facility else "Location shared after acceptance",
            distance_km=float(match.distance_km),
            status=match.status.value,
            responded_at=match.responded_at,
        ))
    return items


@app.post("/v1/donors/{donor_id}/requests/{request_id}/response", response_model=DonorResponseOut)
async def donor_response_postgres(
    donor_id: str,
    request_id: str,
    payload: DonorResponseIn,
    session: AsyncSession = Depends(get_db_session),
    principal: Principal = Depends(get_principal),
):
    if principal.subject != "development-user" and principal.subject != donor_id:
        raise HTTPException(status_code=403, detail="donor_id must match the authenticated user")
    try:
        match = await SqlAlchemyDonorStore(session).respond(donor_id, request_id, payload)
    except KeyError:
        raise HTTPException(status_code=403, detail="Donor is not eligible for this request") from None
    return DonorResponseOut(
        request_id=request_id,
        donor_id=donor_id,
        response=payload.response,
        responded_at=match.responded_at,
    )
