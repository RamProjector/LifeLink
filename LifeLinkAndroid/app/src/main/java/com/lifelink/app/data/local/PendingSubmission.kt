package com.lifelink.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.google.gson.Gson
import com.lifelink.app.LifeLinkApplication
import com.lifelink.app.domain.EmergencyRequestDraft
import com.lifelink.app.domain.SubmitResult

@Entity(tableName = "pending_submissions", primaryKeys = ["ownerId", "id"])
data class PendingSubmissionEntity(
    val ownerId: String,
    val id: String,
    val payloadJson: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

@Dao
interface PendingSubmissionDao {
    @Query("SELECT * FROM pending_submissions WHERE ownerId = :ownerId AND id = :id LIMIT 1")
    suspend fun findById(ownerId: String, id: String): PendingSubmissionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingSubmissionEntity)

    @Query("DELETE FROM pending_submissions WHERE ownerId = :ownerId AND id = :id")
    suspend fun delete(ownerId: String, id: String)

    @Query("DELETE FROM pending_submissions WHERE ownerId = :ownerId")
    suspend fun clearAll(ownerId: String)
}

class PendingSubmissionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? LifeLinkApplication ?: return Result.failure()
        return PendingSubmissionProcessor(
            dao = LifeLinkDatabase.getInstance(applicationContext).pendingSubmissionDao(),
            currentUserId = { application.authRepository.session.value?.userId },
            submit = { ownerId, draft -> application.accountContainer(ownerId).emergencyRequestRepository.submit(draft) }
        ).process(inputData.getString(OWNER_ID), inputData.getString(DRAFT_ID))
    }

    companion object {
        const val OWNER_ID = "owner_id"
        const val DRAFT_ID = "draft_id"
    }
}

/** Retry policy is independent of WorkManager scheduling and is tested with stored payloads. */
internal class PendingSubmissionProcessor(
    private val dao: PendingSubmissionDao,
    private val currentUserId: () -> String?,
    private val submit: suspend (String, EmergencyRequestDraft) -> SubmitResult
) {
    suspend fun process(ownerId: String?, draftId: String?): WorkResult {
        if (ownerId.isNullOrBlank() || draftId.isNullOrBlank()) return WorkResult.failure()
        if (currentUserId() != ownerId) return WorkResult.success()
        val pending = dao.findById(ownerId, draftId) ?: return WorkResult.success()
        val draft = runCatching { Gson().fromJson(pending.payloadJson, EmergencyRequestDraft::class.java) }
            .getOrNull() ?: return WorkResult.failure()
        if (draft.id != draftId) return WorkResult.failure()
        return when (val result = submit(ownerId, draft)) {
            is SubmitResult.MatchingStarted,
            is SubmitResult.ContactRequested,
            is SubmitResult.ContactRequestUncertain,
            is SubmitResult.ManualFallback,
            is SubmitResult.Cancelled,
            is SubmitResult.Fulfilled -> {
                dao.delete(ownerId, draftId)
                WorkResult.success()
            }
            is SubmitResult.OfflineQueued -> WorkResult.retry()
            is SubmitResult.Error -> {
                dao.upsert(pending.copy(attempts = pending.attempts + 1, lastError = result.message))
                if (result.retryable) WorkResult.retry() else WorkResult.failure()
            }
        }
    }
}
