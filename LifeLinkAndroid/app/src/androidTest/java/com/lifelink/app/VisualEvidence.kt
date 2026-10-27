package com.lifelink.app

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

/**
 * Saves screenshots privately, then publishes a copy to shared Pictures storage while the test app is
 * still installed. CI collects those copies with `adb pull`; it must not depend on the app remaining
 * installed after Gradle finishes the instrumentation task.
 *
 * UiAutomation.takeScreenshot() can also return null for the first frames after it connects, so capture
 * retries before logging a non-fatal failure. The workflow separately fails if required evidence is absent.
 */
object VisualEvidence {
    private const val TAG = "LifeLinkVisual"
    private const val SCREENSHOT_ATTEMPTS = 6
    private const val RETRY_DELAY_MS = 1_000L
    private const val FALLBACK_DIR = "lifelink-evidence"

    /** Saves a screenshot as [fileName]; returns false (after logging) if it could not be saved. */
    fun capture(fileName: String): Boolean {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(SCREENSHOT_ATTEMPTS) { attempt ->
            val target = File(evidenceDir(), fileName)
            if (device.takeScreenshot(target)) {
                publishForCi(device, fileName)
                return true
            }
            Log.w(TAG, "Screenshot attempt ${attempt + 1}/$SCREENSHOT_ATTEMPTS failed for $target")
            Thread.sleep(RETRY_DELAY_MS)
        }
        Log.w(TAG, "Could not save $fileName; continuing because the UI assertions do not depend on it")
        return false
    }

    /**
     * Copies the saved PNG to shared storage before the instrumentation runner can uninstall the app.
     * The filename is restricted to the fixed evidence naming convention before shell use.
     */
    private fun publishForCi(device: UiDevice, fileName: String) {
        if (!fileName.matches(Regex("lifelink-[a-z0-9-]+\\.png"))) {
            Log.w(TAG, "Refusing unexpected evidence filename: $fileName")
            return
        }
        val command =
            "mkdir -p /sdcard/Pictures/LifeLinkEvidence && " +
                "run-as com.lifelink.app cat files/$FALLBACK_DIR/$fileName " +
                "> /sdcard/Pictures/LifeLinkEvidence/$fileName"
        runCatching {
            val output = device.executeShellCommand(
                "$command && test -s /sdcard/Pictures/LifeLinkEvidence/$fileName && echo PUBLISHED || echo PUBLISH_FAILED",
            ).trim()
            if (output.contains("PUBLISHED")) {
                Log.i(TAG, "Published $fileName for CI collection")
            } else {
                Log.w(TAG, "Could not publish $fileName for CI collection: $output")
            }
        }.onFailure { Log.w(TAG, "Could not publish $fileName for CI collection", it) }
    }

    private fun evidenceDir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.filesDir, FALLBACK_DIR).apply {
            if (!isDirectory && !mkdirs()) {
                Log.w(TAG, "Could not create private evidence directory: $absolutePath")
            }
        }
    }
}
