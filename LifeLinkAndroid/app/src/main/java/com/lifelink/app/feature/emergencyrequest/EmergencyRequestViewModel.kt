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
import com.lifelink.app.domain.RequesterContact
import com.lifelink.app.domain.RequestHistoryItem
import kotlinx.coroutines.CancellationException
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
    val draftSavedManually: Boolean = false,
    val activeRequest: ActiveRequestSnapshot? = null,
    val statusRefreshing: Boolean = false,
    val resultsRequestId: String? = null,
    val discoveredDonors: List<DiscoveredDonor> = emptyList(),
    val matchesRefreshing: Boolean = false,
    val matchesError: String? = null,
    val selectedDonorIds: Set<String> = emptySet(),
    val contactRequestSent: Boolean = false,
    val contacts: List<RequesterContact> = emptyList(),
    val contactsRefreshing: Boolean = false,
    val contactsError: String? = null,
    val contactActionInFlightDonorId: String? = null,
    val requestHistory: List<RequestHistoryItem> = emptyList(),
    val historyRefreshing: Boolean = false,
    val historyError: String? = null
)

sealed interface EmergencyRequestAction {
    data class UpdateDraft(val update: (EmergencyRequestDraft) -> EmergencyRequestDraft) : EmergencyRequestAction
    data object Continue : EmergencyRequestAction
    data object Back : EmergencyRequestAction
    data class EditStep(val step: RequestStep) : EmergencyRequestAction
    data object SaveDraft : EmergencyRequestAction
    data object DraftSaveHandled : EmergencyRequestAction
    data object Submit : EmergencyRequestAction
    data object ConfirmCriticalSubmit : EmergencyRequestAction
    data object DismissCriticalSubmit : EmergencyRequestAction
    data object SendManualBroadcast : EmergencyRequestAction
    data object RefreshStatus : EmergencyRequestAction
    data class OpenRequest(val requestId: String) : EmergencyRequestAction
    data class ShowContactResults(val requestId: String) : EmergencyRequestAction
    data class RefreshContacts(val requestId: String) : EmergencyRequestAction
    data object RefreshHistory : EmergencyRequestAction
    data object CancelRequest : EmergencyRequestAction
    data object FulfillRequest : EmergencyRequestAction
    data object Retry : EmergencyRequestAction
    data class ToggleDonorSelection(val donorId: String) : EmergencyRequestAction
    data object ContactSelectedDonors : EmergencyRequestAction
    data class UpdateContactStatus(val donorId: String, val status: String) : EmergencyRequestAction
    data class ReportContact(val donorId: String, val reason: String = "") : EmergencyRequestAction
    data class BlockContact(val donorId: String) : EmergencyRequestAction
    data class SetGpsLocation(val latitude: Double, val longitude: Double, val precisionMeters: Int) : EmergencyRequestAction
}

