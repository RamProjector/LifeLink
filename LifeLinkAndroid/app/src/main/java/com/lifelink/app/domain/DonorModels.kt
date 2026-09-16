package com.lifelink.app.domain

import kotlinx.coroutines.flow.Flow

enum class DonorAvailability(val label: String) { AVAILABLE("Available"), PAUSED("Paused"), OFFLINE("Offline") }
enum class DonorResponse(val label: String) { ACCEPTED("Accepted"), DECLINED("Declined"), ARRIVED("Arrived") }

data class DonorProfile(
    val donorId: String = "local-donor",
    val displayName: String = "",
    val bloodType: BloodType? = null,
    val area: String = "",
    val serviceRadiusKm: Int = 10,
    val availability: DonorAvailability = DonorAvailability.OFFLINE,
    val verified: Boolean = false
)

data class DonorRequest(
    val requestId: String,
    val bloodType: String,
    val units: Int,
    val urgency: String,
    val facilityName: String,
    val area: String,
    val distanceKm: Double,
    val response: DonorResponse? = null
)

interface DonorRepository {
    fun observeProfile(): Flow<DonorProfile>
    fun observeRequests(): Flow<List<DonorRequest>>
    suspend fun saveProfile(profile: DonorProfile)
    suspend fun refresh()
    suspend fun respond(requestId: String, response: DonorResponse): Result<Unit>
}
