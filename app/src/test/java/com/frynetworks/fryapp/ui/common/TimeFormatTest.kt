package com.frynetworks.fryapp.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeFormatTest {

    @Test
    fun `parseMillis accepts ISO-8601 and epoch digits and rejects garbage`() {
        val expected = java.time.Instant.parse("2026-09-11T00:00:00Z").toEpochMilli()
        assertEquals(expected, TimeFormat.parseMillis("2026-09-11T00:00:00.000Z"))
        assertEquals(expected, TimeFormat.parseMillis(expected.toString()))
        assertNull(TimeFormat.parseMillis("not a date"))
        assertNull(TimeFormat.parseMillis(null))
        assertNull(TimeFormat.parseMillis("  "))
    }

    @Test
    fun `duration renders the two most significant units`() {
        assertEquals("now", TimeFormat.duration(0))
        assertEquals("now", TimeFormat.duration(-5_000))
        assertEquals("45s", TimeFormat.duration(45_000))
        assertEquals("4m 20s", TimeFormat.duration(260_000))
        assertEquals("2h 5m", TimeFormat.duration((2 * 3600 + 5 * 60) * 1000L))
        assertEquals("3d 4h", TimeFormat.duration((3 * 86_400 + 4 * 3600) * 1000L))
    }

    @Test
    fun `relative time buckets minutes hours and days`() {
        val now = 1_757_548_800_000L
        assertEquals("just now", TimeFormat.relative(now - 10_000, now))
        assertEquals("5m ago", TimeFormat.relative(now - 5 * 60_000, now))
        assertEquals("3h ago", TimeFormat.relative(now - 3 * 3_600_000, now))
        assertEquals("2d ago", TimeFormat.relative(now - 2 * 86_400_000, now))
        assertEquals(TimeFormat.date(now - 30L * 86_400_000), TimeFormat.relative(now - 30L * 86_400_000, now))
    }

    @Test
    fun `dateOr falls back when the value cannot be parsed`() {
        assertEquals("—", TimeFormat.dateOr("garbage"))
        assertEquals("n/a", TimeFormat.dateOr(null, "n/a"))
    }
}
