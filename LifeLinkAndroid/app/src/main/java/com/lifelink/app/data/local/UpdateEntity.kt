package com.lifelink.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "updates")
data class UpdateEntity(
    @androidx.room.PrimaryKey val id: String,
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
    @Query("SELECT * FROM updates ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<UpdateEntity>>

    @Query("SELECT * FROM updates WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): UpdateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(update: UpdateEntity)

    @Query("UPDATE updates SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE updates SET isRead = 1")
    suspend fun markAllRead()
}
