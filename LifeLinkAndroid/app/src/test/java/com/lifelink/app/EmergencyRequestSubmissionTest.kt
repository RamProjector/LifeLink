package com.lifelink.app

import com.lifelink.app.domain.EmergencyRequestDraft
import com.lifelink.app.domain.Facility
import com.lifelink.app.domain.SubmitResult
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestAction
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestViewModel
import com.lifelink.app.feature.emergencyrequest.SubmissionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyRequestSubmissionTest {
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
        advanceUntilIdle()

        assertEquals(SubmissionState.QueuedOffline("draft-queued"), viewModel.uiState.value.submission)
        viewModel.onAction(EmergencyRequestAction.UpdateDraft { it.copy(note = "changed after queue") })
        viewModel.onAction(EmergencyRequestAction.SaveDraft)
        advanceUntilIdle()

        assertEquals(submittedDraft, viewModel.uiState.value.draft)
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
}
