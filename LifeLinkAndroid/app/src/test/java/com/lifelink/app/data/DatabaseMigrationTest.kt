package com.lifelink.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lifelink.app.data.local.LifeLinkDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DatabaseMigrationTest {
    @Test fun version8PreservesOwnedRequestsAndQuarantinesUnownedRows() = verifyMigration(8)
    @Test fun version7PreservesLegacyRowsWithEmptyOwner() = verifyMigration(7)
    @Test fun version6MigratesWithoutDestructiveReset() = verifyMigration(6)

    @Test fun version8AlreadyOwnedRowsRetainTheirOwner() = verifyMigration(8, ownedLegacyRows = true)

    private fun verifyMigration(version: Int, ownedLegacyRows: Boolean = false) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-$version.db"
        context.deleteDatabase(name)
        var schema = javaClass.getResource("/database-v8.sql")!!.readText()
        if (version < 7) schema = schema.replace("  ownerId TEXT NOT NULL,\n", "")
        if (version < 8) {
            schema = schema.replace("  donorId TEXT NOT NULL,\n  requestId", "  requestId")
                .replace("PRIMARY KEY(donorId, requestId)", "PRIMARY KEY(requestId)")
        }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            schema.split(';').filter { it.isNotBlank() }.forEach(old::execSQL)
            old.execSQL("INSERT INTO pending_submissions VALUES ('legacy-draft', '{}', 2, 'offline', 1)")
            old.execSQL("INSERT INTO updates VALUES ('legacy-event', 'SYSTEM', 'Private title', 'Private body', 1, NULL, NULL, 0)")
            val ownerColumn = if (version >= 7) "ownerId," else ""
            val ownerValue = when (version) { 8 -> "'alice',"; 7 -> "'',"; else -> "" }
            old.execSQL("INSERT INTO active_requests (requestId, ${ownerColumn}status, notificationsCreated, matchesResponded, reason, lastUpdatedEpochMillis) VALUES ('request', ${ownerValue}'MATCHING', 0, 0, NULL, 1)")
            val donorColumn = if (version == 8) "donorId," else ""
            val donorValue = if (version == 8) "'alice'," else ""
            old.execSQL("INSERT INTO donor_requests (${donorColumn}requestId, bloodType, units, urgency, facilityName, area, distanceKm, response) VALUES (${donorValue}'donor-request', 'O-', 1, 'urgent', 'Hospital', 'Area', 1.0, NULL)")
            if (ownedLegacyRows) {
                for (table in listOf("emergency_request_drafts", "pending_submissions", "updates")) {
                    old.execSQL("ALTER TABLE `$table` ADD COLUMN ownerId TEXT")
                    old.execSQL("UPDATE `$table` SET ownerId = 'alice'")
                }
                old.execSQL("INSERT INTO pending_submissions (id, payloadJson, attempts, createdAtEpochMillis) VALUES ('legacy-null-owner', '{}', 0, 1)")
            }
            old.version = version
        }
        val migrated = Room.databaseBuilder(context, LifeLinkDatabase::class.java, name)
            .addMigrations(LifeLinkDatabase.MIGRATION_6_7, LifeLinkDatabase.MIGRATION_7_8, LifeLinkDatabase.MIGRATION_8_9)
            .allowMainThreadQueries().build()
        try {
            // Opening through Room validates the entire migrated schema.
            val legacyOwner = if (ownedLegacyRows) "alice" else ""
            if (!ownedLegacyRows) {
                assertNull(migrated.pendingSubmissionDao().findById("alice", "legacy-draft"))
                assertTrue(migrated.updateDao().observeAll("alice").first().isEmpty())
            } else {
                assertNotNull(migrated.pendingSubmissionDao().findById("", "legacy-null-owner"))
                assertNull(migrated.pendingSubmissionDao().findById("alice", "legacy-null-owner"))
            }
            assertEquals(2, migrated.pendingSubmissionDao().findById(legacyOwner, "legacy-draft")?.attempts)
            assertEquals("Private body", migrated.updateDao().findById(legacyOwner, "legacy-event")?.body)
            assertNull(migrated.activeRequestDao().observeLatest("bob").first())
            assertTrue(migrated.donorDao().observeRequests("bob").first().isEmpty())
            val expectedOwner = if (version == 8) "alice" else ""
            assertNotNull(migrated.activeRequestDao().observeLatest(expectedOwner).first())
            assertEquals(1, migrated.donorDao().observeRequests(expectedOwner).first().size)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
