package com.lifelink.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lifelink.app.domain.ActiveRequestSnapshot
import com.lifelink.app.domain.ActiveRequestStatus
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "active_requests")
data class ActiveRequestEntity(
    @androidx.room.PrimaryKey val requestId: String,
    val status: String,
    val notificationsCreated: Int,
    val matchesResponded: Int,
    val reason: String?,
    val lastUpdatedEpochMillis: Long
)

@Dao
interface ActiveRequestDao {
    @Query("SELECT * FROM active_requests ORDER BY lastUpdatedEpochMillis DESC LIMIT 1")
    fun observeLatest(): Flow<ActiveRequestEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: ActiveRequestEntity)

    @Query("DELETE FROM active_requests WHERE requestId = :requestId")
    suspend fun delete(requestId: String)
}

fun ActiveRequestEntity.toDomain(): ActiveRequestSnapshot = ActiveRequestSnapshot(
    requestId = requestId,
    status = runCatching { ActiveRequestStatus.valueOf(status) }.getOrDefault(ActiveRequestStatus.MATCHING),
    notificationsCreated = notificationsCreated,
    matchesResponded = matchesResponded,
    reason = reason,
    lastUpdatedEpochMillis = lastUpdatedEpochMillis
)

fun ActiveRequestSnapshot.toEntity(): ActiveRequestEntity = ActiveRequestEntity(
    requestId = requestId,
    status = status.name,
    notificationsCreated = notificationsCreated,
    matchesResponded = matchesResponded,
    reason = reason,
    lastUpdatedEpochMillis = lastUpdatedEpochMillis
)
