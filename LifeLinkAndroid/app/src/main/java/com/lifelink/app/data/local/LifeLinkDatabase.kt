package com.lifelink.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction

@Dao
interface EmergencyRequestDraftDao {
    @Query("SELECT * FROM emergency_request_drafts WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): EmergencyRequestDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: EmergencyRequestDraftEntity)

    @Query("DELETE FROM emergency_request_drafts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM emergency_request_drafts")
    suspend fun clearAll()
}

@Database(entities = [EmergencyRequestDraftEntity::class, PendingSubmissionEntity::class, ActiveRequestEntity::class, DonorProfileEntity::class, DonorRequestEntity::class, UpdateEntity::class], version = 8, exportSchema = false)
abstract class LifeLinkDatabase : RoomDatabase() {
    abstract fun emergencyRequestDraftDao(): EmergencyRequestDraftDao
    abstract fun pendingSubmissionDao(): PendingSubmissionDao
    abstract fun activeRequestDao(): ActiveRequestDao
    abstract fun donorDao(): DonorDao
    abstract fun updateDao(): UpdateDao

    suspend fun clearLocalAccountData() = withTransaction {
        emergencyRequestDraftDao().clearAll()
        pendingSubmissionDao().clearAll()
        activeRequestDao().clearAll()
        donorDao().clearAll()
        updateDao().clearAll()
    }

    companion object {
        @Volatile private var INSTANCE: LifeLinkDatabase? = null

        fun getInstance(context: Context): LifeLinkDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LifeLinkDatabase::class.java,
                    "lifelink.db"
                ).addMigrations(MIGRATION_6_7, MIGRATION_7_8).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Versions <= 6 stored rows without an account namespace. Never expose
                // those rows to a newly authenticated account.
                database.execSQL("DELETE FROM emergency_request_drafts")
                database.execSQL("DELETE FROM pending_submissions")
                database.execSQL("DELETE FROM active_requests")
                database.execSQL("DELETE FROM donor_profiles")
                database.execSQL("DELETE FROM donor_requests")
                database.execSQL("DELETE FROM updates")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Donor request rows before v8 had no account namespace. Do not
                // risk showing another account's inbox after upgrading.
                database.execSQL("DROP TABLE IF EXISTS donor_requests")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS donor_requests (
                        donorId TEXT NOT NULL,
                        requestId TEXT NOT NULL,
                        bloodType TEXT NOT NULL,
                        units INTEGER NOT NULL,
                        urgency TEXT NOT NULL,
                        facilityName TEXT NOT NULL,
                        area TEXT NOT NULL,
                        distanceKm REAL NOT NULL,
                        response TEXT,
                        PRIMARY KEY(donorId, requestId)
                    )
                """.trimIndent())
            }
        }
    }
}
