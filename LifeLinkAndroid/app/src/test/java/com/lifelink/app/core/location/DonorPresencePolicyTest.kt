package com.lifelink.app.core.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DonorPresencePolicyTest {
    @Test
    fun refreshesOnlyVerifiedDonorsWhoExplicitlyRemainAvailable() {
        assertTrue(shouldRefreshDonorPresence(verified = true, availabilityStatus = "available"))
        assertTrue(shouldRefreshDonorPresence(verified = true, availabilityStatus = "AVAILABLE"))
        assertFalse(shouldRefreshDonorPresence(verified = false, availabilityStatus = "available"))
        assertFalse(shouldRefreshDonorPresence(verified = true, availabilityStatus = "paused"))
        assertFalse(shouldRefreshDonorPresence(verified = true, availabilityStatus = "offline"))
    }
}
