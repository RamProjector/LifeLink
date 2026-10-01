package com.lifelink.app.core.auth

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Supabase auth wire contract.
 *
 * The release build runs R8, which renames Kotlin property names. If the auth
 * DTOs are not pinned with @SerializedName (and kept by proguard-rules.pro),
 * Gson serializes the sign-in body as {"a":...,"b":...} and Supabase answers
 * "missing email or phone" even though the form sent a valid address. These
 * tests fail loudly if that contract regresses.
 */
class AuthRequestSerializationTest {
    private val gson = Gson()

    @Test
    fun signInRequestUsesEmailAndPasswordFieldNames() {
        val json = gson.toJson(AuthRequest("user@example.test", "hunter2"))
        assertEquals("""{"email":"user@example.test","password":"hunter2"}""", json)
    }

    @Test
    fun passwordRecoveryRequestUsesEmailAndRedirectTo() {
        val json = gson.toJson(PasswordRecoveryRequest("user@example.test", "lifelink://auth/recovery"))
        assertTrue(json.contains("\"email\":\"user@example.test\""))
        assertTrue(json.contains("\"redirect_to\":\"lifelink://auth/recovery\""))
    }

    @Test
    fun resendRequestUsesTypeAndEmail() {
        val json = gson.toJson(ResendRequest("signup", "user@example.test"))
        assertTrue(json.contains("\"type\":\"signup\""))
        assertTrue(json.contains("\"email\":\"user@example.test\""))
    }

    @Test
    fun authResponseParsesSnakeCaseTokens() {
        val body = """{"access_token":"a","refresh_token":"r","user":{"id":"u1","email":"user@example.test"}}"""
        val parsed = gson.fromJson(body, SupabaseAuthResponse::class.java)
        assertEquals("a", parsed.accessToken)
        assertEquals("r", parsed.refreshToken)
        assertEquals("u1", parsed.user?.id)
        assertEquals("user@example.test", parsed.user?.email)
    }
}
