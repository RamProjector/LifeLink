package com.lifelink.app

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugFileLoggerTest {
    @Test
    fun debugDiagnosticsAreReadableFromDownloads() {
        assumeTrue("MediaStore Downloads requires Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = "debug-file-regression-${System.nanoTime()}"

        DebugFileLogger.install(context)
        DebugFileLogger.warning("instrumentation-test", marker)
        val sensitiveMarker = "private-location-${System.nanoTime()}"
        DebugFileLogger.warning(
            "instrumentation-test",
            "Sanitization regression",
            IllegalStateException(sensitiveMarker),
        )

        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection =
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val cursor = context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            selection,
            arrayOf("lifelink-debug.txt", "Download/LifeLink/"),
            null,
        )
        assertNotNull("Debug log should exist in Downloads/LifeLink", cursor)
        val fileUri = cursor!!.use {
            assertTrue("Debug log query should return a row", it.moveToFirst())
            ContentUris.withAppendedId(collection, it.getLong(0))
        }
        val contents = context.contentResolver.openInputStream(fileUri)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        assertTrue("Debug log should contain the test diagnostic marker", contents.contains(marker))
        assertTrue("Debug log should retain safe exception type context", contents.contains("java.lang.IllegalStateException"))
        assertFalse("Debug log must omit arbitrary exception messages", contents.contains(sensitiveMarker))
    }
}
