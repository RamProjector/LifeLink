package com.lifelink.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.lifelink.app.core.auth.AuthSession
import com.lifelink.app.core.auth.AuthSessionStore
import com.lifelink.app.data.local.LifeLinkDatabase
import com.lifelink.app.data.local.toEntity
import com.lifelink.app.data.remote.LifeLinkApi
import com.lifelink.app.data.repository.EmergencyRequestRepositoryImpl
import com.lifelink.app.domain.ActiveRequestSnapshot
import com.lifelink.app.domain.ActiveRequestStatus
import com.lifelink.app.domain.SubmitResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class RequestHistoryRestoreTest {
    private lateinit var db: LifeLinkDatabase
    private lateinit var server: MockWebServer
    private lateinit var workManager: WorkManager
    private lateinit var api: LifeLinkApi

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LifeLinkDatabase::class.java).allowMainThreadQueries().build()
        workManager = try {
            WorkManager.getInstance(context)
        } catch (_: IllegalStateException) {
            WorkManager.initialize(context, Configuration.Builder().build())
            WorkManager.getInstance(context)
        }
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(LifeLinkApi::class.java)
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::server.isInitialized) server.shutdown()
    }

    private fun repository(owner: String) = EmergencyRequestRepositoryImpl(
        db.emergencyRequestDraftDao(), db.pendingSubmissionDao(), db.activeRequestDao(),
        api, workManager, requesterIdProvider = { owner }
    )

    @Test fun sameAccountLoginRestoresSubmittedRequestAfterLogoutCleanup() = runBlocking {
        val store = AuthSessionStore(ApplicationProvider.getApplicationContext<Context>()) { owner ->
            launch { db.clearLocalAccountData(owner) }
        }
        val session = AuthSession("synthetic-token", "refresh", "alice", "alice@example.test")
        store.save(session)
        db.activeRequestDao().upsert(ActiveRequestSnapshot("saved-request", ActiveRequestStatus.AWAITING_RESPONSES).toEntity("alice"))
        db.activeRequestDao().upsert(ActiveRequestSnapshot("bobs-request", ActiveRequestStatus.MATCHING).toEntity("bob"))
        store.clear()
        store.save(session.copy(accessToken = "new-synthetic-token"))
        assertNull(repository("alice").observeActiveRequest().first())
        server.enqueue(MockResponse().setBody("[${historyItem("closed-request", "fulfilled")},${historyItem("saved-request", "awaiting_responses")}]"))
        val history = repository("alice").refreshRequestHistory()
        assertEquals(2, history.size)
        val restored = repository("alice").observeActiveRequest().first()!!
        assertEquals("saved-request", restored.requestId)
        assertEquals(ActiveRequestStatus.AWAITING_RESPONSES, restored.status)
        assertEquals(1, restored.matchesResponded)
        assertEquals("bobs-request", repository("bob").observeActiveRequest().first()?.requestId)
        assertEquals("/v1/emergency-requests", server.takeRequest().path)
        store.clear()
        store.save(session.copy(userId = "new-account"))
        server.enqueue(MockResponse().setBody("[]"))
        assertTrue(repository("new-account").refreshRequestHistory().isEmpty())
        assertNull(repository("new-account").observeActiveRequest().first())
    }

    @Test fun failedReloadPreservesCacheAndSuccessfulReloadDoesNotReplaceSelectedRequest() = runBlocking {
        val repository = repository("alice")
        db.activeRequestDao().upsert(ActiveRequestSnapshot("selected", ActiveRequestStatus.MATCHING).toEntity("alice"))
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { repository.refreshRequestHistory() }.isFailure)
        assertEquals("selected", repository.observeActiveRequest().first()?.requestId)
        server.enqueue(MockResponse().setBody("[${historyItem("other", "awaiting_responses")}]"))
        repository.refreshRequestHistory()
        assertEquals("selected", repository.observeActiveRequest().first()?.requestId)
    }

    @Test fun emptySuccessfulContactResponseIsUncertainRatherThanRetryableFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = repository("alice").contactSelectedDonors("request-1", listOf("donor-1"))

        assertEquals(SubmitResult.ContactRequestUncertain("request-1", listOf("donor-1")), result)
        assertEquals("/v1/emergency-requests/request-1/contact", server.takeRequest().path)
    }

    private fun historyItem(id: String, status: String) = """{
        "request_id":"$id","status":"$status","created_at":"2026-09-27T10:00:00Z",
        "expires_at":"2026-09-28T10:00:00Z","blood_type":"O+","units":1,"urgency":"urgent",
        "facility_name":"Test hospital","area":"Test area","notifications_created":2,
        "matches_responded":1,"contact_statuses":[]
    }"""
}
