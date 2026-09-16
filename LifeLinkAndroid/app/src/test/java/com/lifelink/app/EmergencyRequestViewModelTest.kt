package com.lifelink.app

import com.lifelink.app.domain.EmergencyRequestDraft
import com.lifelink.app.domain.EmergencyRequestRepository
import com.lifelink.app.domain.Facility
import com.lifelink.app.domain.ActiveRequestSnapshot
import com.lifelink.app.domain.SubmitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestAction
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestViewModel
import com.lifelink.app.feature.emergencyrequest.SubmissionState
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

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
    fun continue_without_blood_type_exposes_validation_error() = runTest {
        val viewModel = EmergencyRequestViewModel(FakeRepository(), enablePolling = false)
        viewModel.onAction(EmergencyRequestAction.Continue)
        assertTrue(viewModel.uiState.value.submission is SubmissionState.Error)
    }

    @Test
    fun critical_submit_requires_confirmation_then_starts_matching() = runTest {
        val repository = FakeRepository(SubmitResult.MatchingStarted("req-1"))
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(EmergencyRequestAction.UpdateDraft {
            it.copy(
                bloodType = com.lifelink.app.domain.BloodType.O_NEG,
                urgency = com.lifelink.app.domain.Urgency.CRITICAL,
                facility = facility,
                genuineRequestConfirmed = true,
                sharingConsentConfirmed = true
            )
        })
        viewModel.onAction(EmergencyRequestAction.Submit)
        assertTrue(viewModel.uiState.value.criticalConfirmationVisible)
        viewModel.onAction(EmergencyRequestAction.ConfirmCriticalSubmit)
        advanceUntilIdle()
        assertEquals(SubmissionState.Matching("req-1"), viewModel.uiState.value.submission)
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
    fun contact_selected_donors_marks_request_as_sent() = runTest {
        val repository = FakeRepository(SubmitResult.MatchingStarted("req-2"))
        repository.contactResult = SubmitResult.ContactRequested("req-2", listOf("donor-1"))
        val viewModel = EmergencyRequestViewModel(repository, enablePolling = false)
        viewModel.onAction(EmergencyRequestAction.UpdateDraft {
            it.copy(bloodType = com.lifelink.app.domain.BloodType.O_NEG, facility = facility, genuineRequestConfirmed = true, sharingConsentConfirmed = true)
        })
        viewModel.onAction(EmergencyRequestAction.Submit)
        advanceUntilIdle()
        viewModel.onAction(EmergencyRequestAction.ToggleDonorSelection("donor-1"))
        viewModel.onAction(EmergencyRequestAction.ContactSelectedDonors)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.contactRequestSent)
        assertEquals(listOf("donor-1"), repository.lastContactedDonors)
    }
}

private class FakeRepository(
    private val submitResult: SubmitResult = SubmitResult.OfflineQueued("draft-1")
) : EmergencyRequestRepository {
    var savedDrafts = 0
    var contactResult: SubmitResult = SubmitResult.Error("not configured")
    var lastContactedDonors: List<String> = emptyList()
    override suspend fun saveDraft(draft: EmergencyRequestDraft) { savedDrafts++ }
    override suspend fun loadDraft(id: String): EmergencyRequestDraft? = null
    override suspend fun submit(draft: EmergencyRequestDraft): SubmitResult = submitResult
    override suspend fun contactSelectedDonors(requestId: String, donorIds: List<String>): SubmitResult {
        lastContactedDonors = donorIds
        return contactResult
    }
    override suspend fun sendManualBroadcast(requestId: String): SubmitResult = SubmitResult.MatchingStarted(requestId)
    override suspend fun cancelRequest(requestId: String): SubmitResult = SubmitResult.Cancelled(requestId)
    override fun observeActiveRequest(): Flow<ActiveRequestSnapshot?> = flowOf(null)
    override suspend fun refreshActiveRequest(requestId: String): ActiveRequestSnapshot? = null
}
