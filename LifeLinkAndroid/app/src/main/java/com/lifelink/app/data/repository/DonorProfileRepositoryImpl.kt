package com.lifelink.app.data.repository

import com.lifelink.app.data.remote.DonorAvailabilityToggleRequest
import com.lifelink.app.data.remote.DonorPresenceRequest
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
private const val MIN_LOCATION_PRECISION_METERS = 1
private const val MAX_LOCATION_PRECISION_METERS = 10_000

class DonorProfileRepositoryImpl(private val api: LifeLinkApi) : DonorProfileRepository {
    override suspend fun load(): DonorProfileMe? = withContext(Dispatchers.IO) {
        val response = api.getDonorProfileMe()
        if (response.code() == HTTP_NOT_FOUND) return@withContext null
        check(response.isSuccessful) { "Donor profile could not be loaded (${response.code()})." }
        checkNotNull(response.body()) { "The server returned an empty donor profile." }.toDomain()
    }

    override suspend fun save(profile: DonorProfileMe): DonorProfileMe = withContext(Dispatchers.IO) {
        val latitude = checkNotNull(profile.latitude) { "Capture your current GPS location before saving." }
        val longitude = checkNotNull(profile.longitude) { "Capture your current GPS location before saving." }
        val response = api.upsertDonorProfile(profile.toRequest())
        check(response.isSuccessful) {
            // Do not surface arbitrary backend detail text: it may contain implementation
            // details or sensitive data. Keep the status code for diagnostics and safe UI mapping.
            "Donor profile could not be saved (${response.code()})."
        }
        val saved = checkNotNull(response.body()) { "The server returned an empty donor profile." }.toDomain()
        val presence = api.recordDonorPresence(
            DonorPresenceRequest(
                latitude = latitude,
                longitude = longitude,
                precisionMeters = profile.locationPrecisionMeters.coerceIn(MIN_LOCATION_PRECISION_METERS, MAX_LOCATION_PRECISION_METERS),
            ),
        )
        check(presence.isSuccessful) {
            // The profile upsert already succeeded; make this partial-success state explicit so
            // users know a retry is for location confirmation, not a failed profile write.
            "Your donor profile was saved, but your current location could not be confirmed " +
                "(${presence.code()}). Please retry to confirm your location."
        }
        saved.copy(locationPrecisionMeters = profile.locationPrecisionMeters)
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
