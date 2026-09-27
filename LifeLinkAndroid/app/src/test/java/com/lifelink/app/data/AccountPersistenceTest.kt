package com.lifelink.app.data

import android.content.Context
import androidx.work.ListenableWorker
import com.google.gson.Gson
import com.lifelink.app.domain.EmergencyRequestDraft
import com.lifelink.app.domain.SubmitResult
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lifelink.app.core.auth.AuthSession
import com.lifelink.app.core.auth.AuthSessionStore
import com.lifelink.app.data.local.*
import com.lifelink.app.data.repository.UpdatesRepository
import com.lifelink.app.domain.UpdateType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class AccountPersistenceTest {
    private lateinit var db: LifeLinkDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LifeLinkDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    @Test fun sameEventIdHasIndependentContentAndReadStatePerAccount() = runBlocking {
        val alice = UpdatesRepository(db.updateDao(), "alice")
        val bob = UpdatesRepository(db.updateDao(), "bob")
        alice.record("event", UpdateType.ACCOUNT, "Alice", "Private update")
        bob.record("event", UpdateType.ACCOUNT, "Bob", "Another update")
        alice.markAllRead()
        alice.record("event", UpdateType.ACCOUNT, "Alice refreshed", "Still private")
        assertTrue(alice.observe().first().single().isRead)
        assertFalse(bob.observe().first().single().isRead)
        assertEquals("Bob", bob.observe().first().single().title)
        db.clearLocalAccountData("alice")
        assertTrue(alice.observe().first().isEmpty())
        assertEquals(1, bob.observe().first().size)
    }

    @Test fun draftsAndRetryPayloadsAreReadAndDeletedOnlyByTheirOwner() = runBlocking {
        for (owner in listOf("alice", "bob", "")) {
            db.emergencyRequestDraftDao().upsert(draft(owner))
            db.pendingSubmissionDao().upsert(PendingSubmissionEntity(owner, "draft", "payload-$owner"))
        }
        assertEquals("alice", db.emergencyRequestDraftDao().findById("alice", "draft")?.ownerId)
        assertNull(db.emergencyRequestDraftDao().findById("new-account", "draft"))
        assertNull(db.pendingSubmissionDao().findById("alice", "other-draft"))
        assertEquals("payload-bob", db.pendingSubmissionDao().findById("bob", "draft")?.payloadJson)
        db.emergencyRequestDraftDao().deleteById("alice", "draft")
        db.pendingSubmissionDao().delete("alice", "draft")
        assertNotNull(db.emergencyRequestDraftDao().findById("bob", "draft"))
        assertNotNull(db.pendingSubmissionDao().findById("bob", "draft"))
    }

    @Test fun donorReplacementIsScopedAndPrioritizesCriticalRequests() = runBlocking {
        val dao = db.donorDao()
        dao.upsertRequest(request("bob", "private"))
        dao.upsertRequest(request("alice", "stale"))
        dao.replaceRequests("alice", listOf(request("alice", "urgent"), request("alice", "critical", "critical")))
        assertEquals(listOf("critical", "urgent"), dao.observeRequests("alice").first().map { it.requestId })
        dao.replaceRequests("alice", emptyList())
        assertTrue(dao.observeRequests("alice").first().isEmpty())
        assertEquals("private", dao.observeRequests("bob").first().single().requestId)
    }

    @Test fun legacyCredentialsWithoutAnAccountIdRequireSignIn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("lifelink_auth", Context.MODE_PRIVATE)
        preferences.edit().clear().putString("access_token", "legacy-token").commit()
        assertNull(AuthSessionStore(context).session.value)
        preferences.edit().clear().commit()
    }

    @Test fun accountRemovalTriggersCleanupWithoutAnActivity() = runBlocking {
        val removedAccounts = mutableListOf<String>()
        val store = AuthSessionStore(ApplicationProvider.getApplicationContext<Context>()) {
            removedAccounts += it
            null
        }
        val alice = AuthSession("alice-token", "refresh", "alice", "alice@example.test")
        store.save(alice)
        assertTrue(store.replace(alice, alice.copy(accessToken = "refreshed-token")))
        assertTrue(removedAccounts.isEmpty())

        val bob = alice.copy(userId = "bob", accessToken = "bob-token")
        store.save(bob)
        assertEquals(listOf("alice"), removedAccounts)
        assertFalse(store.replace(alice, null))
        assertEquals(listOf("alice"), removedAccounts)

        // Token expiration and explicit sign-out share the same cleanup hook.
        assertTrue(store.replace(bob, null))
        assertEquals(listOf("alice", "bob"), removedAccounts)
        store.clear()
        assertEquals(listOf("alice", "bob"), removedAccounts)
        store.save(alice)
        store.clear()
        assertEquals(listOf("alice", "bob", "alice"), removedAccounts)
    }

    @Test fun sameAccountSignInWaitsForCleanupBeforeWritingNewData() = runBlocking {
        val releaseCleanup = CompletableDeferred<Unit>()
        val store = AuthSessionStore(ApplicationProvider.getApplicationContext<Context>()) { owner ->
            launch {
                releaseCleanup.await()
                db.clearLocalAccountData(owner)
            }
        }
        val alice = AuthSession("alice-token", "refresh", "alice", "alice@example.test")
        store.save(alice)
        db.pendingSubmissionDao().upsert(PendingSubmissionEntity("alice", "old", "old-payload"))
        store.clear()
        val signIn = async(start = CoroutineStart.UNDISPATCHED) {
            store.save(alice.copy(accessToken = "new-token"))
            db.pendingSubmissionDao().upsert(PendingSubmissionEntity("alice", "new", "new-payload"))
        }
        assertFalse(signIn.isCompleted)
        assertNull(store.session.value)
        releaseCleanup.complete(Unit)
        signIn.await()
        assertEquals("new-token", store.session.value?.accessToken)
        assertNull(db.pendingSubmissionDao().findById("alice", "old"))
        assertNotNull(db.pendingSubmissionDao().findById("alice", "new"))
        store.clear()
    }

    @Test fun staleRefreshCannotRestoreSignedOutOrReplaceDifferentAccount() = runBlocking {
        val store = AuthSessionStore(ApplicationProvider.getApplicationContext<Context>())
        val alice = AuthSession("alice-token", "refresh", "alice", "alice@example.test")
        val bob = alice.copy(accessToken = "bob-token", userId = "bob")
        store.save(alice)
        store.clear()
        assertFalse(store.replace(alice, alice.copy(accessToken = "late-token")))
        assertNull(store.session.value)
        store.save(bob)
        assertFalse(store.replace(alice, null))
        assertEquals(bob, store.session.value)
        store.clear()
    }

    @Test fun retryUsesExactScheduledDraftAndNeverAnotherAccountsPayload() = runBlocking {
        val dao = db.pendingSubmissionDao()
        for ((owner, id) in listOf("alice" to "oldest", "bob" to "target", "alice" to "target")) {
            dao.upsert(PendingSubmissionEntity(owner, id, Gson().toJson(EmergencyRequestDraft(id = id))))
        }
        var submissions = 0
        val processor = PendingSubmissionProcessor(dao, { "alice" }) { owner, draft ->
            submissions++
            assertEquals("alice", owner)
            assertEquals("target", draft.id)
            SubmitResult.ManualFallback("request", "Needs manual broadcast")
        }
        assertEquals(ListenableWorker.Result.success(), processor.process("bob", "target"))
        assertEquals(0, submissions)
        assertEquals(ListenableWorker.Result.success(), processor.process("alice", "target"))
        assertEquals(1, submissions)
        assertNull(dao.findById("alice", "target"))
        assertNotNull(dao.findById("alice", "oldest"))
        assertNotNull(dao.findById("bob", "target"))
    }

    @Test fun transientRetryFailureRetainsPayloadAndRecordsAttempt() = runBlocking {
        val dao = db.pendingSubmissionDao()
        dao.upsert(PendingSubmissionEntity("alice", "draft", Gson().toJson(EmergencyRequestDraft(id = "draft"))))
        val processor = PendingSubmissionProcessor(dao, { "alice" }) { _, _ -> SubmitResult.Error("Unavailable", retryable = true) }
        assertEquals(ListenableWorker.Result.retry(), processor.process("alice", "draft"))
        assertEquals(1, dao.findById("alice", "draft")?.attempts)
        assertEquals("Unavailable", dao.findById("alice", "draft")?.lastError)
        assertEquals(ListenableWorker.Result.failure(), processor.process(null, "draft"))
    }

    private fun request(owner: String, id: String, urgency: String = "urgent") =
        DonorRequestEntity(owner, id, "O-", 1, urgency, "Facility", "Area", 2.0, null)

    private fun draft(owner: String) = EmergencyRequestDraftEntity(
        ownerId = owner, id = "draft", bloodType = "O_NEG", units = 1, typeUnknown = false,
        urgency = "URGENT", responseDeadline = "Today", note = "Private note",
        facilityId = null, facilityName = null, facilityArea = null, facilityVerified = false,
        requesterLatitude = null, requesterLongitude = null, locationPrecisionMeters = 100,
        contactMethod = "IN_APP", genuineRequestConfirmed = true, sharingConsentConfirmed = true,
        aiMatchingEnabled = true, updatedAtEpochMillis = 1
    )
}
