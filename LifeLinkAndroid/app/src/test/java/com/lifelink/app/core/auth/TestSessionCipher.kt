package com.lifelink.app.core.auth

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Uses an in-memory AES-GCM key so persistence tests do not depend on an
 * emulator-provided Android Keystore implementation.
 */
internal class TestSessionCipher : SessionCipher {
    override fun encrypt(plaintext: ByteArray): EncryptedSessionPayload {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(ASSOCIATED_DATA)
        return EncryptedSessionPayload(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.doFinal(ciphertext)
    }

    override fun deleteKey() = Unit

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        val ASSOCIATED_DATA = "lifelink-auth-session-test".toByteArray()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
