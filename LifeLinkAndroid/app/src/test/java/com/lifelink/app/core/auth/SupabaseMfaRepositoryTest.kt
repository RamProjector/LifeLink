package com.lifelink.app.core.auth

import android.content.Context
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
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SupabaseMfaRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var sessionStore: AuthSessionStore
    private lateinit var repository: SupabaseAuthRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("lifelink_auth_secure", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("lifelink_auth", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.start()
        sessionStore = AuthSessionStore(context, TestSessionCipher())
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
    fun listsOnlyTheSupabaseUserFactorInventoryResponse() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"factors":[{"id":"factor-1","factor_type":"totp","status":"verified","friendly_name":"Primary"}]}""",
            ),
        )
        val session = AuthSession("access-token", "refresh-token", "user-1", "admin@example.test")

        val result = repository.listMfaFactors(session)

        assertTrue(result.isSuccess)
        assertEquals("factor-1", result.getOrThrow().single().id)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/auth/v1/user", request.requestUrl?.encodedPath)
        assertEquals("Bearer access-token", request.getHeader("Authorization"))
        assertEquals("test-publishable-key", request.getHeader("apikey"))
    }

    @Test
    fun enrollmentSendsTotpTypeAndFriendlyName() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"factor-1","friendly_name":"Primary","totp":{"secret":"TESTSECRET","uri":"otpauth://totp/LifeLink",""" +
                    """"qr_code":"data:image/svg+xml;base64,PHN2Zz4="}}""",
            ),
        )

        val result = repository.enrollMfaTotp(
            AuthSession("access-token", "refresh-token", "user-1", "admin@example.test"),
            "Primary",
        )

        assertTrue(result.isSuccess)
        assertEquals("TESTSECRET", result.getOrThrow().totp?.secret)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/v1/factors", request.requestUrl?.encodedPath)
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("totp", body.get("factor_type").asString)
        assertEquals("Primary", body.get("friendly_name").asString)
        assertEquals("LifeLink", body.get("issuer").asString)
    }

    @Test
    fun challengeUsesTheSelectedFactorAndAnEmptyBody() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"challenge-1"}"""))

        val result = repository.challengeMfa(
            AuthSession("access-token", "refresh-token", "user-1", "admin@example.test"),
            "factor-1",
        )

        assertEquals("challenge-1", result.getOrThrow())
        val request = server.takeRequest()
        assertEquals("/auth/v1/factors/factor-1/challenge", request.requestUrl?.encodedPath)
        assertEquals("{}", request.body.readUtf8())
    }

    @Test
    fun verificationRequiresSixDigitsAndPersistsTheRefreshedAal2Session() = runBlocking {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"aal":"aal2"}""".toByteArray())
        val token = "header.$payload.signature"
        server.enqueue(
            MockResponse().setBody(
                """{"access_token":"$token","refresh_token":"rotated-refresh","user":{"id":"user-1","email":"admin@example.test"}}""",
            ),
        )
        val current = AuthSession("old-access", "old-refresh", "user-1", "admin@example.test")

        val result = repository.verifyMfaTotp(current, "factor-1", "challenge-1", "123456")

        assertTrue(result.isSuccess)
        assertEquals("aal2", repository.assuranceLevel(result.getOrThrow().accessToken))
        assertEquals("rotated-refresh", sessionStore.session.value?.refreshToken)
        assertEquals("user-1", sessionStore.session.value?.userId)
        val request = server.takeRequest()
        assertEquals("/auth/v1/factors/factor-1/verify", request.requestUrl?.encodedPath)
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("factor-1", body.get("factor_id").asString)
        assertEquals("challenge-1", body.get("challenge_id").asString)
        assertEquals("123456", body.get("code").asString)
    }

    @Test
    fun assuranceParserDefaultsMissingClaimToAal1AndFailsClosedOnMalformedClaims() {
        val aal1 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"user-1"}""".toByteArray())
        val unknown = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"aal":"aal3"}""".toByteArray())

        assertEquals("aal1", repository.assuranceLevel("header.$aal1.signature"))
        assertEquals("unknown", repository.assuranceLevel("header.$unknown.signature"))
        assertEquals("unknown", repository.assuranceLevel("not-a-jwt"))
    }

    @Test
    fun invalidTotpCodeDoesNotMakeANetworkRequest() = runBlocking {
        val result = repository.verifyMfaTotp(
            AuthSession("access-token", "refresh-token", "user-1", "admin@example.test"),
            "factor-1",
            "challenge-1",
            "12345",
        )

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }
}
