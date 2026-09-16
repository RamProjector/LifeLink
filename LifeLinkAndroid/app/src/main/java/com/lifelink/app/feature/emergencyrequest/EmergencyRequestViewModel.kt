package com.lifelink.app.feature.emergencyrequest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifelink.app.domain.EmergencyRequestDraft
import com.lifelink.app.domain.EmergencyRequestRepository
import com.lifelink.app.domain.RequestStep
import com.lifelink.app.domain.SubmitResult
import com.lifelink.app.domain.ActiveRequestSnapshot
import com.lifelink.app.domain.DiscoveredDonor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SubmissionState {
    data object Idle : SubmissionState
    data object Saving : SubmissionState
    data object Submitting : SubmissionState
    data class Matching(val requestId: String) : SubmissionState
    data class ManualFallback(val requestId: String, val reason: String) : SubmissionState
    data class QueuedOffline(val draftId: String) : SubmissionState
    data class Error(val message: String) : SubmissionState
}

data class EmergencyRequestUiState(
    val draft: EmergencyRequestDraft = EmergencyRequestDraft(),
    val step: RequestStep = RequestStep.BLOOD_NEED,
    val submission: SubmissionState = SubmissionState.Idle,
    val criticalConfirmationVisible: Boolean = false,
    val draftSaved: Boolean = false,
    val activeRequest: ActiveRequestSnapshot? = null,
    val statusRefreshing: Boolean = false,
    val discoveredDonors: List<DiscoveredDonor> = emptyList(),
    val selectedDonorIds: Set<String> = emptySet(),
    val contactRequestSent: Boolean = false
)

sealed interface EmergencyRequestAction {
    data class UpdateDraft(val update: (EmergencyRequestDraft) -> EmergencyRequestDraft) : EmergencyRequestAction
    data object Continue : EmergencyRequestAction
    data object Back : EmergencyRequestAction
    data class EditStep(val step: RequestStep) : EmergencyRequestAction
    data object SaveDraft : EmergencyRequestAction
    data object Submit : EmergencyRequestAction
    data object ConfirmCriticalSubmit : EmergencyRequestAction
    data object DismissCriticalSubmit : EmergencyRequestAction
    data object SendManualBroadcast : EmergencyRequestAction
    data object RefreshStatus : EmergencyRequestAction
    data object CancelRequest : EmergencyRequestAction
    data object Retry : EmergencyRequestAction
    data class ToggleDonorSelection(val donorId: String) : EmergencyRequestAction
    data object ContactSelectedDonors : EmergencyRequestAction
}

