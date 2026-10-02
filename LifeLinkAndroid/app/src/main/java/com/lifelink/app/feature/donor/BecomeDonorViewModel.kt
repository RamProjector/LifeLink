package com.lifelink.app.feature.donor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        message = error.message ?: "Your donor profile could not be loaded.",
                    )
                }
        }
    }

    fun onAction(action: BecomeDonorAction) {
        when (action) {
            is BecomeDonorAction.UpdateDraft ->
                _state.value = _state.value.copy(draft = action.update(_state.value.draft), message = null)
            BecomeDonorAction.Save -> save()
            is BecomeDonorAction.SetAvailability -> setAvailability(action.availability)
            BecomeDonorAction.OptOut -> optOut()
            BecomeDonorAction.ClearMessage -> _state.value = _state.value.copy(message = null)
        }
    }

    private fun save() {
        val draft = _state.value.draft
        if (!draft.isSetupComplete) {
            _state.value = _state.value.copy(message = "Choose a blood type and location before saving.")
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
                            "Donor profile saved"
                        } else {
                            "Donor profile saved. An admin must verify you before you appear in matching."
                        },
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(saving = false, message = error.message ?: "Donor profile could not be saved.")
                }
        }
    }

    private fun setAvailability(availability: DonorAvailability) {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, message = null)
            runCatching { repository.setAvailability(availability) }
                .onSuccess { updated ->
                    _state.value = _state.value.copy(
                        saving = false,
                        draft = updated,
                        message = if (availability == DonorAvailability.AVAILABLE && !updated.verified) {
                            "Availability saved. You will be matched once an admin verifies you."
                        } else {
                            "Availability updated"
                        },
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(saving = false, message = error.message ?: "Availability could not be updated.")
                }
        }
    }

    private fun optOut() {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, message = null)
            runCatching { repository.optOut() }
                .onSuccess {
                    _state.value = BecomeDonorUiState(loading = false, optedIn = false, message = "You are no longer a donor.")
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(saving = false, message = error.message ?: "Donor profile could not be removed.")
                }
        }
    }
}

class BecomeDonorViewModelFactory(private val repository: DonorProfileRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = BecomeDonorViewModel(repository) as T
}
