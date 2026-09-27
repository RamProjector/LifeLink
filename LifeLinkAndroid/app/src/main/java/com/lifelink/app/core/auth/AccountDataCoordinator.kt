package com.lifelink.app.core.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Orders background account writes before removal, or rejects them after sign-out. */
internal class AccountDataCoordinator(private val currentOwner: () -> String?) {
    private val mutex = Mutex()

    suspend fun runForOwner(ownerId: String, block: suspend () -> Unit) = mutex.withLock {
        if (ownerId.isNotBlank() && currentOwner() == ownerId) block()
    }

    suspend fun cleanup(block: suspend () -> Unit) = mutex.withLock { block() }
}
