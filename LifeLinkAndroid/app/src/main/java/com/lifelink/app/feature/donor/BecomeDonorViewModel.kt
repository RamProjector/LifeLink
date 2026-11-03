package com.lifelink.app.feature.donor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifelink.app.core.errors.userFacingError
import com.lifelink.app.core.ui.LifeLinkFeedbackLevel
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.DonorProfileMe
import com.lifelink.app.domain.DonorProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the separate donor-profile flow.
 *
 * [optedIn] is false until the user creates a donor profile, so the screen can
 * show the "Become a donor" call to action rather than a half-filled form.
 */
data class BecomeDonorUiState(
    val loading: Boolean = true,
    val optedIn: Boolean = false,
    val draft: DonorProfileMe = DonorProfileMe(),
    val saving: Boolean = false,
    val message: String? = null,
    val messageLevel: LifeLinkFeedbackLevel = LifeLinkFeedbackLevel.Info,
)

sealed interface BecomeDonorAction {
    data class UpdateDraft(val update: (DonorProfileMe) -> DonorProfileMe) : BecomeDonorAction
    data object Save : BecomeDonorAction
    data class SetAvailability(val availability: DonorAvailability) : BecomeDonorAction
    data object OptOut : BecomeDonorAction
    data object ClearMessage : BecomeDonorAction
}

/**
 * Drives the donor-profile setup/edit screen.
 *
 * The account is created first; this ViewModel only ever reads and writes the
 * current user's own donor profile through [repository].
 */
class BecomeDonorViewModel(private val repository: DonorProfileRepository) : ViewModel() {
    private val _state = MutableStateFlow(BecomeDonorUiState())
    val state: StateFlow<BecomeDonorUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            runCatching { repository.load() }
                .onSuccess { profile ->
                    _state.value = _state.value.copy(
                        loading = false,
                        optedIn = profile != null,
                        draft = profile ?: DonorProfileMe(),
                        messageLevel = LifeLinkFeedbackLevel.Info,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        message = userFacingError(error, "Your donor profile could not be loaded. Check your connection and try again."),
                        messageLevel = LifeLinkFeedbackLevel.Error,
                    )
                }
        }
    }

    fun onAction(action: BecomeDonorAction) {
        when (action) {
            is BecomeDonorAction.UpdateDraft ->
                _state.value = _state.value.copy(
                    draft = action.update(_state.value.draft),
                    message = null,
                    messageLevel = LifeLinkFeedbackLevel.Info,
                )
            BecomeDonorAction.Save -> save()
            is BecomeDonorAction.SetAvailability -> setAvailability(action.availability)
            BecomeDonorAction.OptOut -> optOut()
            BecomeDonorAction.ClearMessage -> _state.value = _state.value.copy(message = null, messageLevel = LifeLinkFeedbackLevel.Info)
        }
    }

    private fun save() {
        val draft = _state.value.draft
        if (!draft.isSetupComplete) {
            _state.value = _state.value.copy(
                message = "Choose a blood type and location before saving.",
                messageLevel = LifeLinkFeedbackLevel.Warning,
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, message = null)
            runCatching { repository.save(draft) }
                .onSuccess { saved ->
                    _state.value = _state.value.copy(
                        saving = false,
                        optedIn = true,
                        draft = saved,
                        message = if (saved.verified) {
                            "Donor profile saved. Set availability to Available when you are ready to receive matching requests."
                        } else {
                            "Donor profile saved. You will not appear in matching until an admin verifies your profile."
                        },
                        messageLevel = if (saved.verified) LifeLinkFeedbackLevel.Success else LifeLinkFeedbackLevel.Warning,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        saving = false,
                        message = userFacingError(error, "Donor profile could not be saved. Check your connection and try again."),
                        messageLevel = LifeLinkFeedbackLevel.Error,
                    )
                }
        }
    }

    private fun setAvailability(availability: DonorAvailability) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                saving = true,
                message = "Updating availability\u2026",
                messageLevel = LifeLinkFeedbackLevel.Info,
            )
            runCatching { repository.setAvailability(availability) }
                .onSuccess { updated ->
                    _state.value = _state.value.copy(
                        saving = false,
                        draft = updated,
                        message = availabilityMessage(updated),
                        messageLevel = availabilityMessageLevel(updated),
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        saving = false,
                        message = userFacingError(error, "Availability could not be updated. Check your connection and try again."),
                        messageLevel = LifeLinkFeedbackLevel.Error,
                    )
                }
        }
    }

    private fun optOut() {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, message = null)
            runCatching { repository.optOut() }
                .onSuccess {
                    _state.value = BecomeDonorUiState(
                        loading = false,
                        optedIn = false,
                        message = "Your donor profile was removed.",
                        messageLevel = LifeLinkFeedbackLevel.Success,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        saving = false,
                        message = userFacingError(error, "Donor profile could not be removed. Check your connection and try again."),
                        messageLevel = LifeLinkFeedbackLevel.Error,
                    )
                }
        }
    }

    private fun availabilityMessage(profile: DonorProfileMe): String =
        when (profile.availability) {
            DonorAvailability.AVAILABLE -> when {
                !profile.verified -> "Availability is Available, but you will not appear in matching until your profile is verified."
                !profile.profileVisible -> "Availability is Available, but your profile is hidden from matching."
                else -> "Availability set to Available. Matching also requires a recent location."
            }
            DonorAvailability.PAUSED -> "Availability paused. You will not receive new matching requests until you resume."
            DonorAvailability.OFFLINE ->
                "Availability set to Offline. You will not receive new matching requests until you choose Available."
        }

    private fun availabilityMessageLevel(profile: DonorProfileMe): LifeLinkFeedbackLevel =
        when {
            profile.availability == DonorAvailability.AVAILABLE && (!profile.verified || !profile.profileVisible) ->
                LifeLinkFeedbackLevel.Warning
            else -> LifeLinkFeedbackLevel.Success
        }
}

class BecomeDonorViewModelFactory(private val repository: DonorProfileRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = BecomeDonorViewModel(repository) as T
}
