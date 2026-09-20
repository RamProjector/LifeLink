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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

interface LifeLinkApi {
    @PUT("v1/profile")
    suspend fun upsertProfile(@Body profile: ProfileRequest): Response<ProfileResponse>

    @GET("v1/profile")
    suspend fun getProfile(): Response<ProfileResponse>

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

    @POST("v1/emergency-requests/{requestId}/fulfill")
    suspend fun fulfillEmergencyRequest(@Path("requestId") requestId: String): Response<RequestActionResponse>

    @POST("v1/emergency-requests/{requestId}/contact")
    suspend fun contactSelectedDonors(@Path("requestId") requestId: String, @Body request: ContactSelectedDonorsRequest): Response<ContactSelectedDonorsResponse>

    @GET("v1/emergency-requests/{requestId}/contacts")
    suspend fun requesterContacts(@Path("requestId") requestId: String): Response<List<RequesterContactResponse>>

    @PATCH("v1/emergency-requests/{requestId}/contacts/{donorId}")
    suspend fun updateContactStatus(@Path("requestId") requestId: String, @Path("donorId") donorId: String, @Body request: ContactStatusUpdateRequest): Response<RequesterContactResponse>

    @POST("v1/emergency-requests/{requestId}/contacts/{donorId}/report")
    suspend fun reportContact(@Path("requestId") requestId: String, @Path("donorId") donorId: String, @Body request: ContactModerationRequest): Response<ContactModerationResponse>

    @POST("v1/emergency-requests/{requestId}/contacts/{donorId}/block")
    suspend fun blockContact(@Path("requestId") requestId: String, @Path("donorId") donorId: String): Response<ContactModerationResponse>

    @GET("v1/emergency-requests")
    suspend fun requestHistory(): Response<List<RequestHistoryResponse>>

    @PUT("v1/donors/{donorId}")
    suspend fun registerDonor(@Path("donorId") donorId: String, @Body profile: DonorProfileRequest): Response<DonorProfileResponse>

    @PATCH("v1/donors/{donorId}/availability")
    suspend fun updateDonorAvailability(@Path("donorId") donorId: String, @Body availability: DonorAvailabilityRequest): Response<DonorProfileResponse>

    @GET("v1/donors/{donorId}/requests")
    suspend fun donorRequests(@Path("donorId") donorId: String): Response<List<DonorRequestResponse>>

    @POST("v1/donors/{donorId}/requests/{requestId}/response")
    suspend fun respondToDonorRequest(@Path("donorId") donorId: String, @Path("requestId") requestId: String, @Body response: DonorResponseRequest): Response<DonorResponseResponse>
}

data class ProfileRequest(
    val role: String,
    @SerializedName("display_name") val displayName: String? = null
)

data class ProfileResponse(
    @SerializedName("user_id") val userId: String,
    val email: String,
    val role: String,
    @SerializedName("display_name") val displayName: String? = null
)

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
        fun from(draft: EmergencyRequestDraft, requesterId: String? = null): EmergencyRequestRequest = EmergencyRequestRequest(
            requesterId = requireNotNull(requesterId) { "An authenticated requester is required." },
            bloodType = draft.bloodType?.label ?: "UNKNOWN",
            units = draft.units,
            urgency = draft.urgency.name.lowercase(),
            responseDeadline = normalizeDeadline(draft),
            location = LocationRequest(
                facilityId = draft.facility?.id,
                facilityName = draft.facility?.name ?: "Requester location",
                area = draft.facility?.area ?: "Approximate area",
                latitude = requireNotNull(draft.requesterLatitude) { "Requester location is required." },
                longitude = requireNotNull(draft.requesterLongitude) { "Requester location is required." },
                precisionMeters = draft.locationPrecisionMeters,
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

private fun normalizeDeadline(draft: EmergencyRequestDraft): String {
    val value = draft.responseDeadline.trim()
    if (value.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))) return value
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    calendar.add(Calendar.MINUTE, if (draft.urgency.name == "CRITICAL") 90 else 24 * 60)
    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(calendar.time)
}

data class LocationRequest(
    @SerializedName("facility_id") val facilityId: String?,
    @SerializedName("facility_name") val facilityName: String,
    val area: String,
    val latitude: Double,
    val longitude: Double,
    @SerializedName("precision_meters") val precisionMeters: Int = 100,
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
data class RequesterContactResponse(
    @SerializedName("donor_id") val donorId: String,
    @SerializedName("display_name") val displayName: String,
    val status: String,
    @SerializedName("accepted_at") val acceptedAt: String? = null,
    @SerializedName("contact_email") val contactEmail: String? = null
)

data class ContactStatusUpdateRequest(val status: String)
data class ContactModerationRequest(val reason: String = "")
data class ContactModerationResponse(@SerializedName("request_id") val requestId: String, @SerializedName("donor_id") val donorId: String, val action: String)

data class RequestHistoryResponse(
    @SerializedName("request_id") val requestId: String,
    val status: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("blood_type") val bloodType: String,
    val units: Int,
    val urgency: String,
    @SerializedName("facility_name") val facilityName: String,
    val area: String,
    @SerializedName("notifications_created") val notificationsCreated: Int = 0,
    @SerializedName("matches_responded") val matchesResponded: Int = 0,
    @SerializedName("contact_statuses") val contactStatuses: List<String> = emptyList()
)

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
