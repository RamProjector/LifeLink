package com.lifelink.app

import com.lifelink.app.core.errors.userFacingError
import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class UserFacingErrorTest {
    @Test
    fun maps_http_statuses_without_exposing_server_details() {
        assertEquals("Your session has expired. Please sign in again.", userFacingError(IOException("request failed (401)"), "fallback"))
        assertEquals("You do not have permission to perform that action.", userFacingError(IOException("request failed (403)"), "fallback"))
        assertEquals("Too many attempts. Please wait a moment and try again.", userFacingError(IOException("request failed (429)"), "fallback"))
        assertEquals("LifeLink is temporarily unavailable. Please try again shortly.", userFacingError(IOException("request failed (503)"), "fallback"))
    }

    @Test
    fun maps_offline_failures_to_a_connection_message() {
        assertEquals(
            "You appear to be offline. Check your connection and try again.",
            userFacingError(UnknownHostException("api.example.invalid"), "fallback"),
        )
    }

    @Test
    fun preserves_feature_fallback_for_unknown_application_failures() {
        assertEquals("The conversation could not be opened.", userFacingError(IllegalStateException(), "The conversation could not be opened."))
    }
}
