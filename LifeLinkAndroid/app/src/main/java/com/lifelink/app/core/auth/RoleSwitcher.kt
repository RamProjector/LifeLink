package com.lifelink.app.core.auth

/**
 * Pure role-switching rules shared by the UI and its unit tests.
 *
 * Switching roles must swap the whole tab set and its state in both directions,
 * so the meaning of "what does this role allow" lives here instead of being
 * scattered across composables.
 */
object RoleSwitcher {

    /** The role the switcher moves to when the user taps it. */
    fun toggled(current: UserRole): UserRole =
        if (current == UserRole.DONOR) UserRole.REQUESTER else UserRole.DONOR

    /** The profile `role` string the backend expects for a role. */
    fun profileRole(role: UserRole): String = role.name.lowercase()

    /**
     * Capability flags to persist with a role. A requester keeps donor access if
     * they already have a donor profile; a donor can always request as well.
     */
    fun capabilities(role: UserRole, hasDonorProfile: Boolean): RoleCapabilities =
        when (role) {
            UserRole.REQUESTER -> RoleCapabilities(canRequest = true, canDonate = hasDonorProfile)
            UserRole.DONOR -> RoleCapabilities(canRequest = true, canDonate = true)
        }

    /** Whether the donor workspace should be the landing surface for a role. */
    fun opensDonorWorkspace(role: UserRole): Boolean = role == UserRole.DONOR
}

data class RoleCapabilities(val canRequest: Boolean, val canDonate: Boolean)
