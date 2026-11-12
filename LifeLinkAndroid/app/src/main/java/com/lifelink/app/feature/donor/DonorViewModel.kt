package com.lifelink.app.feature.donor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifelink.app.core.errors.userFacingError
import com.lifelink.app.core.ui.LifeLinkFeedbackLevel
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.DonorProfile
import com.lifelink.app.domain.DonorRepository
import com.lifelink.app.domain.DonorRequest
import com.lifelink.app.domain.DonorResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.io.IOException
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class DonorUiState(
    val profile: DonorProfile = DonorProfile(),
    val requests: List<DonorRequest> = emptyList(),
    val saving: Boolean = false,
    val message: String? = null,
    val messageLevel: LifeLinkFeedbackLevel = LifeLinkFeedbackLevel.Info,
    val profileDirty: Boolean = false,
    val requestsRefreshing: Boolean = false,
)

sealed interface DonorAction {
    data class UpdateDraft(val update: (DonorProfile) -> DonorProfile) : DonorAction
    data object SaveProfile : DonorAction
    data class SetLocation(val latitude: Double, val longitude: Double, val precisionMeters: Int) : DonorAction
    data class SetAvailability(val availability: DonorAvailability) : DonorAction
    data class Respond(val requestId: String, val response: DonorResponse) : DonorAction
    data object RefreshRequests : DonorAction
    data object ClearMessage : DonorAction
}

