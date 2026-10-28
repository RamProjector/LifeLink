package com.lifelink.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Debug-only diagnostics written to Downloads/LifeLink/lifelink-debug.log.
 * Never pass tokens, request bodies, donor details, or precise coordinates to this logger.
 */
internal object DebugFileLogger {
    private const val TAG = "LifeLinkDebugFile"
    private const val FILE_NAME = "lifelink-debug.log"
    private const val RELATIVE_PATH = "Download/LifeLink/"
    private const val MAX_LOG_BYTES = 1_000_000L
    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        if (!BuildConfig.DEBUG) return
        appContext = context.applicationContext
        synchronized(lock) {
            if (previousHandler != null) return
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                write("FATAL", "Uncaught exception on thread ${thread.name}", error)
                previousHandler?.uncaughtException(thread, error)
            }
        }
        write("INFO", "Debug file logger initialized; Android ${Build.VERSION.SDK_INT}")
    }

    fun error(area: String, message: String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        write("ERROR", "[$area] $message", error)
    }

    fun warning(area: String, message: String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        write("WARN", "[$area] $message", error)
    }

    private fun write(level: String, message: String, error: Throwable? = null) {
        val context = appContext ?: return
        if (!BuildConfig.DEBUG) return
        val stack = error?.let {
            StringWriter().also { writer -> it.printStackTrace(PrintWriter(writer)) }.toString()
        }.orEmpty()
        val entry = "${Instant.now()} [$level] $message" +
            if (stack.isEmpty()) "\n" else "\n$stack\n"
        synchronized(lock) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appendToDownloads(context, entry)
                } else {
                    // Android versions before scoped storage keep a fallback in app-specific files.
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                        ?.resolve(FILE_NAME)
                        ?.appendText(entry)
                }
            } catch (failure: Exception) {
                // Logging must never crash or disrupt the app.
                Log.w(TAG, "Could not write debug log file", failure)
            }
        }
    }

    private fun appendToDownloads(context: Context, entry: String) {
        val resolver = context.contentResolver
        val collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        var fileUri: Uri? = null
        var fileSize = 0L
        resolver.query(collection, projection, selection, arrayOf(FILE_NAME, RELATIVE_PATH), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                fileUri = Uri.withAppendedPath(collection, cursor.getLong(0).toString())
                fileSize = cursor.getLong(1)
            }
        }

        if (fileUri != null && fileSize >= MAX_LOG_BYTES) {
            resolver.delete(fileUri!!, null, null)
            fileUri = null
        }

        if (fileUri == null) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            }
            fileUri = resolver.insert(collection, values)
        }

        val uri = fileUri ?: error("Unable to create debug log in Downloads")
        resolver.openOutputStream(uri, "wa")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(entry)
        } ?: error("Unable to open debug log in Downloads")
    }
}
