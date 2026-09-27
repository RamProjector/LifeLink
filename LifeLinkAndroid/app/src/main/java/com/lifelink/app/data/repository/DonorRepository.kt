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
import kotlinx.coroutines.CancellationException
import java.util.Locale
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

    override fun observeProfile(): Flow<DonorProfile> {
        val ownerId = donorId()
        return dao.observeProfile(ownerId).map { it?.toDomain() ?: DonorProfile(donorId = ownerId) }
    }
    override fun observeRequests(): Flow<List<DonorRequest>> = dao.observeRequests(donorId()).map { list -> list.map { it.toDomain() } }

    override suspend fun saveProfile(profile: DonorProfile) {
        withContext(Dispatchers.IO) {
            val ownerId = donorId()
            val effectiveProfile = profile.copy(donorId = ownerId)
            require(effectiveProfile.displayName.trim().length >= 2) { "Add a display name before saving your donor profile." }
            require(effectiveProfile.bloodType != null) { "Select your blood type before saving your donor profile." }
            require(effectiveProfile.latitude != null && effectiveProfile.longitude != null) { "Capture your approximate location before saving your donor profile." }
            require(effectiveProfile.serviceRadiusKm in 1..100) { "Service radius must be between 1 and 100 km." }
            api?.let { remote ->
                val response = remote.registerDonor(ownerId, DonorProfileRequest(
                    ownerId, effectiveProfile.displayName.trim(), effectiveProfile.bloodType.label.replace('−', '-'),
                    effectiveProfile.latitude, effectiveProfile.longitude, effectiveProfile.serviceRadiusKm.toDouble(), effectiveProfile.verified,
                    effectiveProfile.donorNote.trim(), effectiveProfile.preferredContactMethod,
                    effectiveProfile.pauseReason?.trim()?.takeIf { it.isNotEmpty() }, effectiveProfile.profileVisible
                ))
                check(response.isSuccessful) {
                    val detail = response.errorBody()?.string()?.takeIf { it.isNotBlank() }
                    "Profile could not be saved on the server (${response.code()})${detail?.let { ": $it" } ?: "."}"
                }
                val availability = remote.updateDonorAvailability(ownerId, DonorAvailabilityRequest(effectiveProfile.availability.name.lowercase()))
                check(availability.isSuccessful) {
                    val detail = availability.errorBody()?.string()?.takeIf { it.isNotBlank() }
                    "Availability could not be updated (${availability.code()})${detail?.let { ": $it" } ?: "."}"
                }
            }
            dao.upsertProfile(effectiveProfile.toEntity())
        }
    }

    override suspend fun setAvailability(availability: DonorAvailability) {
        withContext(Dispatchers.IO) {
            val ownerId = donorId()
            val local = dao.observeProfile(ownerId).first()
            val current = local?.toDomain() ?: DonorProfile(donorId = ownerId)
            require(current.isSetupComplete) { "Complete your donor profile before choosing availability." }
            api?.let { remote ->
                val response = remote.updateDonorAvailability(ownerId, DonorAvailabilityRequest(availability.name.lowercase()))
                check(response.isSuccessful) { "Availability could not be updated (${response.code()})." }
            }
            dao.upsertProfile(current.copy(availability = availability).toEntity())
        }
    }

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val remote = api ?: return@withContext
        val ownerId = donorId()
        val profileResponse = remote.getDonorProfile(ownerId)
        if (profileResponse.code() == 404) {
            dao.clearAll(ownerId)
            return@withContext
        }
        check(profileResponse.isSuccessful) { "Donor profile could not be loaded (${profileResponse.code()})." }
        val restored = checkNotNull(profileResponse.body()) { "The server returned an empty donor profile." }
        restored.let { response ->
            val bloodType = BloodType.values().firstOrNull { it.label.replace('−', '-') == response.bloodType }
            dao.upsertProfile(DonorProfile(
                donorId = ownerId,
                displayName = response.displayName,
                bloodType = bloodType,
                serviceRadiusKm = response.serviceRadiusKm.toInt(),
                availability = runCatching { DonorAvailability.valueOf(response.availability.uppercase()) }.getOrDefault(DonorAvailability.OFFLINE),
                verified = response.verified,
                latitude = response.latitude,
                longitude = response.longitude,
                donorNote = response.donorNote,
                preferredContactMethod = response.preferredContactMethod,
                pauseReason = response.pauseReason,
                profileVisible = response.profileVisible
            ).toEntity())
        }
        val profile = dao.observeProfile(ownerId).first()
        if (profile != null && profile.toDomain().isSetupComplete) {
            val response = remote.donorRequests(ownerId)
            check(response.isSuccessful) { "Donor requests could not be loaded (${response.code()})." }
            val requests = checkNotNull(response.body()) { "The server returned an empty donor inbox response." }
            dao.replaceRequests(ownerId, requests.map { request ->
                DonorRequestEntity(ownerId, request.requestId, request.bloodType, request.units, request.urgency, request.facilityName, request.area, request.distanceKm, request.status.takeUnless { it.equals("not_responded", ignoreCase = true) })
            })
        } else {
            dao.clearRequests(ownerId)
        }
    }

    override suspend fun respond(requestId: String, response: DonorResponse): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ownerId = donorId()
            val local = dao.observeProfile(ownerId).first()
            val remote = api
            check(local?.toDomain()?.isSetupComplete == true) { "Complete your donor profile before responding." }
            if (remote != null) {
                val result = remote.respondToDonorRequest(ownerId, requestId, DonorResponseRequest(response.name.lowercase()))
                check(result.isSuccessful) { "The server rejected the response" }
            }
            dao.updateResponse(ownerId, requestId, response.name)
        }.onFailure { if (it is CancellationException) throw it }
    }

    suspend fun seedDemoRequests() {
        val ownerId = donorId()
        dao.upsertRequest(DonorRequestEntity(ownerId, "req-demo-001", "O−", 1, "critical", "St. Luke’s Medical Center", "Quezon City", 4.2, null))
        dao.upsertRequest(DonorRequestEntity(ownerId, "req-demo-002", "A+", 2, "urgent", "Philippine General Hospital", "Manila", 8.7, null))
    }
}

private fun DonorProfile.toEntity() = DonorProfileEntity(donorId, displayName, bloodType?.name, area, serviceRadiusKm, availability.name, verified, latitude, longitude, locationPrecisionMeters, donorNote, preferredContactMethod, pauseReason, profileVisible)
private fun DonorProfileEntity.toDomain() = DonorProfile(donorId, displayName, bloodType?.let { runCatching { BloodType.valueOf(it) }.getOrNull() }, area, serviceRadiusKm, runCatching { DonorAvailability.valueOf(availability) }.getOrDefault(DonorAvailability.OFFLINE), verified, latitude, longitude, locationPrecisionMeters, donorNote, preferredContactMethod, pauseReason, profileVisible)
private fun DonorRequestEntity.toDomain() = DonorRequest(requestId, bloodType, units, urgency, facilityName, area, distanceKm, response?.let { runCatching { DonorResponse.valueOf(it.uppercase(Locale.ROOT)) }.getOrNull() })
