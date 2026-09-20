package com.lifelink.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifelink.app.core.auth.AuthResult
import com.lifelink.app.core.auth.AuthSession
import com.lifelink.app.core.auth.SupabaseAuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data object SessionExpired : AuthState
    data class SignedIn(val session: AuthSession) : AuthState
    data object EmailConfirmationRequired : AuthState
    data class Message(val text: String, val isError: Boolean = false) : AuthState
    data class Error(val message: String) : AuthState
}

class AuthViewModel(private val repository: SupabaseAuthRepository) : ViewModel() {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.session.collect { session ->
                if (session != null) _state.value = AuthState.SignedIn(session)
                else if (_state.value !is AuthState.EmailConfirmationRequired) _state.value = AuthState.SignedOut
            }
        }
        viewModelScope.launch {
            repository.sessionExpired.collect { expired ->
                if (expired) _state.value = AuthState.SessionExpired
            }
        }
    }

    fun signIn(email: String, password: String) = authenticate { repository.signIn(email, password) }
    fun signUp(email: String, password: String) = authenticate { repository.signUp(email, password) }
    fun signOut() = repository.signOut()
    fun requestPasswordReset(email: String) = runRecovery(email) { repository.requestPasswordReset(email) }
    fun resendConfirmation(email: String) = runRecovery(email) { repository.resendConfirmation(email) }

    private fun runRecovery(email: String, action: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _state.value = AuthState.Loading
            action().onSuccess { _state.value = AuthState.Message("Check your inbox for the next step.") }
                .onFailure { _state.value = AuthState.Message(it.message ?: "Authentication email could not be sent.", true) }
        }
    }

    private fun authenticate(action: suspend () -> Result<AuthResult>) {
        viewModelScope.launch {
            _state.value = AuthState.Loading
            action().onSuccess { result ->
                _state.value = when (result) {
                    is AuthResult.SignedIn -> AuthState.SignedIn(result.session)
                    AuthResult.EmailConfirmationRequired -> AuthState.EmailConfirmationRequired
                }
            }.onFailure { _state.value = AuthState.Error(it.message ?: "Authentication failed") }
        }
    }
}

class AuthViewModelFactory(private val repository: SupabaseAuthRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthViewModel(repository) as T
}
