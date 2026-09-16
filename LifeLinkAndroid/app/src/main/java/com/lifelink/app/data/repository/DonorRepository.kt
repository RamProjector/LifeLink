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

class DonorRepositoryImpl(private val dao: DonorDao, private val api: LifeLinkApi? = null) : DonorRepository {
    override fun observeProfile(): Flow<DonorProfile> = dao.observeProfile().map { it?.toDomain() ?: DonorProfile() }
    override fun observeRequests(): Flow<List<DonorRequest>> = dao.observeRequests().map { list -> list.map { it.toDomain() } }
    override suspend fun saveProfile(profile: DonorProfile) {
        withContext(Dispatchers.IO) {
        dao.upsertProfile(profile.toEntity())
        api?.registerDonor(
            profile.donorId,
            DonorProfileRequest(profile.donorId, profile.displayName, profile.bloodType?.label ?: "UNKNOWN", 14.6466, 121.0437, profile.serviceRadiusKm.toDouble(), profile.verified)
        )
        api?.updateDonorAvailability(profile.donorId, DonorAvailabilityRequest(profile.availability.name.lowercase()))
        }
    }
    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val remote = api ?: return@withContext
        val profile = dao.observeProfile().first()
        // The local profile is the source of identity; a later authenticated build
        // should obtain donor_id from the signed-in account instead.
        if (profile != null) {
            remote.donorRequests(profile.donorId).body().orEmpty().forEach { request ->
                dao.upsertRequest(DonorRequestEntity(request.requestId, request.bloodType, request.units, request.urgency, request.facilityName, request.area, request.distanceKm, request.status.takeUnless { it == "not_responded" }))
            }
        }
    }

    override suspend fun respond(requestId: String, response: DonorResponse): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val local = dao.observeProfile().first()
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

private fun DonorProfile.toEntity() = DonorProfileEntity(donorId, displayName, bloodType?.name, area, serviceRadiusKm, availability.name, verified)
private fun DonorProfileEntity.toDomain() = DonorProfile(donorId, displayName, bloodType?.let { runCatching { BloodType.valueOf(it) }.getOrNull() }, area, serviceRadiusKm, runCatching { DonorAvailability.valueOf(availability) }.getOrDefault(DonorAvailability.OFFLINE), verified)
private fun DonorRequestEntity.toDomain() = DonorRequest(requestId, bloodType, units, urgency, facilityName, area, distanceKm, response?.let { runCatching { DonorResponse.valueOf(it) }.getOrNull() })
