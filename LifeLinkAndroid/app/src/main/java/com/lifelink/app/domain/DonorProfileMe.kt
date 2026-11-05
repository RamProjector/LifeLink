package com.lifelink.app.domain

/**
 * The current user's own donor profile, decoupled from account creation.
 *
 * A user creates a normal account first and then opts in to donating. [verified]
 * is server-controlled: a client can never mark itself verified, and matching
 * only ever considers a profile that is both [verified] and available.
 */
data class DonorProfileMe(
    val userId: String = "",
    val donorId: String = "",
    val bloodType: BloodType? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationPrecisionMeters: Int = 1000,
    val area: String = "",
    val serviceRadiusKm: Int = 15,
    val availability: DonorAvailability = DonorAvailability.OFFLINE,
    val lastDonationDate: String? = null,
    val verified: Boolean = false,
    val matchable: Boolean = false,
    val matchabilityReason: String = "Pending server verification",
    val notificationsEnabled: Boolean = true,
    val displayName: String = "",
    val donorNote: String = "",
    val preferredContactMethod: String = "in_app",
    val profileVisible: Boolean = true,
) {
    /** Enough of the profile is filled in to be saved. */
    val isSetupComplete: Boolean
        get() = bloodType != null && latitude != null && longitude != null && serviceRadiusKm in 1..500

    /** Server-owned result; the client does not decide whether this profile can match. */
    val isMatchable: Boolean
        get() = matchable
}

/** Reads and writes the current user's donor profile through the API. */
interface DonorProfileRepository {
    /** Returns the profile, or null when the user has not opted in yet (404). */
    suspend fun load(): DonorProfileMe?

    suspend fun save(profile: DonorProfileMe): DonorProfileMe

    suspend fun setAvailability(availability: DonorAvailability): DonorProfileMe

    /** Opt out: removes the profile and stops the donor from being matched. */
    suspend fun optOut()
}
