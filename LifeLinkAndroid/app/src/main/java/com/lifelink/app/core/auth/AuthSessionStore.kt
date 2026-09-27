package com.lifelink.app.core.auth

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthSessionStore(
    context: Context,
    private val onAccountRemoved: (String) -> Job? = { null }
) {
    private val preferences = context.getSharedPreferences("lifelink_auth", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    private val cleanupJobs = mutableMapOf<String, Job>()

    suspend fun save(session: AuthSession) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val cleanup = synchronized(this) {
                val pending = cleanupJobs[session.userId]
                if (pending == null || pending.isCompleted) {
                    saveImmediately(session)
                    return
                }
                pending
            }
            // Never publish this account until its previous work and rows are cleared.
            cleanup.join()
        }
    }

    private fun saveImmediately(session: AuthSession) {
        val previousOwner = userId()
        preferences.edit()
            .putString("access_token", session.accessToken)
            .putString("refresh_token", session.refreshToken)
            .putString("user_id", session.userId)
            .putString("email", session.email)
            .apply()
        _session.value = session
        if (previousOwner != null && previousOwner != session.userId) scheduleCleanup(previousOwner)
    }

    @Synchronized
    fun clear() {
        val previousOwner = userId()
        preferences.edit().clear().apply()
        _session.value = null
        if (previousOwner != null) scheduleCleanup(previousOwner)
    }

    @Synchronized
    fun replace(expected: AuthSession, replacement: AuthSession?): Boolean {
        if (_session.value != expected) return false
        if (replacement != null && replacement.userId != expected.userId) return false
        if (replacement == null) clear() else saveImmediately(replacement)
        return true
    }

    private fun scheduleCleanup(ownerId: String) {
        val job = onAccountRemoved(ownerId) ?: return
        cleanupJobs[ownerId] = job
        job.invokeOnCompletion {
            synchronized(this) {
                if (cleanupJobs[ownerId] === job) cleanupJobs.remove(ownerId)
            }
        }
    }

    fun accessToken(): String? = _session.value?.accessToken

    fun userId(): String? = _session.value?.userId?.takeIf { it.isNotBlank() }

    private fun readSession(): AuthSession? {
        val accessToken = preferences.getString("access_token", null)?.takeIf { it.isNotBlank() } ?: return null
        val userId = preferences.getString("user_id", null)?.takeIf { it.isNotBlank() } ?: return null
        return AuthSession(
            accessToken = accessToken,
            refreshToken = preferences.getString("refresh_token", "").orEmpty(),
            userId = userId,
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
