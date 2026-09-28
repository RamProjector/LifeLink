package com.lifelink.app

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

/**
 * Saves the screenshots that the "Android visual review" workflow pulls off the emulator and
 * uploads as evidence.
 *
 * Two things fail transiently on a slow hosted emulator, and both broke this suite in CI:
 *  - Context.getExternalFilesDir() returns null while the emulated shared-storage volume is still
 *    (re)mounting ("Failed to ensure .../Android/data/com.lifelink.app/files"). File(null, "x.png")
 *    then silently becomes a relative path, and the instrumentation process's working directory is
 *    read-only, so the save failed with EROFS.
 *  - UiAutomation.takeScreenshot() can return null for the first frames after UiAutomation connects.
 *
 * Evidence is a by-product of the walkthrough, not what it asserts, so a screenshot that still cannot
 * be saved after retrying is logged instead of failing a test whose UI assertions passed.
 */
object VisualEvidence {
    private const val TAG = "LifeLinkVisual"
    private const val STORAGE_ATTEMPTS = 10
    private const val SCREENSHOT_ATTEMPTS = 6
    private const val RETRY_DELAY_MS = 1_000L
    private const val FALLBACK_DIR = "lifelink-evidence"

    /** Saves a screenshot as [fileName]; returns false (after logging) if it could not be saved. */
    fun capture(fileName: String): Boolean {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(SCREENSHOT_ATTEMPTS) { attempt ->
            val target = File(evidenceDir(), fileName)
            if (device.takeScreenshot(target)) return true
            Log.w(TAG, "Screenshot attempt ${attempt + 1}/$SCREENSHOT_ATTEMPTS failed for $target")
            Thread.sleep(RETRY_DELAY_MS)
        }
        Log.w(TAG, "Could not save $fileName; continuing because the UI assertions do not depend on it")
        return false
    }

    /**
     * Prefers the app's external files dir, which the workflow pulls with `adb pull`. While shared
     * storage is unavailable it falls back to app-private storage, which the workflow reads with
     * `run-as`. It never returns a relative path.
     */
    private fun evidenceDir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repeat(STORAGE_ATTEMPTS) {
            val dir = context.getExternalFilesDir(null)
            if (dir != null && (dir.isDirectory || dir.mkdirs()) && dir.canWrite()) return dir
            Thread.sleep(RETRY_DELAY_MS)
        }
        Log.w(TAG, "Shared storage is unavailable; saving evidence in app-private storage")
        return File(context.filesDir, FALLBACK_DIR).apply { mkdirs() }
    }
}
