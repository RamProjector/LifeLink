package com.lifelink.app.data.remote

import com.google.gson.annotations.SerializedName
import com.lifelink.app.domain.EmergencyRequestDraft
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.PATCH
import retrofit2.http.DELETE
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

interface LifeLinkApi {
    @GET("health")
    suspend fun health(): Response<Map<String, String>>

    @PUT("v1/profile")
    suspend fun upsertProfile(@Body profile: ProfileRequest): Response<ProfileResponse>

    @GET("v1/profile")
    suspend fun getProfile(): Response<ProfileResponse>

    @PUT("v1/push-token")
    suspend fun registerPushToken(@Body token: PushTokenRequest): Response<Unit>

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
    suspend fun contactSelectedDonors(@Path("requestId") requestId: String, @Body request: ContactSelectedDonorsRequest): Response<ResponseBody>

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

    @GET("v1/donors/{donorId}")
    suspend fun getDonorProfile(@Path("donorId") donorId: String): Response<DonorProfileResponse>

    @PATCH("v1/donors/{donorId}/availability")
    suspend fun updateDonorAvailability(@Path("donorId") donorId: String, @Body availability: DonorAvailabilityRequest): Response<DonorProfileResponse>

    @GET("v1/donors/{donorId}/requests")
    suspend fun donorRequests(@Path("donorId") donorId: String): Response<List<DonorRequestResponse>>

    @POST("v1/donors/{donorId}/requests/{requestId}/response")
    suspend fun respondToDonorRequest(@Path("donorId") donorId: String, @Path("requestId") requestId: String, @Body response: DonorResponseRequest): Response<DonorResponseResponse>

    @GET("v1/donor-map")
    suspend fun donorMap(): Response<DonorMapResponse>

    @PUT("v1/donors/{donorId}/map-visibility")
    suspend fun setDonorMapVisibility(@Path("donorId") donorId: String, @Body request: DonorMapVisibilityRequest): Response<DonorMapVisibilityResponse>

    @POST("v1/emergency-requests/{requestId}/donors/{donorId}/location-share")
    suspend fun activateLocationShare(@Path("requestId") requestId: String, @Path("donorId") donorId: String): Response<LocationShareResponse>

    @DELETE("v1/emergency-requests/{requestId}/donors/{donorId}/location-share")
    suspend fun revokeLocationShare(@Path("requestId") requestId: String, @Path("donorId") donorId: String): Response<LocationShareResponse>

    @GET("v1/emergency-requests/{requestId}/donors/{donorId}/location")
    suspend fun matchedDonorLocation(@Path("requestId") requestId: String, @Path("donorId") donorId: String): Response<ExactLocationResponse>

    @POST("v1/emergency-requests/{requestId}/donors/{donorId}/conversation")
    suspend fun openConversation(@Path("requestId") requestId: String, @Path("donorId") donorId: String): Response<ConversationResponse>

    @GET("v1/conversations")
    suspend fun listConversations(): Response<List<ConversationResponse>>

    @GET("v1/conversations/{conversationId}/messages")
    suspend fun listMessages(@Path("conversationId") conversationId: String): Response<List<MessageResponse>>

    @POST("v1/conversations/{conversationId}/messages")
    suspend fun sendMessage(@Path("conversationId") conversationId: String, @Body request: MessageRequest): Response<MessageResponse>

    @GET("v1/conversations/{conversationId}/contact-shares")
    suspend fun listContactShares(@Path("conversationId") conversationId: String): Response<List<ContactShareResponse>>

    @POST("v1/conversations/{conversationId}/contact-shares")
    suspend fun shareContactDetails(@Path("conversationId") conversationId: String, @Body request: ContactShareRequest): Response<ContactShareResponse>

    @POST("v1/conversations/{conversationId}/report")
    suspend fun reportConversation(@Path("conversationId") conversationId: String, @Body request: ConversationModerationRequest): Response<ConversationModerationResponse>

    @POST("v1/conversations/{conversationId}/block")
    suspend fun blockConversation(@Path("conversationId") conversationId: String, @Body request: ConversationModerationRequest): Response<ConversationModerationResponse>
}

data class ProfileRequest(
    val role: String,
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("can_request") val canRequest: Boolean = true,
    @SerializedName("can_donate") val canDonate: Boolean = false
)

data class ProfileResponse(
    @SerializedName("user_id") val userId: String,
    val email: String,
    val role: String,
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("can_request") val canRequest: Boolean = true,
    @SerializedName("can_donate") val canDonate: Boolean = false
)

