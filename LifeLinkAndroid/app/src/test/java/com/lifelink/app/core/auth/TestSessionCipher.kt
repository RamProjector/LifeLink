package com.lifelink.app.core.auth

/**
 * Reversible test double for exercising AuthSessionStore persistence without
 * depending on an emulator-provided Android Keystore implementation.
 * Production code always uses AndroidKeystoreSessionCipher.
 */
internal class TestSessionCipher : SessionCipher {
    private val iv = ByteArray(12) { index -> (index + 1).toByte() }

    override fun encrypt(plaintext: ByteArray): EncryptedSessionPayload =
        EncryptedSessionPayload(
            iv = iv.copyOf(),
            ciphertext = plaintext.map { (it.toInt() xor MASK).toByte() }.toByteArray(),
        )

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.contentEquals(this.iv)) { "Invalid test IV" }
        return ciphertext.map { (it.toInt() xor MASK).toByte() }.toByteArray()
    }

    override fun deleteKey() = Unit

    private companion object {
        const val MASK = 0x5A
    }
}
