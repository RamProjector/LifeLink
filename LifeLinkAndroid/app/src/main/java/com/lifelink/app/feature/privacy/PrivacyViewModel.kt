package com.lifelink.app.feature.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifelink.app.domain.ChatMessage
import com.lifelink.app.domain.ContactShare
import com.lifelink.app.domain.DonorMap
import com.lifelink.app.domain.DonorMapVisibility
import com.lifelink.app.domain.MatchedDonorLocation
import com.lifelink.app.domain.PrivacyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PrivacyUiState(
    val map: DonorMap? = null,
    val mapLoading: Boolean = false,
    val mapError: String? = null,
    val visibility: DonorMapVisibility? = null,
    val visibilitySaving: Boolean = false,
    val matchedLocation: MatchedDonorLocation? = null,
    val locationLoading: Boolean = false,
    val conversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val contactShares: List<ContactShare> = emptyList(),
    val chatLoading: Boolean = false,
    val sending: Boolean = false,
    val message: String? = null
)

sealed interface PrivacyAction {
    data object LoadMap : PrivacyAction
    data class SetMapVisibility(val mapVisible: Boolean, val exactLocationSharingEnabled: Boolean) : PrivacyAction
    data class LoadMatchedLocation(val requestId: String, val donorId: String) : PrivacyAction
    data class ActivateLocationShare(val requestId: String, val donorId: String) : PrivacyAction
    data class RevokeLocationShare(val requestId: String, val donorId: String) : PrivacyAction
    data class OpenConversation(val requestId: String, val donorId: String) : PrivacyAction
    data class SendMessage(val body: String) : PrivacyAction
    data class ShareContact(val field: String, val value: String) : PrivacyAction
    data class ReportParticipant(val requestId: String, val donorId: String, val reason: String) : PrivacyAction
    data class BlockParticipant(val requestId: String, val donorId: String) : PrivacyAction
    data object ClearMessage : PrivacyAction
}

/**
 * Drives the donor map, matched-requester exact location, chat, and contact
 * sharing. Exact coordinates are never cached: [PrivacyAction.LoadMatchedLocation]
 * re-asks the server, which re-checks the live share.
 */
