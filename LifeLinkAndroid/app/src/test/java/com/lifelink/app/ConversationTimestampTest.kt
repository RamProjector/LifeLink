package com.lifelink.app

import com.lifelink.app.feature.privacy.formatConversationTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ConversationTimestampTest {
    private val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private fun date(dayOffset: Int): Date =
        Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 12, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, dayOffset)
        }.time

    private fun timestamp(value: Date): String = formatter.format(value)

    @Test
    fun same_day_timestamp_uses_today_label_and_device_local_time() {
        val now = date(0)
        val result = formatConversationTimestamp(timestamp(date(0)), now)

        assertTrue(result.startsWith("Today · "))
    }

    @Test
    fun previous_calendar_day_uses_yesterday_label() {
        val now = date(0)
        val result = formatConversationTimestamp(timestamp(date(-1)), now)

        assertTrue(result.startsWith("Yesterday · "))
    }

    @Test
    fun invalid_or_legacy_timestamp_never_leaks_raw_value() {
        assertEquals("Recent message", formatConversationTimestamp("not-a-timestamp"))
    }
}
