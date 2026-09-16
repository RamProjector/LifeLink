package com.lifelink.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.lifelink.app.LifeLinkApplication
import com.lifelink.app.domain.EmergencyRequestDraft
import com.lifelink.app.domain.SubmitResult

@Entity(tableName = "pending_submissions")
data class PendingSubmissionEntity(
    @androidx.room.PrimaryKey val id: String,
    val payloadJson: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

@Dao
interface PendingSubmissionDao {
    @Query("SELECT * FROM pending_submissions ORDER BY createdAtEpochMillis ASC LIMIT 1")
    suspend fun oldest(): PendingSubmissionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingSubmissionEntity)

    @Query("DELETE FROM pending_submissions WHERE id = :id")
    suspend fun delete(id: String)
}

class PendingSubmissionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? LifeLinkApplication ?: return Result.failure()
        val database = LifeLinkDatabase.getInstance(applicationContext)
        val pending = database.pendingSubmissionDao().oldest() ?: return Result.success()
        val draft = runCatching { Gson().fromJson(pending.payloadJson, EmergencyRequestDraft::class.java) }
            .getOrNull() ?: return Result.failure()

        return when (application.container.emergencyRequestRepository.submit(draft)) {
            is SubmitResult.MatchingStarted -> {
                database.pendingSubmissionDao().delete(pending.id)
                Result.success()
            }
            is SubmitResult.ContactRequested -> {
                database.pendingSubmissionDao().delete(pending.id)
                Result.success()
            }
            is SubmitResult.ManualFallback -> {
                database.pendingSubmissionDao().delete(pending.id)
                Result.failure()
            }
            is SubmitResult.Cancelled -> Result.success()
            is SubmitResult.OfflineQueued -> Result.retry()
            is SubmitResult.Error -> Result.failure()
        }
    }
}