class EmergencyRequestViewModel(
    private val repository: EmergencyRequestRepository,
    private val enablePolling: Boolean = true
) : ViewModel() {
    private val _uiState = MutableStateFlow(EmergencyRequestUiState())
    val uiState: StateFlow<EmergencyRequestUiState> = _uiState.asStateFlow()
    private var draftSaveJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeActiveRequest().collect { active ->
                _uiState.update { it.copy(activeRequest = active) }
            }
        }
        if (enablePolling) {
            viewModelScope.launch {
                while (true) {
                    delay(30_000)
                    val current = _uiState.value.activeRequest
                    if (current != null && !current.isTerminal) {
                        runCatching { repository.refreshActiveRequest(current.requestId) }
                    }
                }
            }
        }
    }

    fun onAction(action: EmergencyRequestAction) {
        when (action) {
            is EmergencyRequestAction.UpdateDraft -> updateDraft(action.update)
            EmergencyRequestAction.Continue -> continueStep()
            EmergencyRequestAction.Back -> back()
            is EmergencyRequestAction.EditStep -> _uiState.update { it.copy(step = action.step) }
            EmergencyRequestAction.SaveDraft -> saveDraft()
            EmergencyRequestAction.Submit -> requestSubmit()
            EmergencyRequestAction.ConfirmCriticalSubmit -> {
                _uiState.update { it.copy(criticalConfirmationVisible = false) }
                submit()
            }
            EmergencyRequestAction.DismissCriticalSubmit -> _uiState.update { it.copy(criticalConfirmationVisible = false) }
            EmergencyRequestAction.SendManualBroadcast -> sendManualBroadcast()
            EmergencyRequestAction.RefreshStatus -> refreshStatus()
            EmergencyRequestAction.CancelRequest -> cancelRequest()
            EmergencyRequestAction.Retry -> submit()
            is EmergencyRequestAction.ToggleDonorSelection -> toggleDonor(action.donorId)
            EmergencyRequestAction.ContactSelectedDonors -> contactSelectedDonors()
        }
    }

    private fun updateDraft(update: (EmergencyRequestDraft) -> EmergencyRequestDraft) {
        _uiState.update { it.copy(draft = update(it.draft), draftSaved = false, submission = SubmissionState.Idle) }
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(500)
            saveDraft()
        }
    }

    private fun continueStep() {
        val state = _uiState.value
        if (validateStep(state.step, state.draft) != null) {
            _uiState.update { it.copy(submission = SubmissionState.Error(validateStep(state.step, state.draft)!!)) }
            return
        }
        val next = RequestStep.entries.getOrNull(state.step.index + 1) ?: return
        _uiState.update { it.copy(step = next, submission = SubmissionState.Idle) }
    }

    private fun back() {
        val previous = RequestStep.entries.getOrNull(_uiState.value.step.index - 1)
        if (previous != null) _uiState.update { it.copy(step = previous) }
    }

    private fun requestSubmit() {
        val state = _uiState.value
        val error = validateFullDraft(state.draft)
        if (error != null) {
            _uiState.update { it.copy(submission = SubmissionState.Error(error)) }
        } else if (state.draft.urgency == com.lifelink.app.domain.Urgency.CRITICAL) {
            _uiState.update { it.copy(criticalConfirmationVisible = true) }
        } else submit()
    }

    private fun saveDraft() {
        val draft = _uiState.value.draft
        viewModelScope.launch {
            _uiState.update { it.copy(submission = SubmissionState.Saving) }
            runCatching { repository.saveDraft(draft) }
                .onSuccess { _uiState.update { it.copy(draftSaved = true, submission = SubmissionState.Idle) } }
                .onFailure { _uiState.update { it.copy(submission = SubmissionState.Error("Draft is kept on this device; sync will retry.")) } }
        }
    }

    private fun submit() {
        draftSaveJob?.cancel()
        val draft = _uiState.value.draft
        viewModelScope.launch {
            _uiState.update { it.copy(submission = SubmissionState.Submitting) }
            when (val result = repository.submit(draft)) {
                is SubmitResult.MatchingStarted -> _uiState.update { it.copy(submission = SubmissionState.Matching(result.requestId), discoveredDonors = result.donors) }
                is SubmitResult.ContactRequested -> _uiState.update { it.copy(contactRequestSent = true) }
                is SubmitResult.ManualFallback -> _uiState.update { it.copy(submission = SubmissionState.ManualFallback(result.requestId, result.reason)) }
                is SubmitResult.Cancelled -> _uiState.update { it.copy(submission = SubmissionState.Idle) }
                is SubmitResult.OfflineQueued -> _uiState.update { it.copy(submission = SubmissionState.QueuedOffline(result.draftId)) }
                is SubmitResult.Error -> _uiState.update { it.copy(submission = SubmissionState.Error(result.message)) }
            }
        }
    }

    private fun sendManualBroadcast() {
        val fallback = _uiState.value.submission as? SubmissionState.ManualFallback ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(submission = SubmissionState.Submitting) }
            when (val result = repository.sendManualBroadcast(fallback.requestId)) {
                is SubmitResult.MatchingStarted -> _uiState.update { it.copy(submission = SubmissionState.Matching(result.requestId)) }
                is SubmitResult.ContactRequested -> _uiState.update { it.copy(contactRequestSent = true) }
                is SubmitResult.Error -> _uiState.update { it.copy(submission = SubmissionState.Error(result.message)) }
                is SubmitResult.ManualFallback -> _uiState.update { it.copy(submission = SubmissionState.ManualFallback(result.requestId, result.reason)) }
                is SubmitResult.Cancelled -> _uiState.update { it.copy(submission = SubmissionState.Idle) }
                is SubmitResult.OfflineQueued -> _uiState.update { it.copy(submission = SubmissionState.Error("You’re offline. No broadcast was sent.")) }
            }
        }
    }

    private fun toggleDonor(donorId: String) {
        _uiState.update { state ->
            val next = state.selectedDonorIds.toMutableSet().apply { if (!add(donorId)) remove(donorId) }
            state.copy(selectedDonorIds = next)
        }
    }

    private fun contactSelectedDonors() {
        val state = _uiState.value
        val requestId = (state.submission as? SubmissionState.Matching)?.requestId ?: return
        if (state.selectedDonorIds.isEmpty()) return
        viewModelScope.launch {
            when (val result = repository.contactSelectedDonors(requestId, state.selectedDonorIds.toList())) {
                is SubmitResult.ContactRequested -> _uiState.update { it.copy(contactRequestSent = true) }
                is SubmitResult.Error -> _uiState.update { it.copy(submission = SubmissionState.Error(result.message)) }
                else -> Unit
            }
        }
    }

    private fun refreshStatus() {
        val requestId = _uiState.value.activeRequest?.requestId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(statusRefreshing = true) }
            runCatching { repository.refreshActiveRequest(requestId) }
                .onFailure { _uiState.update { it.copy(submission = SubmissionState.Error("Status could not be refreshed. Try again.")) } }
            _uiState.update { it.copy(statusRefreshing = false) }
        }
    }

    private fun cancelRequest() {
        val requestId = _uiState.value.activeRequest?.requestId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(statusRefreshing = true) }
            when (val result = repository.cancelRequest(requestId)) {
                is SubmitResult.Cancelled -> _uiState.update { it.copy(statusRefreshing = false) }
                is SubmitResult.Error -> _uiState.update { it.copy(statusRefreshing = false, submission = SubmissionState.Error(result.message)) }
                else -> _uiState.update { it.copy(statusRefreshing = false, submission = SubmissionState.Error("Unexpected cancellation response.")) }
            }
        }
    }

    private fun validateStep(step: RequestStep, draft: EmergencyRequestDraft): String? = when (step) {
        RequestStep.BLOOD_NEED -> when {
            !draft.typeUnknown && draft.bloodType == null -> "Select a blood type or choose unknown type."
            draft.units !in 1..20 -> "Units must be between 1 and 20."
            else -> null
        }
        RequestStep.URGENCY -> if (draft.responseDeadline.isBlank()) "Choose a response deadline." else null
        RequestStep.LOCATION -> if (draft.facility?.verified != true) "Choose a verified facility." else null
        RequestStep.CONTACT -> when {
            !draft.genuineRequestConfirmed -> "Confirm this is a genuine request for a verified facility."
            !draft.sharingConsentConfirmed -> "Confirm that request details may be shared with eligible donors."
            else -> null
        }
        RequestStep.REVIEW -> null
    }

    private fun validateFullDraft(draft: EmergencyRequestDraft): String? = RequestStep.entries.firstNotNullOfOrNull { validateStep(it, draft) }

    override fun onCleared() {
        draftSaveJob?.cancel()
        super.onCleared()
    }
}

class EmergencyRequestViewModelFactory(
    private val repository: EmergencyRequestRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(EmergencyRequestViewModel::class.java))
        return EmergencyRequestViewModel(repository) as T
    }
}
