package com.lifelink.app.domain

import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class BloodType(val label: String) { A_POS("A+"), A_NEG("A−"), B_POS("B+"), B_NEG("B−"), AB_POS("AB+"), AB_NEG("AB−"), O_POS("O+"), O_NEG("O−") }
enum class Urgency(val label: String, val description: String) { CRITICAL("Critical", "Needed within 2 hours"), URGENT("Urgent", "Needed today"), PLANNED("Planned", "Needed within 24 hours") }
enum class ContactMethod(val label: String) { IN_APP("In-app message"), PHONE("Verified coordinator call") }
enum class RequestStep(val index: Int) { BLOOD_NEED(0), URGENCY(1), LOCATION(2), CONTACT(3), REVIEW(4) }

data class Facility(val id: String, val name: String, val area: String, val verified: Boolean = true)

data class EmergencyRequestDraft(
    val id: String = UUID.randomUUID().toString(),
    val bloodType: BloodType? = null,
    val units: Int = 1,
    val typeUnknown: Boolean = false,
    val urgency: Urgency = Urgency.URGENT,
    val responseDeadline: String = "Today, 12:30 PM",
    val note: String = "",
    val facility: Facility? = null,
    val contactMethod: ContactMethod = ContactMethod.IN_APP,
    val genuineRequestConfirmed: Boolean = false,
    val sharingConsentConfirmed: Boolean = false,
    val aiMatchingEnabled: Boolean = true
)

data class DiscoveredDonor(
    val donorId: String,
    val displayName: String,
    val bloodType: String,
    val distanceKm: Double,
    val travelMinutes: Int,
    val score: Double,
    val explanation: List<String> = emptyList()
)

sealed interface SubmitResult {
    data class MatchingStarted(val requestId: String, val donors: List<DiscoveredDonor> = emptyList()) : SubmitResult
    data class ContactRequested(val requestId: String, val donorIds: List<String>) : SubmitResult
    data class ManualFallback(val requestId: String, val reason: String) : SubmitResult
    data class Cancelled(val requestId: String) : SubmitResult
    data class OfflineQueued(val draftId: String) : SubmitResult
    data class Error(val message: String) : SubmitResult
}

interface EmergencyRequestRepository {
    suspend fun saveDraft(draft: EmergencyRequestDraft)
    suspend fun loadDraft(id: String): EmergencyRequestDraft?
    suspend fun submit(draft: EmergencyRequestDraft): SubmitResult
    suspend fun contactSelectedDonors(requestId: String, donorIds: List<String>): SubmitResult
    suspend fun sendManualBroadcast(requestId: String): SubmitResult
    suspend fun cancelRequest(requestId: String): SubmitResult
    fun observeActiveRequest(): Flow<ActiveRequestSnapshot?>
    suspend fun refreshActiveRequest(requestId: String): ActiveRequestSnapshot?
}