class EmergencyRequestViewModel(
    private val repository: EmergencyRequestRepository,
    private val enablePolling: Boolean = true
) : ViewModel() {
    private val _uiState = MutableStateFlow(EmergencyRequestUiState())
    val uiState: StateFlow<EmergencyRequestUiState> = _uiState.asStateFlow()
    private var draftSaveJob: Job? = null
    private var historyRefreshJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeActiveRequest().collect { active ->
                _uiState.update { it.copy(activeRequest = active) }
            }
        }
        viewModelScope.launch {
            repository.observeRequestHistory().collect { history ->
                _uiState.update { state ->
                    if (state.requestHistory.isNotEmpty()) state else state.copy(requestHistory = history.map { snapshot ->
                        RequestHistoryItem(
                            requestId = snapshot.requestId,
                            status = snapshot.status,
                            notificationsCreated = snapshot.notificationsCreated,
                            matchesResponded = snapshot.matchesResponded
                        )
                    })
                }
            }
        }
        refreshHistory()
        if (enablePolling) {
            viewModelScope.launch {
                while (true) {
                    delay(30_000)
                    val current = _uiState.value.activeRequest
                    if (current != null && !current.isTerminal) {
                        runCatching { repository.refreshActiveRequest(current.requestId) }
                        refreshContacts(current.requestId)
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
            EmergencyRequestAction.SaveDraft -> saveDraft(manual = true)
            EmergencyRequestAction.DraftSaveHandled -> _uiState.update { it.copy(draftSavedManually = false) }
            EmergencyRequestAction.Submit -> requestSubmit()
            EmergencyRequestAction.ConfirmCriticalSubmit -> {
                _uiState.update { it.copy(criticalConfirmationVisible = false) }
                submit()
            }
            EmergencyRequestAction.DismissCriticalSubmit -> _uiState.update { it.copy(criticalConfirmationVisible = false) }
            EmergencyRequestAction.SendManualBroadcast -> sendManualBroadcast()
            EmergencyRequestAction.RefreshStatus -> refreshStatus()
            is EmergencyRequestAction.OpenRequest -> openRequest(action.requestId)
            is EmergencyRequestAction.ShowContactResults -> showContactResults(action.requestId)
            is EmergencyRequestAction.RefreshContacts -> refreshContacts(action.requestId)
            EmergencyRequestAction.RefreshHistory -> refreshHistory()
            EmergencyRequestAction.CancelRequest -> cancelRequest()
            EmergencyRequestAction.FulfillRequest -> fulfillRequest()
            EmergencyRequestAction.Retry -> submit()
            is EmergencyRequestAction.ToggleDonorSelection -> toggleDonor(action.donorId)
            EmergencyRequestAction.ContactSelectedDonors -> contactSelectedDonors()
            is EmergencyRequestAction.UpdateContactStatus -> updateContactStatus(action.donorId, action.status)
            is EmergencyRequestAction.ReportContact -> moderateContact(action.donorId) { requestId, donorId -> repository.reportContact(requestId, donorId, action.reason) }
            is EmergencyRequestAction.BlockContact -> moderateContact(action.donorId) { requestId, donorId -> repository.blockContact(requestId, donorId) }
            is EmergencyRequestAction.SetGpsLocation -> updateDraft {
                it.copy(
                    requesterLatitude = action.latitude,
                    requesterLongitude = action.longitude,
                    locationPrecisionMeters = action.precisionMeters,
                    facility = null
                )
            }
        }
    }

    private fun updateDraft(update: (EmergencyRequestDraft) -> EmergencyRequestDraft) {
        _uiState.update { it.copy(draft = update(it.draft), draftSaved = false, draftSavedManually = false, submission = SubmissionState.Idle) }
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

    private fun saveDraft(manual: Boolean = false) {
        val draft = _uiState.value.draft
        viewModelScope.launch {
            _uiState.update { it.copy(submission = SubmissionState.Saving) }
            runCatching { repository.saveDraft(draft) }
                .onSuccess { _uiState.update { it.copy(draftSaved = true, draftSavedManually = manual, submission = SubmissionState.Idle) } }
                .onFailure { _uiState.update { it.copy(submission = SubmissionState.Error("Draft is kept on this device; sync will retry.")) } }
        }
    }

    private fun submit() {
        draftSaveJob?.cancel()
        val draft = _uiState.value.draft
        viewModelScope.launch {
            _uiState.update { it.copy(submission = SubmissionState.Submitting) }
            when (val result = repository.submit(draft)) {
                is SubmitResult.MatchingStarted -> {
                    // Do not keep the submit button spinning while the optional contacts refresh waits on the API.
                    _uiState.update {
                        it.copy(
                            step = RequestStep.RESULTS,
                            submission = SubmissionState.Matching(result.requestId),
                            resultsRequestId = result.requestId,
                            discoveredDonors = result.donors,
                            matchesRefreshing = false,
                            matchesError = null,
                            selectedDonorIds = emptySet(),
                            contactRequestSent = false,
                            contacts = emptyList(),
                            contactsRefreshing = false,
                            contactsError = null
                        )
                    }
                    refreshContacts(result.requestId)
                }
                is SubmitResult.ContactRequested -> _uiState.update { it.copy(contactRequestSent = true) }
                is SubmitResult.ManualFallback -> _uiState.update { it.copy(submission = SubmissionState.ManualFallback(result.requestId, result.reason)) }
                is SubmitResult.Cancelled -> _uiState.update { it.copy(submission = SubmissionState.Idle) }
                is SubmitResult.Fulfilled -> _uiState.update { it.copy(submission = SubmissionState.Idle) }
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
                is SubmitResult.Fulfilled -> _uiState.update { it.copy(submission = SubmissionState.Idle) }
                is SubmitResult.OfflineQueued -> _uiState.update { it.copy(submission = SubmissionState.Error("You’re offline. No broadcast was sent.")) }
            }
        }
    }

    private fun toggleDonor(donorId: String) {
        _uiState.update { state ->
            val next = state.selectedDonorIds.toMutableSet().apply { if (!add(donorId)) remove(donorId) }
            state.copy(selectedDonorIds = next, contactRequestSent = false)
        }
    }

    private fun contactSelectedDonors() {
        val state = _uiState.value
        val requestId = (state.submission as? SubmissionState.Matching)?.requestId ?: return
        if (state.selectedDonorIds.isEmpty()) return
        val alreadyContacted = state.contacts.mapTo(mutableSetOf()) { it.donorId }
        val donorIds = state.selectedDonorIds.filterNot { it in alreadyContacted }
        if (donorIds.isEmpty()) return
        viewModelScope.launch {
            when (val result = repository.contactSelectedDonors(requestId, donorIds)) {
                is SubmitResult.ContactRequested -> {
                    val current = _uiState.value
                    if (current.resultsRequestId != requestId) return@launch
                    val newlyConfirmedIds = result.donorIds.distinct()
                    val localContacts = newlyConfirmedIds.map { id ->
                        current.contacts.firstOrNull { it.donorId == id } ?: RequesterContact(
                            donorId = id,
                            displayName = current.discoveredDonors.firstOrNull { it.donorId == id }?.displayName ?: "Selected donor",
                            status = "pending"
                        )
                    }
                    val refreshed = try {
                        repository.refreshContacts(requestId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    _uiState.update { state ->
                        if (state.resultsRequestId != requestId) state else {
                            val contacts = if (refreshed == null) {
                                mergeContacts(state.contacts, localContacts)
                            } else {
                                mergeContacts(state.contacts + localContacts, refreshed)
                            }
                            val selected = if (refreshed == null) state.selectedDonorIds
                                else state.selectedDonorIds - contacts.map { it.donorId }.toSet()
                            state.copy(
                                contactRequestSent = state.contactRequestSent || newlyConfirmedIds.isNotEmpty(),
                                contacts = contacts,
                                selectedDonorIds = selected,
                                contactsRefreshing = false,
                                contactsError = if (refreshed == null) "The contact request was sent, but its latest status could not be loaded. Check again shortly." else null
                            )
                        }
                    }
                }
                is SubmitResult.Error -> _uiState.update { it.copy(submission = SubmissionState.Error(result.message)) }
                else -> Unit
            }
        }
    }

    private fun updateContactStatus(donorId: String, status: String) {
        val requestId = (_uiState.value.submission as? SubmissionState.Matching)?.requestId ?: _uiState.value.activeRequest?.requestId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(contactActionInFlightDonorId = donorId) }
            runCatching { repository.updateContactStatus(requestId, donorId, status) }
                .onSuccess { updated ->
                    _uiState.update { state ->
                        state.copy(
                            contacts = state.contacts.map { if (it.donorId == donorId) updated else it },
                            contactActionInFlightDonorId = null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            contactActionInFlightDonorId = null,
                            submission = SubmissionState.Error(error.message ?: "Contact status could not be updated.")
                        )
                    }
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
            refreshContacts(requestId)
        }
    }

    private fun openRequest(requestId: String) {
        refreshContacts(requestId)
        viewModelScope.launch {
            _uiState.update { it.copy(statusRefreshing = true) }
            val loaded = repository.refreshActiveRequest(requestId)
            _uiState.update {
                it.copy(
                    statusRefreshing = false,
                    submission = if (loaded == null) SubmissionState.Error("This request could not be loaded. Try refreshing.") else SubmissionState.Matching(requestId)
                )
            }
        }
    }

    private fun showContactResults(requestId: String) {
        val previous = _uiState.value
        val sameRequest = previous.resultsRequestId == requestId
        _uiState.update {
            it.copy(
                step = RequestStep.RESULTS,
                submission = SubmissionState.Matching(requestId),
                resultsRequestId = requestId,
                discoveredDonors = emptyList(),
                matchesRefreshing = true,
                matchesError = null,
                selectedDonorIds = emptySet(),
                contactRequestSent = sameRequest && it.contactRequestSent,
                contacts = if (sameRequest) it.contacts else emptyList(),
                contactsError = if (sameRequest) it.contactsError else null
            )
        }
        viewModelScope.launch {
            try {
                val matches = repository.refreshMatches(requestId)
                _uiState.update { state ->
                    if (state.resultsRequestId == requestId) state.copy(discoveredDonors = matches, matchesError = null) else state
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { state ->
                    if (state.resultsRequestId == requestId) state.copy(matchesError = "Saved donor matches could not be loaded. Check your connection and retry.") else state
                }
            } finally {
                _uiState.update { state ->
                    if (state.resultsRequestId == requestId) state.copy(matchesRefreshing = false) else state
                }
            }
        }
        refreshContacts(requestId)
    }

    private fun refreshContacts(requestId: String) {
        val switchingRequest = _uiState.value.resultsRequestId != requestId
        _uiState.update { state ->
            state.copy(
                resultsRequestId = requestId,
                discoveredDonors = if (switchingRequest) emptyList() else state.discoveredDonors,
                selectedDonorIds = if (switchingRequest) emptySet() else state.selectedDonorIds,
                contactRequestSent = if (switchingRequest) false else state.contactRequestSent,
                contacts = if (switchingRequest) emptyList() else state.contacts,
                matchesError = if (switchingRequest) null else state.matchesError,
                contactsRefreshing = true,
                contactsError = null
            )
        }
        viewModelScope.launch {
            try {
                val refreshed = repository.refreshContacts(requestId)
                _uiState.update { state ->
                    if (state.resultsRequestId != requestId) state else {
                        val contacts = mergeContacts(state.contacts, refreshed)
                        state.copy(
                            contacts = contacts,
                            selectedDonorIds = state.selectedDonorIds - contacts.map { it.donorId }.toSet(),
                            contactsRefreshing = false,
                            contactsError = null
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { state ->
                    if (state.resultsRequestId == requestId) state.copy(
                        contactsRefreshing = false,
                        contactsError = "Contact activity could not be loaded. Check your connection and retry."
                    ) else state
                }
            }
        }
    }

    private fun mergeContacts(existing: List<RequesterContact>, latest: List<RequesterContact>): List<RequesterContact> {
        val contacts = linkedMapOf<String, RequesterContact>()
        (existing + latest).forEach { contacts[it.donorId] = it }
        return contacts.values.toList()
    }

    private fun moderateContact(donorId: String, action: suspend (String, String) -> String) {
        val requestId = (_uiState.value.submission as? SubmissionState.Matching)?.requestId ?: _uiState.value.activeRequest?.requestId ?: return
        viewModelScope.launch {
            runCatching { action(requestId, donorId) }
                .onSuccess { result -> _uiState.update { it.copy(submission = SubmissionState.Error("Contact $result. Further contact is disabled until reviewed.")) } }
                .onFailure { error -> _uiState.update { it.copy(submission = SubmissionState.Error(error.message ?: "Contact safety action could not be completed.")) } }
        }
    }

    private fun refreshHistory() {
        if (historyRefreshJob?.isActive == true) return
        historyRefreshJob = viewModelScope.launch {
            _uiState.update { it.copy(historyRefreshing = true, historyError = null) }
            try {
                val history = repository.refreshRequestHistory()
                _uiState.update { it.copy(requestHistory = history) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(historyError = "Couldn’t load your requests. Check your connection and try again.") }
            } finally {
                _uiState.update { it.copy(historyRefreshing = false) }
            }
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

    private fun fulfillRequest() {
        val requestId = _uiState.value.activeRequest?.requestId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(statusRefreshing = true) }
            when (val result = repository.fulfillRequest(requestId)) {
                is SubmitResult.Fulfilled -> _uiState.update { it.copy(statusRefreshing = false) }
                is SubmitResult.Error -> _uiState.update { it.copy(statusRefreshing = false, submission = SubmissionState.Error(result.message)) }
                else -> _uiState.update { it.copy(statusRefreshing = false, submission = SubmissionState.Error("Unexpected fulfillment response.")) }
            }
        }
    }

    private fun validateStep(step: RequestStep, draft: EmergencyRequestDraft): String? = when (step) {
        RequestStep.BLOOD_NEED -> when {
            draft.bloodType == null -> "Select a blood type before continuing."
            draft.units !in 1..20 -> "Units must be between 1 and 20."
            else -> null
        }
        RequestStep.URGENCY -> if (draft.responseDeadline.isBlank()) "Choose a response deadline." else null
        RequestStep.LOCATION -> if (draft.requesterLatitude == null || draft.requesterLongitude == null) "Capture your approximate location or choose a manual location." else null
        RequestStep.CONTACT -> when {
            !draft.genuineRequestConfirmed -> "Confirm this is a genuine request for a verified facility."
            !draft.sharingConsentConfirmed -> "Confirm that request details may be shared with eligible donors."
            else -> null
        }
        RequestStep.REVIEW -> null
        RequestStep.RESULTS -> null
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
