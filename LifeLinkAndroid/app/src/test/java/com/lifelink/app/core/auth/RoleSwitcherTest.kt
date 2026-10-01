package com.lifelink.app.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleSwitcherTest {

    @Test
    fun togglesBetweenRolesInBothDirections() {
        assertEquals(UserRole.DONOR, RoleSwitcher.toggled(UserRole.REQUESTER))
        assertEquals(UserRole.REQUESTER, RoleSwitcher.toggled(UserRole.DONOR))
    }

    @Test
    fun togglingTwiceReturnsToTheOriginalRole() {
        val start = UserRole.REQUESTER
        assertEquals(start, RoleSwitcher.toggled(RoleSwitcher.toggled(start)))
    }

    @Test
    fun profileRoleMatchesBackendContract() {
        assertEquals("requester", RoleSwitcher.profileRole(UserRole.REQUESTER))
        assertEquals("donor", RoleSwitcher.profileRole(UserRole.DONOR))
    }

    @Test
    fun donorRoleAlwaysEnablesDonating() {
        val caps = RoleSwitcher.capabilities(UserRole.DONOR, hasDonorProfile = false)
        assertTrue(caps.canRequest)
        assertTrue(caps.canDonate)
    }

    @Test
    fun requesterKeepsDonorAccessOnlyWithADonorProfile() {
        val without = RoleSwitcher.capabilities(UserRole.REQUESTER, hasDonorProfile = false)
        assertTrue(without.canRequest)
        assertFalse(without.canDonate)

        val with = RoleSwitcher.capabilities(UserRole.REQUESTER, hasDonorProfile = true)
        assertTrue(with.canRequest)
        assertTrue(with.canDonate)
    }

    @Test
    fun onlyDonorRoleOpensTheDonorWorkspace() {
        assertTrue(RoleSwitcher.opensDonorWorkspace(UserRole.DONOR))
        assertFalse(RoleSwitcher.opensDonorWorkspace(UserRole.REQUESTER))
    }
}
