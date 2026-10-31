package com.lifelink.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RetrofitProviderAuthTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun defaultClientDoesNotSendAnAuthorizationHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val api = RetrofitProvider.create(baseUrl = server.url("/").toString())
        val response = api.health()

        assertTrue(response.isSuccessful)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun accountClientSendsOnlyItsInjectedAccessToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val api = RetrofitProvider.create(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "signed-in-access-token" },
        )
        val response = api.health()

        assertTrue(response.isSuccessful)
        assertEquals("Bearer signed-in-access-token", server.takeRequest().getHeader("Authorization"))
    }
}
