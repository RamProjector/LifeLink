package com.lifelink.app.core.auth

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SupabasePasswordRecoveryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SupabaseAuthRepository
    private lateinit var sessionStore: AuthSessionStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = AuthSessionStore(ApplicationProvider.getApplicationContext<Context>()).also { it.clear() }
        repository = SupabaseAuthRepository(
            sessionStore = sessionStore,
            baseUrl = server.url("/").toString(),
            publishableKey = "test-publishable-key",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun resetRedirectIsSentAsQueryParameterAndEmailAsJson() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = repository.requestPasswordReset("user@example.test")

        assertTrue("The successful recovery response should be accepted", result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/v1/recover", request.requestUrl?.encodedPath)
        assertEquals("lifelink://auth/recovery", request.requestUrl?.queryParameter("redirect_to"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("user@example.test", body.get("email").asString)
        assertEquals(1, body.size())
    }

    @Test
    fun emailChangeUsesAuthenticatedUserEndpointAndDedicatedConfirmationRedirect() = runBlocking {
        sessionStore.save(AuthSession("access-token", "refresh-token", "user-1", "old@example.test"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"user-1","email":"old@example.test"}"""),
        )

        val result = repository.requestEmailChange("user-1", "access-token", "new@example.test")

        assertTrue("Supabase should accept the email-change request", result.isSuccess)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/auth/v1/user", request.requestUrl?.encodedPath)
        assertEquals("lifelink://auth/email-change", request.requestUrl?.queryParameter("redirect_to"))
        assertEquals("Bearer access-token", request.getHeader("Authorization"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("new@example.test", body.get("email").asString)
        assertEquals(1, body.size())
    }

    @Test
    fun emailChangeRefreshesExpiredAccessTokenAndRetriesOnce() = runBlocking {
        sessionStore.save(AuthSession("expired-token", "refresh-token", "user-1", "old@example.test"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"msg":"JWT expired"}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"fresh-token","refresh_token":"next-refresh","user":{"id":"user-1","email":"old@example.test"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"user-1","email":"old@example.test"}"""),
        )

        val result = repository.requestEmailChange("user-1", "expired-token", "new@example.test")

        assertTrue("The request should succeed after one token refresh", result.isSuccess)
        val first = server.takeRequest()
        assertEquals("Bearer expired-token", first.getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/auth/v1/token", refresh.requestUrl?.encodedPath)
        assertEquals("refresh_token", refresh.requestUrl?.queryParameter("grant_type"))
        val retry = server.takeRequest()
        assertEquals("Bearer fresh-token", retry.getHeader("Authorization"))
        assertEquals("new@example.test", JsonParser.parseString(retry.body.readUtf8()).asJsonObject.get("email").asString)
        assertEquals("user-1", sessionStore.session.value?.userId)
        assertEquals("fresh-token", sessionStore.session.value?.accessToken)
    }

    @Test
    fun emailChangeConfirmationRequiresTheDedicatedCallbackType() {
        val valid = repository.parseAuthCallback(
            Uri.parse("lifelink://auth/email-change?type=email_change&access_token=confirmed-token"),
        ).getOrThrow()
        assertEquals(AuthCallbackKind.EMAIL_CHANGE, valid.kind)

        val invalid = repository.parseAuthCallback(
            Uri.parse("lifelink://auth/email-change?type=signup&access_token=untrusted-token"),
        )
        assertTrue(invalid.isFailure)
    }

    @Test
    fun invalidEmailChangeIsRejectedBeforeNetworkRequest() = runBlocking {
        val result = repository.requestEmailChange("user-1", "access-token", "not-an-email")

        assertEquals("Enter a valid email address.", result.exceptionOrNull()?.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun invalidSupabaseApiKeyIsNotReportedAsAnInvalidEmail() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"msg":"Invalid API key"}"""))

        val result = repository.requestPasswordReset("user@example.test")

        assertEquals(
            "LifeLink could not authenticate with Supabase. Check the Supabase URL and publishable key in local.properties.",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun emailFormatErrorStillExplainsThatTheEmailIsInvalid() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"msg":"Unable to validate email address: invalid format"}"""),
        )

        val result = repository.requestPasswordReset("invalid-email")

        assertEquals("Please enter a valid email address.", result.exceptionOrNull()?.message)
    }
}
