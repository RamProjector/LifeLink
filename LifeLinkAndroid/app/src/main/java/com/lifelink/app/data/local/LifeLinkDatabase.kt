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
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

@Dao
interface EmergencyRequestDraftDao {
    @Query("SELECT * FROM emergency_request_drafts WHERE ownerId = :ownerId AND id = :id LIMIT 1")
    suspend fun findById(ownerId: String, id: String): EmergencyRequestDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: EmergencyRequestDraftEntity)

    @Query("DELETE FROM emergency_request_drafts WHERE ownerId = :ownerId AND id = :id")
    suspend fun deleteById(ownerId: String, id: String)

    @Query("DELETE FROM emergency_request_drafts WHERE ownerId = :ownerId")
    suspend fun clearAll(ownerId: String)
}

@Database(entities = [EmergencyRequestDraftEntity::class, PendingSubmissionEntity::class, ActiveRequestEntity::class, DonorProfileEntity::class, DonorRequestEntity::class, UpdateEntity::class], version = 9, exportSchema = false)
abstract class LifeLinkDatabase : RoomDatabase() {
    abstract fun emergencyRequestDraftDao(): EmergencyRequestDraftDao
    abstract fun pendingSubmissionDao(): PendingSubmissionDao
    abstract fun activeRequestDao(): ActiveRequestDao
    abstract fun donorDao(): DonorDao
    abstract fun updateDao(): UpdateDao

    suspend fun clearLocalAccountData(ownerId: String) = withTransaction {
        emergencyRequestDraftDao().clearAll(ownerId)
        pendingSubmissionDao().clearAll(ownerId)
        activeRequestDao().clearAll(ownerId)
        donorDao().clearAll(ownerId)
        updateDao().clearAll(ownerId)
    }

    companion object {
        @Volatile private var INSTANCE: LifeLinkDatabase? = null

        fun getInstance(context: Context): LifeLinkDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LifeLinkDatabase::class.java,
                    "lifelink.db"
                ).addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5).build().also { INSTANCE = it }
            }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Preserve legacy rows, but never assign unowned data to a signed-in user.
                addOwnerIfMissing(database, "active_requests")
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE donor_requests RENAME TO donor_requests_legacy")
                database.execSQL("""
                    CREATE TABLE donor_requests (
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
                database.execSQL("""
                    INSERT INTO donor_requests
                    SELECT '', requestId, bloodType, units, urgency, facilityName, area, distanceKm, response
                    FROM donor_requests_legacy
                """.trimIndent())
                database.execSQL("DROP TABLE donor_requests_legacy")
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Some v7/v8 installs predate ownerId on active_requests.
                addOwnerIfMissing(database, "active_requests")
                for (table in listOf("emergency_request_drafts", "pending_submissions", "updates")) {
                    migrateOwnedTable(database, table)
                }
            }
        }

        private fun addOwnerIfMissing(database: SupportSQLiteDatabase, table: String) {
            val hasOwner = database.query("PRAGMA table_info(`$table`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(name) == "ownerId") found = true
                found
            }
            if (!hasOwner) database.execSQL("ALTER TABLE `$table` ADD COLUMN ownerId TEXT NOT NULL DEFAULT ''")
        }

        private fun migrateOwnedTable(database: SupportSQLiteDatabase, table: String) {
            val columns = mutableListOf<String>()
            val definitions = mutableListOf<String>()
            var ownerExpression = "''"
            database.query("PRAGMA table_info(`$table`)").use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    if (name == "ownerId") {
                        ownerExpression = "COALESCE(`ownerId`, '')"
                        continue
                    }
                    val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                    val required = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                    columns += "`$name`"
                    definitions += "`$name` $type" + if (required) " NOT NULL" else ""
                }
            }
            database.execSQL("CREATE TABLE `${table}_owned` (ownerId TEXT NOT NULL, ${definitions.joinToString()}, PRIMARY KEY(ownerId, id))")
            database.execSQL("INSERT INTO `${table}_owned` SELECT $ownerExpression, ${columns.joinToString()} FROM `$table`")
            database.execSQL("DROP TABLE `$table`")
            database.execSQL("ALTER TABLE `${table}_owned` RENAME TO `$table`")
        }
    }
}
