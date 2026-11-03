from __future__ import annotations

import pytest
from fastapi import HTTPException

from app import facility_api


def test_parse_overpass_facilities_normalizes_nodes_and_centers() -> None:
    result = facility_api._parse_response(
        {
            "osm3s": {"timestamp": "2026-11-04T00:00:00Z"},
            "elements": [
                {
                    "type": "node",
                    "id": 1,
                    "lat": 14.5995,
                    "lon": 120.9842,
                    "tags": {"name": "General Hospital", "amenity": "hospital", "addr:city": "Manila"},
                },
                {
                    "type": "way",
                    "id": 2,
                    "center": {"lat": 14.61, "lon": 120.99},
                    "tags": {"name": "Community Clinic", "healthcare": "clinic"},
                },
                {"type": "way", "id": 3, "tags": {"name": "Missing geometry", "amenity": "hospital"}},
            ],
        },
        14.5995,
        120.9842,
        10.0,
        20,
    )

    assert [facility.name for facility in result.facilities] == ["General Hospital", "Community Clinic"]
    assert result.facilities[0].category == "hospital"
    assert result.facilities[0].area == "Manila"
    assert result.facilities[0].source_updated_at == "2026-11-04T00:00:00Z"
    assert result.facilities[0].distance_km == 0.0


def test_parse_overpass_facilities_filters_outside_radius_and_limits() -> None:
    result = facility_api._parse_response(
        {
            "elements": [
                {"type": "node", "id": 1, "lat": 14.5995, "lon": 120.9842, "tags": {"name": "Near"}},
                {"type": "node", "id": 2, "lat": 15.5, "lon": 121.5, "tags": {"name": "Far"}},
            ]
        },
        14.5995,
        120.9842,
        1.0,
        1,
    )

    assert [facility.name for facility in result.facilities] == ["Near"]


@pytest.mark.parametrize(
    ("latitude", "longitude", "radius_km", "limit"),
    [(91.0, 120.0, 10.0, 20), (14.0, 181.0, 10.0, 20), (14.0, 120.0, 0.0, 20), (14.0, 120.0, 10.0, 51)],
)
def test_validate_nearby_query_rejects_unsafe_bounds(latitude: float, longitude: float, radius_km: float, limit: int) -> None:
    with pytest.raises(HTTPException) as error:
        facility_api.validate_nearby_query(latitude, longitude, radius_km, limit)
    assert error.value.status_code == 422


def test_build_query_is_bounded_to_hospitals_and_clinics() -> None:
    query = facility_api._build_query(14.5995, 120.9842, 10.0)
    assert "nwr(around:10000,14.5995,120.9842)[amenity=hospital]" in query
    assert "[healthcare=clinic]" in query
    assert "out center tags" in query