class DonorViewModel(
    private val repository: DonorRepository,
    private val enablePolling: Boolean = true,
    launchDurableWrite: ((suspend () -> Unit) -> Unit)? = null,
) : ViewModel() {
    // Accept/Decline must survive this ViewModel being torn down mid-write — e.g. a
    // sign-out that clears accountModels.viewModelStore a split second after the tap
    // (see MainActivity). Falls back to viewModelScope for callers (tests, previews)
    // that don't wire a real durable write.
    private val launchDurableWrite: (suspend () -> Unit) -> Unit =
        launchDurableWrite ?: { block -> viewModelScope.launch { block() } }

    private val _state = MutableStateFlow(DonorUiState())
    val state: StateFlow<DonorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.observeProfile(), repository.observeRequests()) { profile, requests -> profile to requests }
                .collect { (profile, requests) ->
                    _state.value = _state.value.let { current ->
                        current.copy(
                            // A profile observer can emit the old database row
                            // while GPS or form edits are still unsaved. Never
                            // replace those local edits with stale persisted data.
                            profile = if (current.profileDirty) current.profile else profile,
                            requests = if (profile.isSetupComplete) requests else emptyList(),
                        )
                    }
                }
        }
        if (enablePolling) {
            viewModelScope.launch {
                while (true) {
                    runCatching { repository.refresh() }
                    delay(30_000)
                }
            }
        }
    }

    fun onAction(action: DonorAction) {
        when (action) {
            is DonorAction.UpdateDraft -> _state.value = _state.value.copy(
                profile = action.update(_state.value.profile),
                profileDirty = true,
                message = null,
                messageLevel = LifeLinkFeedbackLevel.Info,
            )
            DonorAction.SaveProfile -> saveProfile(_state.value.profile)
            is DonorAction.SetLocation -> _state.value = _state.value.copy(
                profile = _state.value.profile.copy(
                    latitude = action.latitude,
                    longitude = action.longitude,
                    locationPrecisionMeters = action.precisionMeters,
                ),
                message = "Location captured; save your profile to update matching.",
                messageLevel = LifeLinkFeedbackLevel.Info,
                profileDirty = true,
            )
            is DonorAction.SetAvailability -> setAvailability(action.availability)
            is DonorAction.Respond -> respond(action.requestId, action.response)
            DonorAction.RefreshRequests -> refreshRequests()
            DonorAction.ClearMessage -> _state.value = _state.value.copy(
                message = null,
                messageLevel = LifeLinkFeedbackLevel.Info,
            )
        }
    }

    private fun saveProfile(profile: DonorProfile) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                saving = true,
                message = "Saving donor profile\u2026",
                messageLevel = LifeLinkFeedbackLevel.Info,
            )
            runCatching { repository.saveProfile(profile) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        profile = profile,
                        saving = false,
                        message = "Profile saved.",
                        messageLevel = LifeLinkFeedbackLevel.Success,
                        profileDirty = false,
                    )
                    // A blood-type change must re-evaluate matching immediately, so
                    // pull the donor's inbox again instead of waiting for the poll.
                    runCatching { repository.refresh() }
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        saving = false,
                        profileDirty = true,
                        message = userFacingError(error, "Profile could not be saved. Check your connection and try again."),
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
                .onSuccess {
                    val updatedProfile = _state.value.profile.copy(availability = availability)
                    val profileDirty = _state.value.profileDirty
                    _state.value = _state.value.copy(
                        profile = updatedProfile,
                        saving = false,
                        message = availabilityMessage(availability, updatedProfile, profileDirty),
                        messageLevel = availabilityMessageLevel(availability, updatedProfile, profileDirty),
                    )
                }
                .onFailure { error ->
                    _state.value =
                        _state.value.copy(
                            saving = false,
                            message = userFacingError(error, "Availability could not be updated. Check your connection and try again."),
                            messageLevel = LifeLinkFeedbackLevel.Error,
                        )
                }
        }
    }

    private fun respond(requestId: String, response: DonorResponse) {
        _state.value = _state.value.copy(
            saving = true,
            message = "Sending your response\u2026",
            messageLevel = LifeLinkFeedbackLevel.Info,
        )
        launchDurableWrite {
            val result = try {
                repository.respond(requestId, response)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                Result.failure(error)
            } catch (error: HttpException) {
                Result.failure(error)
            } catch (error: IllegalStateException) {
                Result.failure(error)
            } catch (error: IllegalArgumentException) {
                Result.failure(error)
            } catch (error: SecurityException) {
                Result.failure(error)
            }
            result
                .onSuccess {
                    _state.value = _state.value.copy(
                        saving = false,
                        message = "Response sent.",
                        messageLevel = LifeLinkFeedbackLevel.Success,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        saving = false,
                        message = userFacingError(error, "Response could not be sent. Check your connection and try again."),
                        messageLevel = LifeLinkFeedbackLevel.Error,
                    )
                }
        }
    }

    private fun refreshRequests() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                requestsRefreshing = true,
                message = null,
                messageLevel = LifeLinkFeedbackLevel.Info,
            )
            runCatching { repository.refresh() }
                .onSuccess {
                    _state.value = _state.value.copy(
                        requestsRefreshing = false,
                        message = "Requests refreshed.",
                        messageLevel = LifeLinkFeedbackLevel.Success,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        requestsRefreshing = false,
                        message = userFacingError(error, "Requests could not be refreshed. Check your connection and try again."),
                        messageLevel = LifeLinkFeedbackLevel.Error,
                    )
                }
        }
    }

    private fun availabilityMessage(
        availability: DonorAvailability,
        profile: DonorProfile,
        profileDirty: Boolean,
    ): String =
        when (availability) {
            DonorAvailability.AVAILABLE -> when {
                profileDirty ->
                    "Availability set to Available. Save your profile edits before relying on matching status; " +
                        "a recent location is also required."
                !profile.verified -> "Availability is Available, but you will not appear in matching until your profile is verified."
                !profile.profileVisible -> "Availability is Available, but your profile is hidden from matching."
                else -> "Availability set to Available. Matching also requires a recent location."
            }
            DonorAvailability.PAUSED -> "Availability paused. You will not receive new matching requests until you resume."
            DonorAvailability.OFFLINE ->
                "Availability set to Offline. You will not receive new matching requests until you choose Available."
        }

    private fun availabilityMessageLevel(
        availability: DonorAvailability,
        profile: DonorProfile,
        profileDirty: Boolean,
    ): LifeLinkFeedbackLevel =
        when {
            availability == DonorAvailability.AVAILABLE && (profileDirty || !profile.verified || !profile.profileVisible) ->
                LifeLinkFeedbackLevel.Warning
            else -> LifeLinkFeedbackLevel.Success
        }
}

class DonorViewModelFactory(
    private val repository: DonorRepository,
    private val launchDurableWrite: ((suspend () -> Unit) -> Unit)? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DonorViewModel(repository, launchDurableWrite = launchDurableWrite) as T
}
