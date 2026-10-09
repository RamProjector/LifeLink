package com.lifelink.app.core.errors

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TIMEOUT = 408
private const val HTTP_CONFLICT = 409
private const val HTTP_RATE_LIMITED = 429
private const val HTTP_SERVER_ERROR_START = 500
private const val HTTP_SERVER_ERROR_END = 599

/** Converts transport and HTTP failures into stable, user-facing messages. */
fun userFacingError(error: Throwable, fallback: String): String {
    val statusCode =
        Regex("\\b([45]\\d{2})\\b")
            .find(error.message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    return when (statusCode) {
        HTTP_UNAUTHORIZED -> "Your session has expired. Please sign in again."
        HTTP_FORBIDDEN -> "You do not have permission to perform that action."
        HTTP_NOT_FOUND -> "That information is no longer available."
        HTTP_TIMEOUT -> "The request timed out. Check your connection and try again."
        HTTP_CONFLICT -> "This information changed on the server. Refresh and try again."
        HTTP_RATE_LIMITED -> "Too many attempts. Please wait a moment and try again."
        in HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END ->
            "LifeLink is temporarily unavailable. Please try again shortly."
        else ->
            when (error) {
                is SocketTimeoutException -> "The request timed out. Check your connection and try again."
                is UnknownHostException -> "You appear to be offline. Check your connection and try again."
                is IOException -> "A connection problem occurred. Check your connection and try again."
                else -> fallback
            }
    }
}
