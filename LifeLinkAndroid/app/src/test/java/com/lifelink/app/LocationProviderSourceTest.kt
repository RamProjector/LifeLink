package com.lifelink.app

import com.lifelink.app.core.location.normalizeLocationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationProviderSourceTest {
    @Test
    fun supported_android_provider_names_keep_their_bounded_categories() {
        assertEquals("fused", normalizeLocationProvider("fused"))
        assertEquals("gps", normalizeLocationProvider("gps"))
        assertEquals("network", normalizeLocationProvider("network"))
        assertEquals("passive", normalizeLocationProvider("passive"))
    }

    @Test
    fun provider_normalization_is_case_and_whitespace_tolerant() {
        assertEquals("gps", normalizeLocationProvider(" GPS "))
    }

    @Test
    fun unknown_or_missing_provider_never_escapes_the_allow_list() {
        assertEquals("unknown", normalizeLocationProvider(null))
        assertEquals("unknown", normalizeLocationProvider(""))
        assertEquals("unknown", normalizeLocationProvider("oem-custom-provider"))
    }
}
