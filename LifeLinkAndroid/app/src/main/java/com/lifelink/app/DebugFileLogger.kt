package com.lifelink.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Debug-only diagnostics written to Downloads/LifeLink/lifelink-debug.log.
 * Never pass tokens, request bodies, donor details, or precise coordinates to this logger.
 */
internal object DebugFileLogger {
    private const val TAG = "LifeLinkDebugFile"
    private const val FILE_NAME = "lifelink-debug.log"
    private const val RELATIVE_PATH = "Download/LifeLink/"
    private const val MAX_LOG_BYTES = 1_000_000L
    private const val MAX_CAUSE_DEPTH = 5
    private const val MAX_FRAMES_PER_CAUSE = 40
    private const val MIN_VALID_LINE_NUMBER = 0
    private val lock = Any()

    @Volatile private var appContext: Context? = null

    @Volatile private var installed = false
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        if (!BuildConfig.DEBUG) return
        appContext = context.applicationContext
        synchronized(lock) {
            if (!installed) {
                previousHandler = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    write("FATAL", "Uncaught exception on thread ${thread.name}", throwable)
                    previousHandler?.uncaughtException(thread, throwable)
                }
                installed = true
            }
        }
        write("INFO", "Debug file logger initialized; Android ${Build.VERSION.SDK_INT}")
    }

    fun error(area: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) write("ERROR", "[$area] $message", throwable)
    }

    fun warning(area: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) write("WARN", "[$area] $message", throwable)
    }

    // A logger must not destabilize the app when storage providers throw unexpected exceptions.
    @Suppress("TooGenericExceptionCaught")
    private fun write(level: String, message: String, throwable: Throwable? = null) {
        val context = appContext ?: return
        if (!BuildConfig.DEBUG) return
        // Exception messages and suppressed details can contain response bodies, tokens, or
        // precise coordinates. Keep useful class/stack-frame context but omit all messages.
        val stack = throwable?.let(::safeStackTrace).orEmpty()
        val entry = "${System.currentTimeMillis()} [$level] $message" +
            if (stack.isEmpty()) "\n" else "\n$stack\n"
        synchronized(lock) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appendToDownloads(context, entry)
                } else {
                    val fallback = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                        ?.resolve(FILE_NAME)
                    fallback?.parentFile?.mkdirs()
                    fallback?.appendText(entry)
                }
            } catch (failure: Exception) {
                // Logging must never crash or disrupt the app.
                Log.w(TAG, "Could not write debug log file (${failure.javaClass.simpleName})")
            }
        }
    }

    private fun safeStackTrace(throwable: Throwable): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        return buildString {
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                val failure = current
                if (!seen.add(failure)) {
                    append("Cause chain truncated\n")
                    current = null
                } else {
                    append(if (depth == 0) failure.javaClass.name else "Caused by: ${failure.javaClass.name}")
                        .append('\n')
                    failure.stackTrace.take(MAX_FRAMES_PER_CAUSE).forEach { frame ->
                        append("\\tat ").append(frame.className).append('.').append(frame.methodName).append('(')
                        val fileName = frame.fileName
                        if (fileName == null) {
                            append("Unknown Source")
                        } else {
                            append(fileName)
                            if (frame.lineNumber >= UNKNOWN_LINE_NUMBER) {
                                append(':').append(frame.lineNumber)
                            }
                        }
                        append(")\n")
                    }
                    current = failure.cause
                    depth++
                }
            }
            if (current != null && depth >= MAX_CAUSE_DEPTH) append("Cause chain truncated\n")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun appendToDownloads(context: Context, entry: String) {
        val resolver = context.contentResolver
        val collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE)
        val selection =
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        var fileUri: Uri? = null
        var fileSize = 0L
        resolver.query(collection, projection, selection, arrayOf(FILE_NAME, RELATIVE_PATH), null)
            ?.use { cursor ->
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

        val uri = fileUri ?: throw IOException("Unable to create debug log in Downloads")
        resolver.openOutputStream(uri, "wa")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(entry)
        } ?: throw IOException("Unable to open debug log in Downloads")
    }
}
