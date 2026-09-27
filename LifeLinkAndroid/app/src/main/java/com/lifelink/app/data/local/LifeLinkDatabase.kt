package com.lifelink.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.migration.Migration
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

@Database(entities = [EmergencyRequestDraftEntity::class, PendingSubmissionEntity::class, ActiveRequestEntity::class, DonorProfileEntity::class, DonorRequestEntity::class, UpdateEntity::class], version = 9, exportSchema = false)
abstract class LifeLinkDatabase : RoomDatabase() {
    abstract fun emergencyRequestDraftDao(): EmergencyRequestDraftDao
    abstract fun pendingSubmissionDao(): PendingSubmissionDao
    abstract fun activeRequestDao(): ActiveRequestDao
    abstract fun donorDao(): DonorDao
    abstract fun updateDao(): UpdateDao

    companion object {
        @Volatile private var INSTANCE: LifeLinkDatabase? = null

        fun getInstance(context: Context): LifeLinkDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LifeLinkDatabase::class.java,
                    "lifelink.db"
                ).addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE donor_requests ADD COLUMN donorId TEXT NOT NULL DEFAULT ''")
                database.execSQL("DELETE FROM donor_requests WHERE donorId = ''")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE active_requests ADD COLUMN requesterId TEXT NOT NULL DEFAULT ''")
                database.execSQL("DELETE FROM active_requests WHERE requesterId = ''")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE updates ADD COLUMN accountId TEXT NOT NULL DEFAULT ''")
                database.execSQL("DELETE FROM updates WHERE accountId = ''")
            }
        }
    }
}
