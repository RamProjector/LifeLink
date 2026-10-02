package com.lifelink.app.data.repository

import com.lifelink.app.data.remote.DonorAvailabilityToggleRequest
import com.lifelink.app.data.remote.DonorProfileMeRequest
import com.lifelink.app.data.remote.DonorProfileMeResponse
import com.lifelink.app.data.remote.LifeLinkApi
import com.lifelink.app.domain.BloodType
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.DonorProfileMe
import com.lifelink.app.domain.DonorProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Talks to the separate donor-profile endpoints (`/v1/donor-profile`).
 *
 * The account identity comes from the authenticated session, so this repository
 * never sends a user id: the server resolves it from the verified JWT subject.
 */
private const val HTTP_NOT_FOUND = 404

class DonorProfileRepositoryImpl(private val api: LifeLinkApi) : DonorProfileRepository {
    override suspend fun load(): DonorProfileMe? = withContext(Dispatchers.IO) {
        val response = api.getDonorProfileMe()
        if (response.code() == HTTP_NOT_FOUND) return@withContext null
        check(response.isSuccessful) { "Donor profile could not be loaded (${response.code()})." }
        checkNotNull(response.body()) { "The server returned an empty donor profile." }.toDomain()
    }

    override suspend fun save(profile: DonorProfileMe): DonorProfileMe = withContext(Dispatchers.IO) {
        val response = api.upsertDonorProfile(profile.toRequest())
        check(response.isSuccessful) {
            val detail = response.errorBody()?.string()?.takeIf { it.isNotBlank() }
            "Donor profile could not be saved (${response.code()})${detail?.let { ": $it" } ?: "."}"
        }
        checkNotNull(response.body()) { "The server returned an empty donor profile." }.toDomain()
    }

    override suspend fun setAvailability(availability: DonorAvailability): DonorProfileMe = withContext(Dispatchers.IO) {
        val response = api.toggleDonorProfileAvailability(DonorAvailabilityToggleRequest(availability.name.lowercase()))
        check(response.isSuccessful) { "Availability could not be updated (${response.code()})." }
        checkNotNull(response.body()) { "The server returned an empty donor profile." }.toDomain()
    }

    override suspend fun optOut() = withContext(Dispatchers.IO) {
        val response = api.deleteDonorProfile()
        check(response.isSuccessful || response.code() == HTTP_NOT_FOUND) {
            "Donor profile could not be removed (${response.code()})."
        }
    }
}

private fun DonorProfileMe.toRequest() = DonorProfileMeRequest(
    bloodType = bloodType?.label?.replace('\u2212', '-') ?: "UNKNOWN",
    latitude = latitude ?: 0.0,
    longitude = longitude ?: 0.0,
    area = area,
    serviceRadiusKm = serviceRadiusKm.toDouble(),
    availabilityStatus = availability.name.lowercase(),
    lastDonationDate = lastDonationDate,
    notificationsEnabled = notificationsEnabled,
    displayName = displayName.ifBlank { null },
    donorNote = donorNote,
    preferredContactMethod = preferredContactMethod,
    profileVisible = profileVisible,
)

private fun DonorProfileMeResponse.toDomain() = DonorProfileMe(
    userId = userId,
    donorId = donorId,
    bloodType = BloodType.values().firstOrNull { it.label.replace('\u2212', '-') == bloodType },
    latitude = latitude,
    longitude = longitude,
    area = area,
    serviceRadiusKm = serviceRadiusKm.toInt(),
    availability = runCatching { DonorAvailability.valueOf(availabilityStatus.uppercase()) }
        .getOrDefault(DonorAvailability.OFFLINE),
    lastDonationDate = lastDonationDate,
    verified = verified,
    notificationsEnabled = notificationsEnabled,
    displayName = displayName,
    donorNote = donorNote,
    preferredContactMethod = preferredContactMethod,
    profileVisible = profileVisible,
)
