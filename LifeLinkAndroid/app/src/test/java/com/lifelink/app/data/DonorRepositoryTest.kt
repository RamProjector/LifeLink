package com.lifelink.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lifelink.app.data.local.DonorRequestEntity
import com.lifelink.app.data.local.LifeLinkDatabase
import com.lifelink.app.data.remote.LifeLinkApi
import com.lifelink.app.data.repository.DonorRepositoryImpl
import com.lifelink.app.domain.DonorResponse
import kotlinx.coroutines.flow.first
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
class DonorRepositoryTest {
    private lateinit var db: LifeLinkDatabase
    private lateinit var server: MockWebServer
    private lateinit var repository: DonorRepositoryImpl

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LifeLinkDatabase::class.java)
            .allowMainThreadQueries().build()
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(LifeLinkApi::class.java)
        repository = DonorRepositoryImpl(db.donorDao(), api) { "alice" }
    }

    @After fun tearDown() { db.close(); server.shutdown() }

    @Test fun restoresSavedDonorThenReplacesInboxAndRestoresLowercaseResponse() = runBlocking {
        db.donorDao().upsertRequest(DonorRequestEntity("alice", "stale", "O-", 1, "urgent", "Hospital", "Area", 1.0, null))
        enqueueProfile()
        server.enqueue(MockResponse().setBody("""[{"request_id":"current","blood_type":"O-","units":1,"urgency":"critical","facility_name":"Hospital","area":"Area","distance_km":2,"status":"accepted"}]"""))
        repository.refresh()
        assertTrue(repository.observeProfile().first().isSetupComplete)
        val request = repository.observeRequests().first().single()
        assertEquals("current", request.requestId)
        assertEquals(DonorResponse.ACCEPTED, request.response)
        enqueueProfile()
        server.enqueue(MockResponse().setBody("[]"))
        repository.refresh()
        assertTrue(repository.observeRequests().first().isEmpty())
    }

    @Test fun serverErrorKeepsCachedInboxAndReportsFailure() = runBlocking {
        db.donorDao().upsertRequest(DonorRequestEntity("alice", "cached", "O-", 1, "urgent", "Hospital", "Area", 1.0, null))
        enqueueProfile()
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { repository.refresh() }.isFailure)
        assertEquals("cached", repository.observeRequests().first().single().requestId)
    }

    @Test fun missingProfileClearsOnlyThatDonorAndDoesNotFetchInbox() = runBlocking {
        for (owner in listOf("alice", "bob")) {
            db.donorDao().upsertRequest(DonorRequestEntity(owner, "cached", "O-", 1, "urgent", "Hospital", "Area", 1.0, null))
        }
        server.enqueue(MockResponse().setResponseCode(404))
        repository.refresh()
        assertTrue(repository.observeRequests().first().isEmpty())
        assertEquals(1, db.donorDao().observeRequests("bob").first().size)
        assertEquals(1, server.requestCount)
    }

    private fun enqueueProfile() {
        server.enqueue(MockResponse().setBody("""{"donor_id":"alice","display_name":"Alice","blood_type":"O-","latitude":14.6,"longitude":121.0,"service_radius_km":10,"availability":"available","verified":false,"donor_note":"","preferred_contact_method":"in_app","profile_visible":true}"""))
    }
}