data class PushTokenRequest(
    val token: String,
    val platform: String = "android"
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
            // UI labels use a typographic minus (−); the API enum uses ASCII hyphen (-).
            bloodType = draft.bloodType?.label?.replace('−', '-') ?: "UNKNOWN",
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
    @SerializedName("contact_shared_at") val contactSharedAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
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
    val reason: String? = null,
    val matches: List<DonorMatchResponse> = emptyList()
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
    val verified: Boolean,
    @SerializedName("donor_note") val donorNote: String = "",
    @SerializedName("preferred_contact_method") val preferredContactMethod: String = "in_app",
    @SerializedName("pause_reason") val pauseReason: String? = null,
    @SerializedName("profile_visible") val profileVisible: Boolean = true
)

data class DonorAvailabilityRequest(val availability: String)
data class DonorProfileResponse(
    @SerializedName("donor_id") val donorId: String,
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("blood_type") val bloodType: String = "UNKNOWN",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    @SerializedName("service_radius_km") val serviceRadiusKm: Double = 10.0,
    val verified: Boolean = false,
    val availability: String,
    @SerializedName("availability_updated_at") val availabilityUpdatedAt: String,
    @SerializedName("donor_note") val donorNote: String = "",
    @SerializedName("preferred_contact_method") val preferredContactMethod: String = "in_app",
    @SerializedName("pause_reason") val pauseReason: String? = null,
    @SerializedName("profile_visible") val profileVisible: Boolean = true
)
data class DonorRequestResponse(@SerializedName("request_id") val requestId: String, @SerializedName("blood_type") val bloodType: String, val units: Int, val urgency: String, @SerializedName("facility_name") val facilityName: String, val area: String, @SerializedName("distance_km") val distanceKm: Double, val status: String)
data class DonorResponseRequest(val response: String)
data class DonorResponseResponse(@SerializedName("request_id") val requestId: String, @SerializedName("donor_id") val donorId: String, val response: String)

// --- Donor map, matched-requester exact location, chat, and contact sharing ---

data class DonorMapResponse(
    @SerializedName("generated_at") val generatedAt: String,
    @SerializedName("approximate_only") val approximateOnly: Boolean = true,
    val entries: List<DonorMapEntryResponse> = emptyList()
)

data class DonorMapEntryResponse(
    @SerializedName("area_label") val areaLabel: String,
    val latitude: Double,
    val longitude: Double,
    @SerializedName("radius_meters") val radiusMeters: Int,
    @SerializedName("blood_type") val bloodType: String,
    val availability: String,
    @SerializedName("freshness_at") val freshnessAt: String,
    @SerializedName("freshness_age_minutes") val freshnessAgeMinutes: Int,
    @SerializedName("is_stale") val isStale: Boolean = false
)

data class DonorMapVisibilityRequest(
    @SerializedName("map_visible") val mapVisible: Boolean,
    @SerializedName("exact_location_sharing_enabled") val exactLocationSharingEnabled: Boolean = false
)

data class DonorMapVisibilityResponse(
    @SerializedName("donor_id") val donorId: String,
    @SerializedName("map_visible") val mapVisible: Boolean,
    @SerializedName("exact_location_sharing_enabled") val exactLocationSharingEnabled: Boolean,
    @SerializedName("map_visibility_updated_at") val mapVisibilityUpdatedAt: String? = null,
    @SerializedName("freshness_at") val freshnessAt: String? = null
)

data class LocationShareResponse(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("donor_id") val donorId: String,
    val status: String,
    @SerializedName("shared_at") val sharedAt: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
    @SerializedName("revoked_at") val revokedAt: String? = null
)

data class ExactLocationResponse(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("donor_id") val donorId: String,
    val shared: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerializedName("precision_meters") val precisionMeters: Int? = null,
    @SerializedName("freshness_at") val freshnessAt: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
    val reason: String? = null
)

data class ConversationResponse(
    @SerializedName("conversation_id") val conversationId: String,
    @SerializedName("request_id") val requestId: String,
    @SerializedName("donor_id") val donorId: String,
    @SerializedName("requester_id") val requesterId: String,
    @SerializedName("last_message_at") val lastMessageAt: String? = null,
    @SerializedName("created_at") val createdAt: String
)

data class MessageRequest(val body: String)

data class MessageResponse(
    @SerializedName("message_id") val messageId: String,
    @SerializedName("conversation_id") val conversationId: String,
    @SerializedName("sender_id") val senderId: String,
    val body: String,
    @SerializedName("created_at") val createdAt: String
)

data class ContactShareRequest(val field: String, val value: String)

data class ContactShareResponse(
    @SerializedName("share_id") val shareId: String,
    @SerializedName("conversation_id") val conversationId: String,
    @SerializedName("shared_by") val sharedBy: String,
    val field: String,
    val value: String,
    @SerializedName("created_at") val createdAt: String
)

data class ConversationModerationRequest(val reason: String = "")

data class ConversationModerationResponse(
    @SerializedName("conversation_id") val conversationId: String,
    val action: String,
    val accepted: Boolean = true
)
