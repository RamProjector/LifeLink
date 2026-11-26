package com.lifelink.app.core.auth

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
import org.json.JSONObject
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncryptedSessionPayload(val iv: ByteArray, val ciphertext: ByteArray)

internal interface SessionCipher {
    fun encrypt(plaintext: ByteArray): EncryptedSessionPayload

    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray

    fun deleteKey()
}

/**
 * Encrypts the session payload with AES-256-GCM. The AES key is generated and
 * retained by Android Keystore; only ciphertext and the per-encryption IV are
 * written to app-private preferences.
 */
internal class AndroidKeystoreSessionCipher : SessionCipher {
    override fun encrypt(plaintext: ByteArray): EncryptedSessionPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(ASSOCIATED_DATA)
        return EncryptedSessionPayload(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == GCM_IV_LENGTH_BYTES) { "Invalid encrypted session IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.doFinal(ciphertext)
    }

    override fun deleteKey() {
        synchronized(KEY_LOCK) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = runCatching { keyStore.getKey(KEY_ALIAS, null) as? SecretKey }.getOrNull()
        if (existing != null) return@synchronized existing

        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }

    private fun loadKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("LifeLink's encrypted session key is unavailable")
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.lifelink.auth.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
        val ASSOCIATED_DATA = "lifelink-auth-session-v1".toByteArray(UTF_8)
        val KEY_LOCK = Any()
    }
}

class AuthSessionStore internal constructor(
    context: Context,
    private val sessionCipher: SessionCipher = AndroidKeystoreSessionCipher(),
    private val onAccountRemoved: (String) -> Job? = { null },
) {
    private val preferences = context.getSharedPreferences("lifelink_auth_secure", Context.MODE_PRIVATE)
    private val legacyPreferences = context.getSharedPreferences("lifelink_auth", Context.MODE_PRIVATE)
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
        persistEncrypted(session)
        check(legacyPreferences.edit().clear().commit()) {
            "LifeLink could not securely remove the legacy plaintext session"
        }
        _session.value = session
        if (previousOwner != null && previousOwner != session.userId) scheduleCleanup(previousOwner)
    }

    @Synchronized
    fun clear() {
        val previousOwner = userId()
        preferences.edit().clear().commit()
        legacyPreferences.edit().clear().commit()
        runCatching { sessionCipher.deleteKey() }
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

    private fun persistEncrypted(session: AuthSession) {
        val payload = sessionCipher.encrypt(serializeSession(session))
        val saved = preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(payload.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(payload.ciphertext, Base64.NO_WRAP))
            .commit()
        check(saved) { "LifeLink could not securely save the session" }
    }

    private fun readSession(): AuthSession? {
        val iv = preferences.getString(KEY_IV, null)
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)
        if (iv != null && ciphertext != null) {
            return runCatching {
                val session = deserializeSession(
                    sessionCipher.decrypt(
                        Base64.decode(iv, Base64.NO_WRAP),
                        Base64.decode(ciphertext, Base64.NO_WRAP),
                    ),
                )
                require(session.accessToken.isNotBlank() && session.userId.isNotBlank())
                check(legacyPreferences.edit().clear().commit())
                session
            }.getOrNull() ?: invalidateUnreadableSession()
        }
        if (iv != null || ciphertext != null) return invalidateUnreadableSession()
        return migrateLegacySession()
    }

    private fun migrateLegacySession(): AuthSession? {
        val accessToken = legacyPreferences.getString("access_token", null)?.takeIf { it.isNotBlank() }
        val userId = legacyPreferences.getString("user_id", null)?.takeIf { it.isNotBlank() }
        if (accessToken == null || userId == null) {
            if (legacyPreferences.all.isNotEmpty()) legacyPreferences.edit().clear().commit()
            return null
        }

        val legacySession = AuthSession(
            accessToken = accessToken,
            refreshToken = legacyPreferences.getString("refresh_token", "").orEmpty(),
            userId = userId,
            email = legacyPreferences.getString("email", "").orEmpty(),
        )
        return runCatching {
            persistEncrypted(legacySession)
            check(legacyPreferences.edit().clear().commit())
            legacySession
        }.getOrElse {
            preferences.edit().clear().commit()
            legacyPreferences.edit().clear().commit()
            runCatching { sessionCipher.deleteKey() }
            null
        }
    }

    private fun invalidateUnreadableSession(): AuthSession? {
        preferences.edit().clear().commit()
        legacyPreferences.edit().clear().commit()
        runCatching { sessionCipher.deleteKey() }
        return null
    }

    private fun serializeSession(session: AuthSession): ByteArray =
        JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("user_id", session.userId)
            .put("email", session.email)
            .toString()
            .toByteArray(UTF_8)

    private fun deserializeSession(payload: ByteArray): AuthSession {
        val json = JSONObject(payload.toString(UTF_8))
        return AuthSession(
            accessToken = json.optString("access_token"),
            refreshToken = json.optString("refresh_token"),
            userId = json.optString("user_id"),
            email = json.optString("email"),
        )
    }

    private companion object {
        const val KEY_IV = "session_iv"
        const val KEY_CIPHERTEXT = "session_ciphertext"
    }
}

data class AuthSession(val accessToken: String, val refreshToken: String, val userId: String, val email: String)