class PrivacyViewModel(
    private val repository: PrivacyRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PrivacyUiState())
    val state: StateFlow<PrivacyUiState> = _state.asStateFlow()

    fun onAction(action: PrivacyAction) {
        when (action) {
            PrivacyAction.LoadMap -> loadMap()
            is PrivacyAction.SetMapVisibility -> setVisibility(action.mapVisible, action.exactLocationSharingEnabled)
            is PrivacyAction.LoadMatchedLocation -> loadMatchedLocation(action.requestId, action.donorId)
            is PrivacyAction.ActivateLocationShare -> activateShare(action.requestId, action.donorId)
            is PrivacyAction.RevokeLocationShare -> revokeShare(action.requestId, action.donorId)
            is PrivacyAction.OpenConversation -> openConversation(action.requestId, action.donorId)
            is PrivacyAction.SendMessage -> sendMessage(action.body)
            is PrivacyAction.ShareContact -> shareContact(action.field, action.value)
            is PrivacyAction.ReportParticipant -> reportParticipant(action.requestId, action.donorId, action.reason)
            is PrivacyAction.BlockParticipant -> blockParticipant(action.requestId, action.donorId)
            PrivacyAction.ClearMessage -> _state.value = _state.value.copy(message = null)
        }
    }

    private fun loadMap() {
        viewModelScope.launch {
            _state.value = _state.value.copy(mapLoading = true, mapError = null)
            repository.donorMap()
                .onSuccess { _state.value = _state.value.copy(map = it, mapLoading = false) }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        mapLoading = false,
                        mapError = error.message ?: "The donor map could not be loaded."
                    )
                }
        }
    }

    private fun setVisibility(mapVisible: Boolean, exactLocationSharingEnabled: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(visibilitySaving = true, message = null)
            repository.setMapVisibility(mapVisible, exactLocationSharingEnabled)
                .onSuccess {
                    _state.value = _state.value.copy(
                        visibility = it,
                        visibilitySaving = false,
                        message = if (mapVisible) "You are visible on the donor map." else "You are hidden from the donor map."
                    )
                    if (mapVisible) loadMap()
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        visibilitySaving = false,
                        message = error.message ?: "Map visibility could not be updated."
                    )
                }
        }
    }

    private fun loadMatchedLocation(requestId: String, donorId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(locationLoading = true)
            repository.matchedDonorLocation(requestId, donorId)
                .onSuccess { _state.value = _state.value.copy(matchedLocation = it, locationLoading = false) }
                .onFailure { error ->
                    // Never keep a previously loaded pin on screen after a failed
                    // refresh: the share may have been revoked or expired.
                    _state.value = _state.value.copy(
                        matchedLocation = null,
                        locationLoading = false,
                        message = error.message ?: "Donor location could not be loaded."
                    )
                }
        }
    }

    private fun activateShare(requestId: String, donorId: String) {
        viewModelScope.launch {
            repository.activateLocationShare(requestId, donorId)
                .onSuccess {
                    _state.value = _state.value.copy(message = "Exact location sharing is active for this request.")
                    loadMatchedLocation(requestId, donorId)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(message = error.message ?: "Exact location sharing could not be activated.")
                }
        }
    }

    private fun revokeShare(requestId: String, donorId: String) {
        viewModelScope.launch {
            repository.revokeLocationShare(requestId, donorId)
                .onSuccess {
                    _state.value = _state.value.copy(
                        matchedLocation = null,
                        message = "Exact location sharing was revoked."
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(message = error.message ?: "Exact location sharing could not be revoked.")
                }
        }
    }

    private fun openConversation(requestId: String, donorId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(chatLoading = true, message = null)
            repository.openConversation(requestId, donorId)
                .onSuccess { conversation ->
                    _state.value = _state.value.copy(conversationId = conversation.conversationId)
                    refreshConversation(conversation.conversationId)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        chatLoading = false,
                        message = error.message ?: "The conversation could not be opened."
                    )
                }
        }
    }

    private fun refreshConversation(conversationId: String) {
        viewModelScope.launch {
            val messages = repository.messages(conversationId).getOrDefault(emptyList())
            val shares = repository.contactShares(conversationId).getOrDefault(emptyList())
            _state.value = _state.value.copy(messages = messages, contactShares = shares, chatLoading = false)
        }
    }

    private fun sendMessage(body: String) {
        val conversationId = _state.value.conversationId ?: return
        if (body.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true)
            repository.sendMessage(conversationId, body)
                .onSuccess {
                    _state.value = _state.value.copy(sending = false)
                    refreshConversation(conversationId)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(sending = false, message = error.message ?: "Message could not be sent.")
                }
        }
    }

    private fun shareContact(field: String, value: String) {
        val conversationId = _state.value.conversationId ?: return
        if (value.isBlank()) return
        viewModelScope.launch {
            repository.shareContact(conversationId, field, value)
                .onSuccess {
                    _state.value = _state.value.copy(message = "Your $field was shared and recorded.")
                    refreshConversation(conversationId)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(message = error.message ?: "Contact details could not be shared.")
                }
        }
    }

    private fun reportParticipant(requestId: String, donorId: String, reason: String) {
        viewModelScope.launch {
            repository.reportParticipant(requestId, donorId, reason)
                .onSuccess { _state.value = _state.value.copy(message = "Report submitted. Our team will review it.") }
                .onFailure { error -> _state.value = _state.value.copy(message = error.message ?: "Report could not be submitted.") }
        }
    }

    private fun blockParticipant(requestId: String, donorId: String) {
        viewModelScope.launch {
            repository.blockParticipant(requestId, donorId)
                .onSuccess { _state.value = _state.value.copy(message = "This person is now blocked.") }
                .onFailure { error -> _state.value = _state.value.copy(message = error.message ?: "Block could not be submitted.") }
        }
    }
}

class PrivacyViewModelFactory(
    private val repository: PrivacyRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PrivacyViewModel(repository) as T
}
