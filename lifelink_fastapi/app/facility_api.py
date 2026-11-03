from __future__ import annotations

import asyncio
import math
import os
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

import httpx
from fastapi import HTTPException
from pydantic import BaseModel

DEFAULT_OVERPASS_URL = "https://overpass-api.de/api/interpreter"
MAX_RADIUS_KM = 25.0
CACHE_TTL_SECONDS = 600.0


class NearbyFacilityOut(BaseModel):
    id: str
    name: str
    category: str
    latitude: float
    longitude: float
    distance_km: float
    area: str | None = None
    phone: str | None = None
    website: str | None = None
    source: str = "OpenStreetMap"
    source_attribution: str = "© OpenStreetMap contributors"
    source_updated_at: str | None = None


class NearbyFacilitiesOut(BaseModel):
    facilities: list[NearbyFacilityOut]
    latitude: float
    longitude: float
    radius_km: float
    source: str = "OpenStreetMap via Overpass API"
    source_attribution: str = "© OpenStreetMap contributors"
    retrieved_at: str
    stale: bool = False


@dataclass(frozen=True)
class _CacheEntry:
    expires_at: float
    response: NearbyFacilitiesOut


_cache: dict[tuple[float, float, float, int], _CacheEntry] = {}
_cache_lock = asyncio.Lock()


def _cache_key(latitude: float, longitude: float, radius_km: float, limit: int) -> tuple[float, float, float, int]:
    # Round the lookup key so repeated requests do not create a precise location
    # trail in process memory and nearby users share the same provider query.
    return (round(latitude, 3), round(longitude, 3), round(radius_km, 1), limit)


def _distance_km(latitude: float, longitude: float, other_latitude: float, other_longitude: float) -> float:
    earth_radius_km = 6371.0088
    lat1, lat2 = math.radians(latitude), math.radians(other_latitude)
    d_lat = lat2 - lat1
    d_lon = math.radians(other_longitude - longitude)
    value = math.sin(d_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(d_lon / 2) ** 2
    return earth_radius_km * 2 * math.asin(math.sqrt(value))


def _display_name(tags: dict[str, Any], element_id: str) -> str:
    return str(tags.get("name") or tags.get("name:en") or tags.get("official_name") or f"Health facility {element_id}")[:200]


def _category(tags: dict[str, Any]) -> str:
    value = str(tags.get("healthcare") or tags.get("amenity") or "healthcare").lower()
    return "hospital" if value == "hospital" else "clinic" if value in {"clinic", "doctors", "health_centre"} else value


def _element_coordinates(element: dict[str, Any]) -> tuple[float, float] | None:
    if isinstance(element.get("lat"), (int, float)) and isinstance(element.get("lon"), (int, float)):
        return float(element["lat"]), float(element["lon"])
    center = element.get("center") or {}
    if isinstance(center.get("lat"), (int, float)) and isinstance(center.get("lon"), (int, float)):
        return float(center["lat"]), float(center["lon"])
    return None


def _build_query(latitude: float, longitude: float, radius_km: float) -> str:
    radius_m = round(radius_km * 1000)
    return f"""[out:json][timeout:15];
(
  nwr(around:{radius_m},{latitude},{longitude})[amenity=hospital];
  nwr(around:{radius_m},{latitude},{longitude})[healthcare=hospital];
  nwr(around:{radius_m},{latitude},{longitude})[healthcare=clinic];
);
out center tags;"""


def _parse_response(
    payload: dict[str, Any],
    latitude: float,
    longitude: float,
    radius_km: float,
    limit: int,
) -> NearbyFacilitiesOut:
    retrieved_at = datetime.now(timezone.utc).isoformat()
    facilities: list[NearbyFacilityOut] = []
    seen: set[tuple[str, str]] = set()
    for element in payload.get("elements", []):
        tags = element.get("tags") or {}
        coordinates = _element_coordinates(element)
        if not coordinates:
            continue
        item_latitude, item_longitude = coordinates
        distance = _distance_km(latitude, longitude, item_latitude, item_longitude)
        if distance > radius_km:
            continue
        element_id = f"osm-{element.get('type', 'facility')}-{element.get('id', 'unknown')}"
        dedupe_key = (_display_name(tags, element_id).casefold(), f"{item_latitude:.5f},{item_longitude:.5f}")
        if dedupe_key in seen:
            continue
        seen.add(dedupe_key)
        facilities.append(
            NearbyFacilityOut(
                id=element_id,
                name=_display_name(tags, element_id),
                category=_category(tags),
                latitude=item_latitude,
                longitude=item_longitude,
                distance_km=round(distance, 2),
                area=str(tags.get("addr:city") or tags.get("addr:municipality") or tags.get("addr:province") or "") or None,
                phone=str(tags.get("phone") or tags.get("contact:phone") or "") or None,
                website=str(tags.get("website") or tags.get("contact:website") or "") or None,
                source_updated_at=payload.get("osm3s", {}).get("timestamp"),
            )
        )
    facilities.sort(key=lambda item: (item.distance_km, item.name.casefold()))
    return NearbyFacilitiesOut(
        facilities=facilities[:limit],
        latitude=latitude,
        longitude=longitude,
        radius_km=radius_km,
        retrieved_at=retrieved_at,
    )


async def nearby_facilities(latitude: float, longitude: float, radius_km: float, limit: int) -> NearbyFacilitiesOut:
    key = _cache_key(latitude, longitude, radius_km, limit)
    now = time.monotonic()
    async with _cache_lock:
        cached = _cache.get(key)
        if cached and cached.expires_at > now:
            return cached.response
    endpoint = os.getenv("LIFELINK_OVERPASS_URL", DEFAULT_OVERPASS_URL)
    query = _build_query(latitude, longitude, radius_km)
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(20.0, connect=5.0), headers={"User-Agent": "LifeLink/1.0 (health-facility-search)"}) as client:
            response = await client.post(endpoint, data={"data": query})
            response.raise_for_status()
            result = _parse_response(response.json(), latitude, longitude, radius_km, limit)
    except (httpx.HTTPError, ValueError) as exc:
        raise HTTPException(status_code=503, detail="Nearby health facilities are temporarily unavailable. Try again shortly.") from exc
    async with _cache_lock:
        _cache[key] = _CacheEntry(time.monotonic() + CACHE_TTL_SECONDS, result)
    return result


def validate_nearby_query(latitude: float, longitude: float, radius_km: float, limit: int) -> None:
    if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
        raise HTTPException(status_code=422, detail="A valid latitude and longitude are required")
    if not (0 < radius_km <= MAX_RADIUS_KM):
        raise HTTPException(status_code=422, detail=f"radius_km must be greater than 0 and no more than {MAX_RADIUS_KM:g}")
    if not (1 <= limit <= 50):
        raise HTTPException(status_code=422, detail="limit must be between 1 and 50")
