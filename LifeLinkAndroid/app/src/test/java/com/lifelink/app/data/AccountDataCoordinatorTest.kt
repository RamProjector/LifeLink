package com.lifelink.app.data

import com.lifelink.app.core.auth.AccountDataCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AccountDataCoordinatorTest {
    @Test
    fun registrationWaitsForCleanupAndSurvivesItsCancellation() = runBlocking {
        val coordinator = AccountDataCoordinator { "alice" }
        val releaseCleanup = CompletableDeferred<Unit>()
        val scheduled = mutableListOf("old token")
        val cleaning = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.cleanup {
                releaseCleanup.await()
                scheduled.clear()
            }
        }
        val registering = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runForOwner("alice") { scheduled.add("new token") }
        }

        assertFalse(registering.isCompleted)
        assertEquals(listOf("old token"), scheduled)
        releaseCleanup.complete(Unit)
        cleaning.join()
        registering.join()
        assertEquals(listOf("new token"), scheduled)
    }

    @Test
    fun cleanupWaitsForInFlightWriteAndRejectsLateWrite() = runBlocking {
        var owner: String? = "alice"
        val coordinator = AccountDataCoordinator { owner }
        val releaseWrite = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        val writing = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runForOwner("alice") {
                releaseWrite.await()
                persisted.add("in-flight notification")
            }
        }
        owner = null
        val cleaning = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.cleanup { persisted.clear() }
        }
        assertFalse(cleaning.isCompleted)
        val lateWrite = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runForOwner("alice") { persisted.add("late notification") }
        }
        releaseWrite.complete(Unit)
        writing.join()
        cleaning.join()
        lateWrite.join()
        assertTrue(persisted.isEmpty())
    }
}
