package com.lifelink.app.feature.donor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.DonorProfile
import com.lifelink.app.domain.DonorRepository
import com.lifelink.app.domain.DonorRequest
import com.lifelink.app.domain.DonorResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class DonorUiState(
    val profile: DonorProfile = DonorProfile(),
    val requests: List<DonorRequest> = emptyList(),
    val saving: Boolean = false,
    val message: String? = null
)

sealed interface DonorAction {
    data class UpdateProfile(val profile: DonorProfile) : DonorAction
    data class SetLocation(val latitude: Double, val longitude: Double, val precisionMeters: Int) : DonorAction
    data class SetAvailability(val availability: DonorAvailability) : DonorAction
    data class Respond(val requestId: String, val response: DonorResponse) : DonorAction
    data object ClearMessage : DonorAction
}

class DonorViewModel(private val repository: DonorRepository) : ViewModel() {
    private val _state = MutableStateFlow(DonorUiState())
    val state: StateFlow<DonorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.observeProfile(), repository.observeRequests()) { profile, requests -> profile to requests }
                .collect { (profile, requests) ->
                    _state.value = _state.value.copy(
                        profile = profile,
                        requests = if (profile.isSetupComplete) requests else emptyList()
                    )
                }
        }
        viewModelScope.launch {
            while (true) {
                runCatching { repository.refresh() }
                delay(30_000)
            }
        }
    }

    fun onAction(action: DonorAction) {
        when (action) {
            is DonorAction.UpdateProfile -> saveProfile(action.profile)
            is DonorAction.SetLocation -> _state.value = _state.value.copy(
                profile = _state.value.profile.copy(
                    latitude = action.latitude,
                    longitude = action.longitude,
                    locationPrecisionMeters = action.precisionMeters
                ),
                message = "Location selected. Save the profile to complete setup."
            )
            is DonorAction.SetAvailability -> setAvailability(action.availability)
            is DonorAction.Respond -> respond(action.requestId, action.response)
            DonorAction.ClearMessage -> _state.value = _state.value.copy(message = null)
        }
    }

    private fun saveProfile(profile: DonorProfile) {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true)
            runCatching { repository.saveProfile(profile) }
                .onSuccess { _state.value = _state.value.copy(saving = false, message = "Donor profile saved") }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        saving = false,
                        message = error.message ?: "Profile could not be saved. Check your connection and try again."
                    )
                }
        }
    }

    private fun setAvailability(availability: DonorAvailability) {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true)
            runCatching { repository.setAvailability(availability) }
                .onSuccess { _state.value = _state.value.copy(saving = false, message = "Availability updated") }
                .onFailure { error -> _state.value = _state.value.copy(saving = false, message = error.message ?: "Availability could not be updated.") }
        }
    }

    private fun respond(requestId: String, response: DonorResponse) {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true)
            repository.respond(requestId, response)
                .onSuccess { _state.value = _state.value.copy(saving = false, message = "Response sent") }
                .onFailure { _state.value = _state.value.copy(saving = false, message = "Response could not be sent") }
        }
    }
}

class DonorViewModelFactory(private val repository: DonorRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DonorViewModel(repository) as T
}
