package com.lifelink.app.core.auth

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AuthSessionStore(context: Context, private val onAccountRemoved: (String) -> Job? = { null }) {
    private val legacyPreferences = context.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
    private val encryptedPreferences = context.getSharedPreferences(ENCRYPTED_PREFERENCES, Context.MODE_PRIVATE)
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
        persistSession(session)
        _session.value = session
        if (previousOwner != null && previousOwner != session.userId) scheduleCleanup(previousOwner)
    }

    @SuppressLint("ApplySharedPref")
    private fun persistSession(session: AuthSession) {
        val editor = encryptedPreferences.edit().clear()
        mapOf(
            ACCESS_TOKEN to session.accessToken,
            REFRESH_TOKEN to session.refreshToken,
            USER_ID to session.userId,
            EMAIL to session.email,
        ).forEach { (field, value) ->
            editor.putString(field, encrypt(field, value))
        }
        check(editor.commit()) { "Unable to persist encrypted auth session" }
        // Remove the legacy plaintext copy only after the encrypted copy reaches disk.
        check(legacyPreferences.edit().clear().commit()) { "Unable to remove plaintext auth session" }
    }

    @Synchronized
    fun clear() {
        val previousOwner = userId()
        encryptedPreferences.edit().clear().apply()
        legacyPreferences.edit().clear().apply()
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

    @SuppressLint("ApplySharedPref")
    private fun readSession(): AuthSession? {
        if (encryptedPreferences.contains(ACCESS_TOKEN)) {
            return runCatching {
                val accessToken = encryptedPreferences.getString(ACCESS_TOKEN, null)
                    ?.let { decrypt(ACCESS_TOKEN, it) }
                    ?.takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                val userId = encryptedPreferences.getString(USER_ID, null)
                    ?.let { decrypt(USER_ID, it) }
                    ?.takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                AuthSession(
                    accessToken = accessToken,
                    refreshToken = encryptedPreferences.getString(REFRESH_TOKEN, null)
                        ?.let { decrypt(REFRESH_TOKEN, it) }
                        .orEmpty(),
                    userId = userId,
                    email = encryptedPreferences.getString(EMAIL, null)
                        ?.let { decrypt(EMAIL, it) }
                        .orEmpty(),
                )
            }.getOrNull()
        }

        val accessToken = legacyPreferences.getString(ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val userId = legacyPreferences.getString(USER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val migrated = AuthSession(
            accessToken = accessToken,
            refreshToken = legacyPreferences.getString(REFRESH_TOKEN, "").orEmpty(),
            userId = userId,
            email = legacyPreferences.getString(EMAIL, "").orEmpty(),
        )
        val encrypted = runCatching { persistSession(migrated) }.isSuccess
        if (encrypted) return migrated

        // Fail closed if the device cannot encrypt the old session; require sign-in
        // rather than continuing to use a persistent plaintext credential.
        legacyPreferences.edit().clear().commit()
        return null
    }

    private fun encrypt(field: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(field.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(field: String, value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "Invalid encrypted session value" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(field.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES).toString(Charsets.UTF_8)
    }

    private fun encryptionKey(): SecretKey =
        synchronized(KEY_LOCK) {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .build(),
                    )
                }
                .generateKey()
        }

    private companion object {
        const val LEGACY_PREFERENCES = "lifelink_auth"
        const val ENCRYPTED_PREFERENCES = "lifelink_auth_encrypted"
        const val ACCESS_TOKEN = "access_token"
        const val REFRESH_TOKEN = "refresh_token"
        const val USER_ID = "user_id"
        const val EMAIL = "email"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lifelink_auth_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        val KEY_LOCK = Any()
    }
}

data class AuthSession(val accessToken: String, val refreshToken: String, val userId: String, val email: String)
