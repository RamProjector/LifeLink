package com.lifelink.app

import com.lifelink.app.domain.ActiveRequestSnapshot
import com.lifelink.app.domain.DiscoveredDonor
import com.lifelink.app.domain.EmergencyRequestDraft
import com.lifelink.app.domain.EmergencyRequestRepository
import com.lifelink.app.domain.NearbyFacility
import com.lifelink.app.domain.RequestHistoryItem
import com.lifelink.app.domain.RequesterContact
import com.lifelink.app.domain.SubmitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class FakeRepository(
    private val submitResult: SubmitResult = SubmitResult.OfflineQueued("draft-1"),
) : EmergencyRequestRepository {
    var savedDrafts = 0
    var latestDraft: EmergencyRequestDraft? = null
    val deletedDraftIds = mutableListOf<String>()
    var draftSaveFailure: Exception? = null
    val submissionEvents = mutableListOf<String>()
    var submitCalls = 0
    var submitGate: CompletableDeferred<SubmitResult>? = null
    var contactCalls = 0
    var contactGate: CompletableDeferred<SubmitResult>? = null
    var historyFailure: Exception? = null
    var history: List<RequestHistoryItem> = emptyList()
    var matches: List<DiscoveredDonor> = emptyList()
    var lastMatchesRequestId: String? = null
    var contactsFailure: Exception? = null
    var contactResult: SubmitResult = SubmitResult.Error("not configured")
    var lastContactedDonors: List<String> = emptyList()
    var activeRequest: ActiveRequestSnapshot? = null
    var lastRefreshedActiveRequestId: String? = null
    var contacts: List<RequesterContact> = emptyList()
    var nearbyFacilities: List<NearbyFacility> = emptyList()

    override suspend fun saveDraft(draft: EmergencyRequestDraft) {
        savedDrafts++
        submissionEvents += "save"
        draftSaveFailure?.let { throw it }
    }

    override suspend fun loadDraft(id: String): EmergencyRequestDraft? = latestDraft?.takeIf { it.id == id }

    override suspend fun loadLatestDraft(): EmergencyRequestDraft? = latestDraft

    override suspend fun deleteDraft(id: String) {
        deletedDraftIds += id
        if (latestDraft?.id == id) latestDraft = null
    }

    override suspend fun submit(draft: EmergencyRequestDraft): SubmitResult {
        submitCalls++
        submissionEvents += "submit"
        return submitGate?.await() ?: submitResult
    }

    override suspend fun contactSelectedDonors(requestId: String, donorIds: List<String>): SubmitResult {
        contactCalls++
        lastContactedDonors = donorIds
        val result = contactGate?.await() ?: contactResult
        return when (result) {
            is SubmitResult.ContactRequested -> {
                contacts =
                    (contacts.map { it.donorId to it } + donorIds.map { it to RequesterContact(it, it, "pending") })
                        .associate { it.first to it.second }
                        .values
                        .toList()
                SubmitResult.ContactRequested(requestId, donorIds)
            }
            else -> result
        }
    }

    override suspend fun sendManualBroadcast(requestId: String): SubmitResult = SubmitResult.MatchingStarted(requestId)

    override suspend fun cancelRequest(requestId: String): SubmitResult = SubmitResult.Cancelled(requestId)

    override suspend fun fulfillRequest(requestId: String): SubmitResult = SubmitResult.Fulfilled(requestId)

    override suspend fun refreshContacts(requestId: String): List<RequesterContact> {
        contactsFailure?.let { throw it }
        return contacts
    }

    override suspend fun refreshMatches(requestId: String): List<DiscoveredDonor> {
        lastMatchesRequestId = requestId
        return matches
    }

    override suspend fun findNearbyFacilities(
        latitude: Double,
        longitude: Double,
    ): List<NearbyFacility> = nearbyFacilities

    override suspend fun updateContactStatus(
        requestId: String,
        donorId: String,
        status: String,
    ): RequesterContact = RequesterContact(
        donorId,
        "Donor",
        status,
    )

    override suspend fun reportContact(requestId: String, donorId: String, reason: String): String = "reported"

    override suspend fun blockContact(requestId: String, donorId: String): String = "blocked"

    override fun observeActiveRequest(): Flow<ActiveRequestSnapshot?> = flowOf(activeRequest)

    override fun observeRequestHistory(): Flow<List<ActiveRequestSnapshot>> = flowOf(emptyList())

    override suspend fun refreshRequestHistory(): List<RequestHistoryItem> {
        historyFailure?.let { throw it }
        return history
    }

    override suspend fun refreshActiveRequest(requestId: String): ActiveRequestSnapshot? {
        lastRefreshedActiveRequestId = requestId
        return activeRequest
    }
}
