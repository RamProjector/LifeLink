package com.lifelink.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "updates", primaryKeys = ["ownerId", "id"])
data class UpdateEntity(
    val ownerId: String,
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val createdAtEpochMillis: Long,
    val requestId: String?,
    val actionKey: String?,
    val isRead: Boolean
)

@Dao
interface UpdateDao {
    @Query("SELECT * FROM updates WHERE ownerId = :ownerId ORDER BY createdAtEpochMillis DESC")
    fun observeAll(ownerId: String): Flow<List<UpdateEntity>>

    @Query("SELECT * FROM updates WHERE ownerId = :ownerId AND id = :id LIMIT 1")
    suspend fun findById(ownerId: String, id: String): UpdateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(update: UpdateEntity)

    @Query("UPDATE updates SET isRead = 1 WHERE ownerId = :ownerId AND id = :id")
    suspend fun markRead(ownerId: String, id: String)

    @Query("UPDATE updates SET isRead = 1 WHERE ownerId = :ownerId")
    suspend fun markAllRead(ownerId: String)

    @Query("DELETE FROM updates WHERE ownerId = :ownerId")
    suspend fun clearAll(ownerId: String)
}
