package com.lifelink.app.core.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("lifelink_auth", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    fun save(session: AuthSession) {
        preferences.edit()
            .putString("access_token", session.accessToken)
            .putString("refresh_token", session.refreshToken)
            .putString("user_id", session.userId)
            .putString("email", session.email)
            .apply()
        _session.value = session
    }

    fun clear() {
        preferences.edit().clear().apply()
        _session.value = null
    }

    fun accessToken(): String? = _session.value?.accessToken

    fun userId(): String? = _session.value?.userId?.takeIf { it.isNotBlank() }

    private fun readSession(): AuthSession? {
        val accessToken = preferences.getString("access_token", null) ?: return null
        return AuthSession(
            accessToken = accessToken,
            refreshToken = preferences.getString("refresh_token", "").orEmpty(),
            userId = preferences.getString("user_id", "").orEmpty(),
            email = preferences.getString("email", "").orEmpty()
        )
    }
}

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String
)
