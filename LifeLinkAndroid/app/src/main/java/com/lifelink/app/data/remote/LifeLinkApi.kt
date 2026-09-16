package com.lifelink.app.data.remote

import com.google.gson.annotations.SerializedName
import com.lifelink.app.domain.EmergencyRequestDraft
import retrofit2.Response
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.PATCH

interface LifeLinkApi {
    @POST("v1/emergency-requests")
    suspend fun submitEmergencyRequest(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: EmergencyRequestRequest
    ): Response<EmergencyRequestResponse>

    @POST("v1/emergency-requests/{requestId}/manual-broadcast")
    suspend fun sendManualBroadcast(@Path("requestId") requestId: String): Response<ManualBroadcastResponse>

    @GET("v1/emergency-requests/{requestId}")
    suspend fun getEmergencyRequest(@Path("requestId") requestId: String): Response<EmergencyRequestStatusResponse>

    @POST("v1/emergency-requests/{requestId}/cancel")
    suspend fun cancelEmergencyRequest(@Path("requestId") requestId: String): Response<RequestActionResponse>

    @POST("v1/emergency-requests/{requestId}/contact")
    suspend fun contactSelectedDonors(@Path("requestId") requestId: String, @Body request: ContactSelectedDonorsRequest): Response<ContactSelectedDonorsResponse>

    @PUT("v1/donors/{donorId}")
    suspend fun registerDonor(@Path("donorId") donorId: String, @Body profile: DonorProfileRequest): Response<DonorProfileResponse>

    @PATCH("v1/donors/{donorId}/availability")
    suspend fun updateDonorAvailability(@Path("donorId") donorId: String, @Body availability: DonorAvailabilityRequest): Response<DonorProfileResponse>

    @GET("v1/donors/{donorId}/requests")
    suspend fun donorRequests(@Path("donorId") donorId: String): Response<List<DonorRequestResponse>>

    @POST("v1/donors/{donorId}/requests/{requestId}/response")
    suspend fun respondToDonorRequest(@Path("donorId") donorId: String, @Path("requestId") requestId: String, @Body response: DonorResponseRequest): Response<DonorResponseResponse>
}

data class EmergencyRequestRequest(
    @SerializedName("requester_id") val requesterId: String,
    @SerializedName("blood_type") val bloodType: String,
    val units: Int,
    val urgency: String,
    @SerializedName("response_deadline") val responseDeadline: String,
    val location: LocationRequest,
    @SerializedName("contact_method") val contactMethod: String,
    val note: String,
    @SerializedName("genuine_request_confirmed") val genuineRequestConfirmed: Boolean,
    @SerializedName("sharing_consent_confirmed") val sharingConsentConfirmed: Boolean,
    @SerializedName("ai_matching_enabled") val aiMatchingEnabled: Boolean,
    @SerializedName("idempotency_key") val idempotencyKey: String
) {
    companion object {
        fun from(draft: EmergencyRequestDraft): EmergencyRequestRequest = EmergencyRequestRequest(
            requesterId = "demo-coordinator",
            bloodType = draft.bloodType?.label ?: "UNKNOWN",
            units = draft.units,
            urgency = draft.urgency.name.lowercase(),
            responseDeadline = draft.responseDeadline,
            location = LocationRequest(
                facilityId = draft.facility?.id.orEmpty(),
                facilityName = draft.facility?.name.orEmpty(),
                area = draft.facility?.area.orEmpty(),
                latitude = 14.6466,
                longitude = 121.0437,
                verified = draft.facility?.verified == true
            ),
            contactMethod = draft.contactMethod.name.lowercase(),
            note = draft.note,
            genuineRequestConfirmed = draft.genuineRequestConfirmed,
            sharingConsentConfirmed = draft.sharingConsentConfirmed,
            aiMatchingEnabled = draft.aiMatchingEnabled,
            idempotencyKey = draft.id
        )
    }
}

data class LocationRequest(
    @SerializedName("facility_id") val facilityId: String,
    @SerializedName("facility_name") val facilityName: String,
    val area: String,
    val latitude: Double,
    val longitude: Double,
    val verified: Boolean
)

data class EmergencyRequestResponse(
    @SerializedName("request_id") val requestId: String,
    val status: String,
    @SerializedName("notifications_created") val notificationsCreated: Int = 0,
    val reason: String? = null,
    val matches: List<DonorMatchResponse> = emptyList()
)

data class DonorMatchResponse(
    @SerializedName("donor_id") val donorId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("blood_type") val bloodType: String,
    @SerializedName("distance_km") val distanceKm: Double,
    @SerializedName("estimated_travel_minutes") val travelMinutes: Int,
    val score: Double,
    val explanation: MatchExplanationResponse? = null
)

data class MatchExplanationResponse(val factors: List<String> = emptyList())
data class ContactSelectedDonorsRequest(@SerializedName("donor_ids") val donorIds: List<String>)
data class ContactSelectedDonorsResponse(@SerializedName("request_id") val requestId: String, @SerializedName("donor_ids") val donorIds: List<String>, val status: String)

data class ManualBroadcastResponse(
    @SerializedName("request_id") val requestId: String,
    val status: String,
    val reason: String
)

data class EmergencyRequestStatusResponse(
    @SerializedName("request_id") val requestId: String,
    val status: String,
    @SerializedName("notifications_created") val notificationsCreated: Int = 0,
    @SerializedName("matches_responded") val matchesResponded: Int = 0,
    val reason: String? = null
)

data class RequestActionResponse(
    @SerializedName("request_id") val requestId: String,
    val status: String,
    val reason: String? = null
)

data class DonorProfileRequest(
    @SerializedName("donor_id") val donorId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("blood_type") val bloodType: String,
    val latitude: Double,
    val longitude: Double,
    @SerializedName("service_radius_km") val serviceRadiusKm: Double,
    val verified: Boolean
)

data class DonorAvailabilityRequest(val availability: String)
data class DonorProfileResponse(@SerializedName("donor_id") val donorId: String, val availability: String, @SerializedName("availability_updated_at") val availabilityUpdatedAt: String)
data class DonorRequestResponse(@SerializedName("request_id") val requestId: String, @SerializedName("blood_type") val bloodType: String, val units: Int, val urgency: String, @SerializedName("facility_name") val facilityName: String, val area: String, @SerializedName("distance_km") val distanceKm: Double, val status: String)
data class DonorResponseRequest(val response: String)
data class DonorResponseResponse(@SerializedName("request_id") val requestId: String, @SerializedName("donor_id") val donorId: String, val response: String)
