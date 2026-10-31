package com.lifelink.app

import com.lifelink.app.data.remote.EmergencyRequestRequest
import com.lifelink.app.domain.ActiveRequestSnapshot
import com.lifelink.app.domain.DiscoveredDonor
import com.lifelink.app.domain.EmergencyRequestDraft
import com.lifelink.app.domain.EmergencyRequestRepository
import com.lifelink.app.domain.Facility
import com.lifelink.app.domain.RequestHistoryItem
import com.lifelink.app.domain.RequesterContact
import com.lifelink.app.domain.SubmitResult
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestAction
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestViewModel
import com.lifelink.app.feature.emergencyrequest.SubmissionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyRequestViewModelTest {
    private val facility = Facility("facility-1", "St. Luke’s Medical Center", "Quezon City")

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun failed_history_load_is_visible_and_retry_restores_requests() = runTest {
        val repository = FakeRepository()
        repository.historyFailure = java.io.IOException("offline")
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.historyError != null)
        assertTrue(!viewModel.uiState.value.historyRefreshing)
        repository.historyFailure = null
        repository.history = listOf(RequestHistoryItem("saved-request", com.lifelink.app.domain.ActiveRequestStatus.AWAITING_RESPONSES))
        viewModel.onAction(EmergencyRequestAction.RefreshHistory)
        advanceUntilIdle()
        assertEquals(
            "saved-request",
            viewModel.uiState.value.requestHistory
                .single()
                .requestId,
        )
        assertEquals(null, viewModel.uiState.value.historyError)
        repository.historyFailure = java.io.IOException("offline again")
        viewModel.onAction(EmergencyRequestAction.RefreshHistory)
        advanceUntilIdle()
        assertEquals(
            "saved-request",
            viewModel.uiState.value.requestHistory
                .single()
                .requestId,
        )
        assertTrue(viewModel.uiState.value.historyError != null)
    }

    @Test
    fun latest_saved_draft_is_restored_when_no_active_request_exists() = runTest {
        val savedDraft = EmergencyRequestDraft(bloodType = com.lifelink.app.domain.BloodType.O_NEG, note = "saved note")
        val repository = FakeRepository().apply { latestDraft = savedDraft }
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        advanceUntilIdle()

        assertEquals(savedDraft.id, viewModel.uiState.value.draft.id)
        assertEquals(com.lifelink.app.domain.BloodType.O_NEG, viewModel.uiState.value.draft.bloodType)
        assertEquals("saved note", viewModel.uiState.value.draft.note)
        assertTrue(viewModel.uiState.value.draftSaved)
    }

    @Test
    fun terminal_request_does_not_block_restoring_a_saved_draft() = runTest {
        val savedDraft = EmergencyRequestDraft(note = "unfinished after cancellation")
        val repository = FakeRepository().apply {
            latestDraft = savedDraft
            activeRequest = ActiveRequestSnapshot("old-request", com.lifelink.app.domain.ActiveRequestStatus.CANCELLED)
        }
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        advanceUntilIdle()

        assertEquals(savedDraft.id, viewModel.uiState.value.draft.id)
        assertEquals("unfinished after cancellation", viewModel.uiState.value.draft.note)
    }

    @Test
    fun successful_submission_removes_the_local_unsent_draft() = runTest {
        val repository = FakeRepository(SubmitResult.MatchingStarted("request-accepted"))
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(
            EmergencyRequestAction.UpdateDraft {
                it.copy(
                    bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                    facility = facility,
                    requesterLatitude = 14.6466,
                    requesterLongitude = 121.0437,
                    genuineRequestConfirmed = true,
                    sharingConsentConfirmed = true,
                )
            },
        )
        val submittedDraftId = viewModel.uiState.value.draft.id
        viewModel.onAction(EmergencyRequestAction.Submit)
        advanceUntilIdle()

        assertTrue(submittedDraftId in repository.deletedDraftIds)
        assertEquals(SubmissionState.Matching("request-accepted"), viewModel.uiState.value.submission)
    }

    @Test
    fun continue_without_blood_type_exposes_validation_error() = runTest {
        val viewModel = EmergencyRequestViewModel(FakeRepository(), enablePolling = false)
        viewModel.onAction(EmergencyRequestAction.Continue)
        assertTrue(viewModel.uiState.value.submission is SubmissionState.Error)
    }

    @Test
    fun negative_blood_type_uses_api_ascii_hyphen() {
        val request =
            EmergencyRequestRequest.from(
                EmergencyRequestDraft(
                    bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                    requesterLatitude = 14.6466,
                    requesterLongitude = 121.0437,
                ),
                requesterId = "user-1",
            )
        assertEquals("O-", request.bloodType)
    }

    @Test
    fun critical_submit_requires_confirmation_then_starts_matching() = runTest {
        val repository = FakeRepository(SubmitResult.MatchingStarted("req-1"))
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(
            EmergencyRequestAction.UpdateDraft {
                it.copy(
                    bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                    urgency = com.lifelink.app.domain.Urgency.CRITICAL,
                    facility = facility,
                    requesterLatitude = 14.6466,
                    requesterLongitude = 121.0437,
                    genuineRequestConfirmed = true,
                    sharingConsentConfirmed = true,
                )
            },
        )
        viewModel.onAction(EmergencyRequestAction.Submit)
        assertTrue(viewModel.uiState.value.criticalConfirmationVisible)
        viewModel.onAction(EmergencyRequestAction.ConfirmCriticalSubmit)
        advanceUntilIdle()
        assertEquals(SubmissionState.Matching("req-1"), viewModel.uiState.value.submission)
    }

    @Test
    fun rapid_duplicate_submit_actions_start_only_one_repository_request() = runTest {
        val repository = FakeRepository()
        val pendingResult = CompletableDeferred<SubmitResult>()
        repository.submitGate = pendingResult
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(
            EmergencyRequestAction.UpdateDraft {
                it.copy(
                    bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                    facility = facility,
                    requesterLatitude = 14.6466,
                    requesterLongitude = 121.0437,
                    genuineRequestConfirmed = true,
                    sharingConsentConfirmed = true,
                )
            },
        )

        viewModel.onAction(EmergencyRequestAction.Submit)
        viewModel.onAction(EmergencyRequestAction.Submit)

        assertEquals(1, repository.submitCalls)
        assertEquals(SubmissionState.Submitting, viewModel.uiState.value.submission)

        pendingResult.complete(SubmitResult.MatchingStarted("single-request"))
        advanceUntilIdle()
        assertEquals(SubmissionState.Matching("single-request"), viewModel.uiState.value.submission)
        assertEquals(1, repository.submitCalls)
    }

    @Test
    fun submission_persists_the_exact_draft_before_network_request() = runTest {
        val repository = FakeRepository(SubmitResult.MatchingStarted("persisted-request"))
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(
            EmergencyRequestAction.UpdateDraft {
                it.copy(
                    bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                    facility = facility,
                    requesterLatitude = 14.6466,
                    requesterLongitude = 121.0437,
                    genuineRequestConfirmed = true,
                    sharingConsentConfirmed = true,
                )
            },
        )

        viewModel.onAction(EmergencyRequestAction.Submit)
        advanceUntilIdle()

        assertEquals(listOf("save", "submit"), repository.submissionEvents)
        assertEquals(SubmissionState.Matching("persisted-request"), viewModel.uiState.value.submission)
    }

    @Test
    fun local_draft_persistence_failure_prevents_network_submission() = runTest {
        val repository = FakeRepository().apply { draftSaveFailure = java.io.IOException("disk unavailable") }
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(
            EmergencyRequestAction.UpdateDraft {
                it.copy(
                    bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                    facility = facility,
                    requesterLatitude = 14.6466,
                    requesterLongitude = 121.0437,
                    genuineRequestConfirmed = true,
                    sharingConsentConfirmed = true,
                )
            },
        )

        viewModel.onAction(EmergencyRequestAction.Submit)
        advanceUntilIdle()

        assertEquals(0, repository.submitCalls)
        val error = viewModel.uiState.value.submission as SubmissionState.Error
        assertTrue(!error.retryable)
    }

    @Test
    fun editing_draft_during_submission_is_ignored() = runTest {
        val repository = FakeRepository()
        val pendingResult = CompletableDeferred<SubmitResult>()
        repository.submitGate = pendingResult
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(
            EmergencyRequestAction.UpdateDraft {
                it.copy(
                    bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                    facility = facility,
                    requesterLatitude = 14.6466,
                    requesterLongitude = 121.0437,
                    genuineRequestConfirmed = true,
                    sharingConsentConfirmed = true,
                )
            },
        )
        val submittedDraft = viewModel.uiState.value.draft

        viewModel.onAction(EmergencyRequestAction.Submit)
        viewModel.onAction(EmergencyRequestAction.UpdateDraft { it.copy(units = 7) })

        assertEquals(submittedDraft, viewModel.uiState.value.draft)
        pendingResult.complete(SubmitResult.MatchingStarted("locked-request"))
        advanceUntilIdle()
    }

    @Test
    fun queued_offline_submission_keeps_its_original_payload_immutable() = runTest {
        val repository = FakeRepository(SubmitResult.OfflineQueued("draft-queued"))
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        val originalNote = viewModel.uiState.value.draft.note
        viewModel.onAction(EmergencyRequestAction.Submit)
        advanceUntilIdle()

        assertEquals(SubmissionState.QueuedOffline("draft-queued"), viewModel.uiState.value.submission)
        viewModel.onAction(EmergencyRequestAction.UpdateDraft { it.copy(note = "changed after queue") })
        viewModel.onAction(EmergencyRequestAction.SaveDraft)
        advanceUntilIdle()

        assertEquals(originalNote, viewModel.uiState.value.draft.note)
        assertEquals(1, repository.savedDrafts)
    }

    @Test
    fun non_retryable_submission_error_does_not_repeat_network_request() = runTest {
        val repository = FakeRepository(SubmitResult.Error("Request is not authorized.", retryable = false))
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(
            EmergencyRequestAction.UpdateDraft {
                it.copy(
                    bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                    facility = facility,
                    requesterLatitude = 14.6466,
                    requesterLongitude = 121.0437,
                    genuineRequestConfirmed = true,
                    sharingConsentConfirmed = true,
                )
            },
        )

        viewModel.onAction(EmergencyRequestAction.Submit)
        advanceUntilIdle()
        viewModel.onAction(EmergencyRequestAction.Retry)
        advanceUntilIdle()

        assertEquals(1, repository.submitCalls)
    }

    @Test
    fun repeated_contact_submit_actions_start_only_one_contact_request() = runTest {
        val repository = FakeRepository(SubmitResult.MatchingStarted("contact-request"))
        val contactGate = CompletableDeferred<SubmitResult>()
        repository.contactGate = contactGate
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(
            EmergencyRequestAction.UpdateDraft {
                it.copy(
                    bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                    facility = facility,
                    requesterLatitude = 14.6466,
                    requesterLongitude = 121.0437,
                    genuineRequestConfirmed = true,
                    sharingConsentConfirmed = true,
                )
            },
        )
        viewModel.onAction(EmergencyRequestAction.Submit)
        advanceUntilIdle()
        viewModel.onAction(EmergencyRequestAction.ToggleDonorSelection("donor-1"))

        viewModel.onAction(EmergencyRequestAction.ContactSelectedDonors)
        viewModel.onAction(EmergencyRequestAction.ContactSelectedDonors)

        assertEquals(1, repository.contactCalls)
        assertTrue(viewModel.uiState.value.contactSubmissionInFlight)
        contactGate.complete(SubmitResult.ContactRequested("contact-request", listOf("donor-1")))
        advanceUntilIdle()
        assertTrue(!viewModel.uiState.value.contactSubmissionInFlight)
    }

    @Test
    fun save_draft_calls_repository() = runTest {
        val repository = FakeRepository()
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(EmergencyRequestAction.SaveDraft)
        advanceUntilIdle()
        assertEquals(1, repository.savedDrafts)
        assertTrue(viewModel.uiState.value.draftSaved)
    }

    @Test
    fun requester_can_contact_an_additional_batch_without_resending_prior_donors() = runTest {
        val repository = FakeRepository(SubmitResult.MatchingStarted("req-2"))
        repository.contactResult = SubmitResult.ContactRequested("req-2", listOf("donor-1"))
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(
            EmergencyRequestAction.UpdateDraft {
                it.copy(
                    bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                    facility = facility,
                    requesterLatitude = 14.6466,
                    requesterLongitude = 121.0437,
                    genuineRequestConfirmed = true,
                    sharingConsentConfirmed = true,
                )
            },
        )
        viewModel.onAction(EmergencyRequestAction.Submit)
        advanceUntilIdle()
        viewModel.onAction(EmergencyRequestAction.ToggleDonorSelection("donor-1"))
        viewModel.onAction(EmergencyRequestAction.ContactSelectedDonors)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.contactRequestSent)
        assertEquals(listOf("donor-1"), repository.lastContactedDonors)
        assertTrue(
            viewModel.uiState.value.selectedDonorIds
                .isEmpty(),
        )

        viewModel.onAction(EmergencyRequestAction.ToggleDonorSelection("donor-1"))
        viewModel.onAction(EmergencyRequestAction.ToggleDonorSelection("donor-2"))
        viewModel.onAction(EmergencyRequestAction.ContactSelectedDonors)
        advanceUntilIdle()
        assertEquals(listOf("donor-2"), repository.lastContactedDonors)
        assertEquals(
            setOf("donor-1", "donor-2"),
            viewModel.uiState.value.contacts
                .map { it.donorId }
                .toSet(),
        )
    }

    @Test
    fun reopening_results_loads_matches_for_the_requested_request_id() = runTest {
        val repository = FakeRepository()
        repository.matches = listOf(DiscoveredDonor("donor-1", "Donor One", "O-", 2.0, 8, 0.9))
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)

        viewModel.onAction(EmergencyRequestAction.ShowContactResults("saved-request"))
        advanceUntilIdle()

        assertEquals("saved-request", repository.lastMatchesRequestId)
        assertEquals(
            "donor-1",
            viewModel.uiState.value.discoveredDonors
                .single()
                .donorId,
        )
        assertEquals("saved-request", viewModel.uiState.value.resultsRequestId)
        assertEquals(null, viewModel.uiState.value.matchesError)
    }

    @Test
    fun acknowledged_contact_is_preserved_and_retry_clears_selection_after_refresh() = runTest {
        val repository = FakeRepository(SubmitResult.MatchingStarted("req-3"))
        repository.contactResult = SubmitResult.ContactRequested("req-3", listOf("donor-1"))
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(
            EmergencyRequestAction.UpdateDraft {
                it.copy(
                    bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                    facility = facility,
                    requesterLatitude = 14.6466,
                    requesterLongitude = 121.0437,
                    genuineRequestConfirmed = true,
                    sharingConsentConfirmed = true,
                )
            },
        )
        viewModel.onAction(EmergencyRequestAction.Submit)
        advanceUntilIdle()
        repository.contactsFailure = java.io.IOException("temporary status failure")

        viewModel.onAction(EmergencyRequestAction.ToggleDonorSelection("donor-1"))
        viewModel.onAction(EmergencyRequestAction.ContactSelectedDonors)
        advanceUntilIdle()

        assertEquals(listOf("donor-1"), repository.lastContactedDonors)
        assertTrue(viewModel.uiState.value.contactRequestSent)
        assertEquals(
            "donor-1",
            viewModel.uiState.value.contacts
                .single()
                .donorId,
        )
        assertTrue(
            "confirmed selection remains available until refresh succeeds",
            "donor-1" in viewModel.uiState.value.selectedDonorIds,
        )
        assertTrue(viewModel.uiState.value.contactsError != null)

        repository.contactsFailure = null
        viewModel.onAction(EmergencyRequestAction.RefreshContacts("req-3"))
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.selectedDonorIds
                .isEmpty(),
        )
        assertEquals(
            "donor-1",
            viewModel.uiState.value.contacts
                .single()
                .donorId,
        )
        assertEquals(null, viewModel.uiState.value.contactsError)
    }

    @Test
    fun empty_contact_acknowledgment_checks_status_without_request_submission_error() = runTest {
        val repository = FakeRepository(SubmitResult.MatchingStarted("req-4"))
        repository.contactResult = SubmitResult.ContactRequestUncertain("req-4", listOf("donor-1"))
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(
            EmergencyRequestAction.UpdateDraft {
                it.copy(
                    bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                    facility = facility,
                    requesterLatitude = 14.6466,
                    requesterLongitude = 121.0437,
                    genuineRequestConfirmed = true,
                    sharingConsentConfirmed = true,
                )
            },
        )
        viewModel.onAction(EmergencyRequestAction.Submit)
        advanceUntilIdle()

        viewModel.onAction(EmergencyRequestAction.ToggleDonorSelection("donor-1"))
        viewModel.onAction(EmergencyRequestAction.ContactSelectedDonors)
        advanceUntilIdle()

        assertEquals(SubmissionState.Matching("req-4"), viewModel.uiState.value.submission)
        assertTrue(!viewModel.uiState.value.contactRequestSent)
        assertTrue(
            viewModel.uiState.value.contactsError
                ?.contains("empty") == true,
        )
        assertTrue("uncertain contacts stay selected until status confirms them", "donor-1" in viewModel.uiState.value.selectedDonorIds)
    }

    @Test
    fun active_request_refresh_does_not_replace_another_open_results_request() = runTest {
        val repository =
            FakeRepository().apply {
                activeRequest = ActiveRequestSnapshot("active-request", com.lifelink.app.domain.ActiveRequestStatus.MATCHING)
            }
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        advanceUntilIdle()
        viewModel.onAction(EmergencyRequestAction.ShowContactResults("open-results-request"))
        advanceUntilIdle()

        viewModel.onAction(EmergencyRequestAction.RefreshStatus)
        advanceUntilIdle()

        assertEquals("active-request", repository.lastRefreshedActiveRequestId)
        assertEquals("open-results-request", viewModel.uiState.value.resultsRequestId)
        assertEquals(SubmissionState.Matching("open-results-request"), viewModel.uiState.value.submission)
    }

    @Test
    fun cancelling_a_request_resets_the_flow_and_starts_a_fresh_draft() = runTest {
        val repository =
            FakeRepository().apply {
                activeRequest = ActiveRequestSnapshot("active-request", com.lifelink.app.domain.ActiveRequestStatus.AWAITING_RESPONSES)
            }
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        advanceUntilIdle()
        viewModel.onAction(
            EmergencyRequestAction.UpdateDraft {
                it.copy(
                    bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                    facility = facility,
                    requesterLatitude = 14.6466,
                    requesterLongitude = 121.0437,
                    genuineRequestConfirmed = true,
                    sharingConsentConfirmed = true,
                )
            },
        )
        val draftBeforeCancel = viewModel.uiState.value.draft.id

        viewModel.onAction(EmergencyRequestAction.CancelRequest)
        advanceUntilIdle()

        assertEquals(SubmissionState.Idle, viewModel.uiState.value.submission)
        assertEquals(com.lifelink.app.domain.RequestStep.BLOOD_NEED, viewModel.uiState.value.step)
        assertTrue(viewModel.uiState.value.cancelCompleted)
        assertEquals(null, viewModel.uiState.value.resultsRequestId)
        assertTrue(
            viewModel.uiState.value.discoveredDonors
                .isEmpty(),
        )
        assertTrue(
            viewModel.uiState.value.contacts
                .isEmpty(),
        )
        assertTrue(
            "a fresh draft id is generated so a new request is not the cancelled one",
            viewModel.uiState.value.draft.id != draftBeforeCancel,
        )
    }
}

private class FakeRepository(private val submitResult: SubmitResult = SubmitResult.OfflineQueued("draft-1")) : EmergencyRequestRepository {
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

    override suspend fun updateContactStatus(requestId: String, donorId: String, status: String): RequesterContact = RequesterContact(
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
