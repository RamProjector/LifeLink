package com.lifelink.app

import com.lifelink.app.domain.DonorMap
import com.lifelink.app.domain.DonorMapArea
import com.lifelink.app.feature.privacy.PrivacyUiState

/**
 * Local-only deterministic map data for instrumentation. These are invented test areas,
 * contain no donor identifiers, and are never written to or fetched from the live backend.
 */
internal object SyntheticDonorMapFixture {
    val state = PrivacyUiState(
        map = DonorMap(
            generatedAt = "2026-10-27T12:00:00Z",
            approximateOnly = true,
            areas = listOf(
                DonorMapArea(
                    areaLabel = "Synthetic Test Area A",
                    latitude = 14.5998,
                    longitude = 120.9840,
                    radiusMeters = 1_500,
                    bloodType = "O+",
                    availability = "Available",
                    freshnessAt = "2026-10-27T11:55:00Z",
                    freshnessAgeMinutes = 5,
                    isStale = false,
                ),
                DonorMapArea(
                    areaLabel = "Synthetic Test Area B",
                    latitude = 14.6021,
                    longitude = 120.9872,
                    radiusMeters = 1_800,
                    bloodType = "A+",
                    availability = "Unavailable",
                    freshnessAt = "2026-10-27T11:52:00Z",
                    freshnessAgeMinutes = 8,
                    isStale = false,
                ),
            ),
        ),
    )
}
