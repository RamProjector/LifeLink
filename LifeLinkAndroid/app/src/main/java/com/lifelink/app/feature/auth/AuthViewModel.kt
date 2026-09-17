package com.lifelink.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifelink.app.core.auth.AuthSession
import com.lifelink.app.core.auth.SupabaseAuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val session: AuthSession) : AuthState
    data class Error(val message: String) : AuthState
}

class AuthViewModel(private val repository: SupabaseAuthRepository) : ViewModel() {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.session.collect { session ->
                _state.value = if (session == null) AuthState.SignedOut else AuthState.SignedIn(session)
            }
        }
    }

    fun signIn(email: String, password: String) = authenticate { repository.signIn(email, password) }
    fun signUp(email: String, password: String) = authenticate { repository.signUp(email, password) }
    fun signOut() = repository.signOut()

    private fun authenticate(action: suspend () -> Result<AuthSession>) {
        viewModelScope.launch {
            _state.value = AuthState.Loading
            action().onFailure { _state.value = AuthState.Error(it.message ?: "Authentication failed") }
        }
    }
}

class AuthViewModelFactory(private val repository: SupabaseAuthRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthViewModel(repository) as T
}
