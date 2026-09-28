package com.lifelink.app.core.notifications

import androidx.work.ListenableWorker
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class PushTokenRegistrationTest {
    @Test
    fun registersRotatedTokenForTheCurrentAccount() = runBlocking {
        var registered: Pair<String, String>? = null
        val processor = PushTokenRegistrationProcessor(
            currentUserId = { "donor-1" },
            register = { ownerId, token ->
                registered = ownerId to token
                Response.success(Unit)
            }
        )

        val result = processor.process("donor-1", "refreshed-fcm-token")

        assertResultType(ListenableWorker.Result.success(), result)
        assertEquals("donor-1" to "refreshed-fcm-token", registered)
    }

    @Test
    fun doesNotRegisterForAnAccountThatIsNoLongerSignedIn() = runBlocking {
        var attempted = false
        val processor = PushTokenRegistrationProcessor(
            currentUserId = { "another-account" },
            register = { _, _ ->
                attempted = true
                Response.success(Unit)
            }
        )

        val result = processor.process("donor-1", "refreshed-fcm-token")

        assertResultType(ListenableWorker.Result.success(), result)
        assertFalse(attempted)
    }

    @Test
    fun rejectsMissingOwnerOrToken() = runBlocking {
        var attempted = false
        val processor = PushTokenRegistrationProcessor(
            currentUserId = { "donor-1" },
            register = { _, _ ->
                attempted = true
                Response.success(Unit)
            }
        )

        assertResultType(ListenableWorker.Result.failure(), processor.process(null, "token"))
        assertResultType(ListenableWorker.Result.failure(), processor.process("donor-1", " "))
        assertFalse(attempted)
    }

    @Test
    fun retriesOnTransientHttpAndNetworkFailures() = runBlocking {
        val unavailable = PushTokenRegistrationProcessor(
            currentUserId = { "donor-1" },
            register = { _, _ -> Response.error(503, "unavailable".toResponseBody()) }
        )
        val rateLimited = PushTokenRegistrationProcessor(
            currentUserId = { "donor-1" },
            register = { _, _ -> Response.error(429, "rate limited".toResponseBody()) }
        )
        val offline = PushTokenRegistrationProcessor(
            currentUserId = { "donor-1" },
            register = { _, _ -> throw IOException("offline") }
        )

        assertResultType(ListenableWorker.Result.retry(), unavailable.process("donor-1", "token"))
        assertResultType(ListenableWorker.Result.retry(), rateLimited.process("donor-1", "token"))
        assertResultType(ListenableWorker.Result.retry(), offline.process("donor-1", "token"))
    }

    @Test
    fun doesNotRetryPermanentBackendRejections() = runBlocking {
        val rejected = PushTokenRegistrationProcessor(
            currentUserId = { "donor-1" },
            register = { _, _ -> Response.error(400, "invalid token".toResponseBody()) }
        )

        assertResultType(ListenableWorker.Result.failure(), rejected.process("donor-1", "token"))
    }

    private fun assertResultType(expected: ListenableWorker.Result, actual: ListenableWorker.Result) {
        assertEquals(expected::class.java, actual::class.java)
    }
}
