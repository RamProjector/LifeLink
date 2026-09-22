package com.lifelink.app.data.repository

import com.lifelink.app.data.local.DonorDao
import com.lifelink.app.data.local.DonorProfileEntity
import com.lifelink.app.data.local.DonorRequestEntity
import com.lifelink.app.domain.BloodType
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.DonorProfile
import com.lifelink.app.domain.DonorRequest
import com.lifelink.app.domain.DonorRepository
import com.lifelink.app.domain.DonorResponse
import com.lifelink.app.data.remote.DonorAvailabilityRequest
import com.lifelink.app.data.remote.DonorProfileRequest
import com.lifelink.app.data.remote.DonorResponseRequest
import com.lifelink.app.data.remote.LifeLinkApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class DonorRepositoryImpl(
    private val dao: DonorDao,
    private val api: LifeLinkApi? = null,
    private val donorIdProvider: () -> String? = { null }
) : DonorRepository {
    private fun donorId(): String = donorIdProvider()?.takeIf { it.isNotBlank() } ?: error("Sign in before using donor mode.")

    override fun observeProfile(): Flow<DonorProfile> = dao.observeProfile(donorId()).map { it?.toDomain() ?: DonorProfile(donorId = donorId()) }
    override fun observeRequests(): Flow<List<DonorRequest>> = dao.observeRequests().map { list -> list.map { it.toDomain() } }
    override suspend fun saveProfile(profile: DonorProfile) {
        withContext(Dispatchers.IO) {
        val ownerId = donorId()
        val effectiveProfile = profile.copy(donorId = ownerId)
        require(effectiveProfile.displayName.trim().length >= 2) { "Add a display name before saving your donor profile." }
        require(effectiveProfile.bloodType != null) { "Select your blood type before saving your donor profile." }
        require(effectiveProfile.latitude != null && effectiveProfile.longitude != null) { "Capture your approximate location before saving your donor profile." }
        require(effectiveProfile.serviceRadiusKm in 1..100) { "Service radius must be between 1 and 100 km." }
        api?.let { remote ->
            val response = remote.registerDonor(
                ownerId, DonorProfileRequest(
                ownerId, effectiveProfile.displayName.trim(), effectiveProfile.bloodType?.label?.replace('−', '-') ?: "UNKNOWN",
                effectiveProfile.latitude, effectiveProfile.longitude, effectiveProfile.serviceRadiusKm.toDouble(), effectiveProfile.verified,
                effectiveProfile.donorNote.trim(), effectiveProfile.preferredContactMethod,
                effectiveProfile.pauseReason?.trim()?.takeIf { it.isNotEmpty() }, effectiveProfile.profileVisible
                )
            )
            check(response.isSuccessful) { "Profile could not be saved on the server (${response.code()})." }
            val availability = remote.updateDonorAvailability(ownerId, DonorAvailabilityRequest(effectiveProfile.availability.name.lowercase()))
            check(availability.isSuccessful) { "Availability could not be updated (${availability.code()})." }
        }
        dao.upsertProfile(effectiveProfile.toEntity())
        }
    }
    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val remote = api ?: return@withContext
        val profile = dao.observeProfile(donorId()).first()
        if (profile != null) {
            remote.donorRequests(profile.donorId).body().orEmpty().forEach { request ->
                dao.upsertRequest(DonorRequestEntity(request.requestId, request.bloodType, request.units, request.urgency, request.facilityName, request.area, request.distanceKm, request.status.takeUnless { it == "not_responded" }))
            }
        }
    }

    override suspend fun respond(requestId: String, response: DonorResponse): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val local = dao.observeProfile(donorId()).first()
            val remote = api
            if (local != null && remote != null) {
                val result = remote.respondToDonorRequest(local.donorId, requestId, DonorResponseRequest(response.name.lowercase()))
                check(result.isSuccessful) { "The server rejected the response" }
            }
            dao.updateResponse(requestId, response.name)
        }
    }

    suspend fun seedDemoRequests() {
        dao.upsertRequest(DonorRequestEntity("req-demo-001", "O−", 1, "critical", "St. Luke’s Medical Center", "Quezon City", 4.2, null))
        dao.upsertRequest(DonorRequestEntity("req-demo-002", "A+", 2, "urgent", "Philippine General Hospital", "Manila", 8.7, null))
    }
}

private fun DonorProfile.toEntity() = DonorProfileEntity(donorId, displayName, bloodType?.name, area, serviceRadiusKm, availability.name, verified, latitude, longitude, locationPrecisionMeters, donorNote, preferredContactMethod, pauseReason, profileVisible)
private fun DonorProfileEntity.toDomain() = DonorProfile(donorId, displayName, bloodType?.let { runCatching { BloodType.valueOf(it) }.getOrNull() }, area, serviceRadiusKm, runCatching { DonorAvailability.valueOf(availability) }.getOrDefault(DonorAvailability.OFFLINE), verified, latitude, longitude, locationPrecisionMeters, donorNote, preferredContactMethod, pauseReason, profileVisible)
private fun DonorRequestEntity.toDomain() = DonorRequest(requestId, bloodType, units, urgency, facilityName, area, distanceKm, response?.let { runCatching { DonorResponse.valueOf(it) }.getOrNull() })
