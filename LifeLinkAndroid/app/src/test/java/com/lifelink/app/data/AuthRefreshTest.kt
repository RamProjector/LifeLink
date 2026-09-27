package com.lifelink.app.data

import androidx.test.core.app.ApplicationProvider
import com.lifelink.app.core.auth.AuthSession
import com.lifelink.app.core.auth.AuthSessionStore
import com.lifelink.app.core.auth.SupabaseAuthRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AuthRefreshTest {
    private lateinit var server: MockWebServer
    private lateinit var store: AuthSessionStore
    private lateinit var repository: SupabaseAuthRepository
    private val original = AuthSession("old-token", "refresh", "alice", "alice@example.test")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        store = AuthSessionStore(ApplicationProvider.getApplicationContext())
        runBlocking { store.save(original) }
        repository = SupabaseAuthRepository(store, server.url("/").toString(), "test-public-key")
    }

    @After fun tearDown() { store.clear(); server.shutdown() }

    @Test fun temporaryServerFailureKeepsSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(repository.refreshAccessToken())
        assertEquals(original, store.session.value)
        assertFalse(repository.sessionExpired.value)
    }

    @Test fun invalidRefreshTokenExpiresSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        assertNull(repository.refreshAccessToken())
        assertNull(store.session.value)
        assertTrue(repository.sessionExpired.value)
    }

    @Test fun refreshResponseCannotSignBackInAfterSignOut() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"access_token":"fresh","refresh_token":"new-refresh","user":{"id":"alice"}}""")
            .setBodyDelay(300, TimeUnit.MILLISECONDS))
        val refresh = async(kotlinx.coroutines.Dispatchers.IO) { repository.refreshAccessToken() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        repository.signOut()
        assertNull(refresh.await())
        assertNull(store.session.value)
    }

    @Test fun refreshDoesNotReplaceAnotherSignedInAccount() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"access_token":"fresh","user":{"id":"alice"}}""")
            .setBodyDelay(300, TimeUnit.MILLISECONDS))
        val refresh = async(kotlinx.coroutines.Dispatchers.IO) { repository.refreshAccessToken() }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val bob = original.copy(userId = "bob", accessToken = "bob-token")
        store.save(bob)
        assertNull(refresh.await())
        assertEquals(bob, store.session.value)
    }
}
