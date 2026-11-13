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

@RunWith(RobolectricTestRunner::class)
class SupabasePasswordRecoveryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: SupabaseAuthRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = SupabaseAuthRepository(
            sessionStore = AuthSessionStore(ApplicationProvider.getApplicationContext<Context>()),
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
}
