package com.lifelink.app.core.errors

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Converts transport and HTTP failures into stable, user-facing messages. */
fun userFacingError(error: Throwable, fallback: String): String {
    val statusCode = Regex("\\b([45]\\d{2})\\b").find(error.message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
    return when (statusCode) {
        401 -> "Your session has expired. Please sign in again."
        403 -> "You do not have permission to perform that action."
        404 -> "That information is no longer available."
        408 -> "The request timed out. Check your connection and try again."
        409 -> "This information changed on the server. Refresh and try again."
        429 -> "Too many attempts. Please wait a moment and try again."
        in 500..599 -> "LifeLink is temporarily unavailable. Please try again shortly."
        else -> when (error) {
            is SocketTimeoutException -> "The request timed out. Check your connection and try again."
            is UnknownHostException -> "You appear to be offline. Check your connection and try again."
            is IOException -> "A connection problem occurred. Check your connection and try again."
            else -> fallback
        }
    }
}
