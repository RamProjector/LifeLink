package com.lifelink.app.core.auth

/**
 * Pure role-switching rules shared by the UI and its unit tests.
 *
 * Role changes update account capabilities and which Home content is shown;
 * the app keeps the same bottom-navigation destinations for both modes.
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

    /** Whether the Home destination should render donor controls for this role. */
    fun showsDonorHome(role: UserRole): Boolean = role == UserRole.DONOR
}

data class RoleCapabilities(val canRequest: Boolean, val canDonate: Boolean)
