package com.lifelink.app

import com.lifelink.app.feature.privacy.formatConversationTimestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTimestampTest {
    private val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    private fun date(value: String): Date = requireNotNull(parser.parse(value))

    @Test
    fun same_day_timestamp_uses_today_label_and_device_local_time() {
        val now = date("2026-11-12T10:00:00+08:00")
        val result = formatConversationTimestamp("2026-11-12T09:30:00+08:00", now)

        assertTrue(result.startsWith("Today · "))
        assertTrue(result.endsWith("AM"))
    }

    @Test
    fun previous_calendar_day_uses_yesterday_label() {
        val now = date("2026-11-12T10:00:00+08:00")
        val result = formatConversationTimestamp("2026-11-11T21:15:00+08:00", now)

        assertTrue(result.startsWith("Yesterday · "))
    }

    @Test
    fun invalid_or_legacy_timestamp_never_leaks_raw_value() {
        assertEquals("Recent message", formatConversationTimestamp("not-a-timestamp"))
    }
}
