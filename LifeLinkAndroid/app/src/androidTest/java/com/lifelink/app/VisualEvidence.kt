package com.lifelink.app

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

/**
 * Saves screenshots to app-private storage. The workflow extracts these files with `run-as`
 * before the emulator is torn down, avoiding unsupported shell-command parsing or shared-storage
 * permission assumptions.
 *
 * UiAutomation.takeScreenshot() can also return null for the first frames after it connects, so capture
 * retries before logging a non-fatal failure. The workflow separately fails if required evidence is absent.
 */
object VisualEvidence {
    private const val TAG = "LifeLinkVisual"
    private const val SCREENSHOT_ATTEMPTS = 6
    private const val RETRY_DELAY_MS = 1_000L
    private const val EVIDENCE_DIR = "lifelink-evidence"

    /** Saves a screenshot as [fileName]; returns false (after logging) if it could not be saved. */
    fun capture(fileName: String): Boolean {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(SCREENSHOT_ATTEMPTS) { attempt ->
            val target = File(evidenceDir(), fileName)
            if (device.takeScreenshot(target)) return true
            Log.w(TAG, "Screenshot attempt ${attempt + 1}/$SCREENSHOT_ATTEMPTS failed for $target")
            Thread.sleep(RETRY_DELAY_MS)
        }
        Log.e(TAG, "Required visual evidence could not be saved: $fileName")
        return false
    }

    private fun evidenceDir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.filesDir, EVIDENCE_DIR).apply {
            if (!isDirectory && !mkdirs()) {
                Log.w(TAG, "Could not create private evidence directory: $absolutePath")
            }
        }
    }
}
