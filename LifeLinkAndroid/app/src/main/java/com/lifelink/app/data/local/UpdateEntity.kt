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
    val accountId: String,
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
    @Query("SELECT * FROM updates WHERE accountId = :accountId ORDER BY createdAtEpochMillis DESC")
    fun observeAll(accountId: String): Flow<List<UpdateEntity>>

    @Query("SELECT * FROM updates WHERE id = :id AND accountId = :accountId LIMIT 1")
    suspend fun findById(id: String, accountId: String): UpdateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(update: UpdateEntity)

    @Query("UPDATE updates SET isRead = 1 WHERE id = :id AND accountId = :accountId")
    suspend fun markRead(id: String, accountId: String)

    @Query("UPDATE updates SET isRead = 1 WHERE accountId = :accountId")
    suspend fun markAllRead(accountId: String)
}
