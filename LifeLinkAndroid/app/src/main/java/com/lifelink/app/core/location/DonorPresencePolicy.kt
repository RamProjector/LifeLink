package com.lifelink.app.core.location

/**
 * Presence may refresh only for a verified donor who explicitly chose availability.
 * Being foregrounded in the app never silently opts a donor into donation.
 */
internal fun shouldRefreshDonorPresence(verified: Boolean, availabilityStatus: String): Boolean =
    verified && availabilityStatus.equals("available", ignoreCase = true)
