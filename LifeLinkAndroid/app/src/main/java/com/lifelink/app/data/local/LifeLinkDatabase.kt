package com.lifelink.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Dao
interface EmergencyRequestDraftDao {
    @Query("SELECT * FROM emergency_request_drafts WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): EmergencyRequestDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: EmergencyRequestDraftEntity)

    @Query("DELETE FROM emergency_request_drafts WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(entities = [EmergencyRequestDraftEntity::class, PendingSubmissionEntity::class, ActiveRequestEntity::class, DonorProfileEntity::class, DonorRequestEntity::class], version = 4, exportSchema = false)
abstract class LifeLinkDatabase : RoomDatabase() {
    abstract fun emergencyRequestDraftDao(): EmergencyRequestDraftDao
    abstract fun pendingSubmissionDao(): PendingSubmissionDao
    abstract fun activeRequestDao(): ActiveRequestDao
    abstract fun donorDao(): DonorDao

    companion object {
        @Volatile private var INSTANCE: LifeLinkDatabase? = null

        fun getInstance(context: Context): LifeLinkDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LifeLinkDatabase::class.java,
                    "lifelink.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
