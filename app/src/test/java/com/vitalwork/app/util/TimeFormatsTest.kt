package com.vitalwork.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Guards [TimeFormats] against the "device-local time mislabeled as UTC" bug: the formatters must
 * pin to UTC regardless of the JVM's default zone. Expected values are computed with an explicit
 * UTC zone so the test is correct no matter where it runs (CI, a CEST laptop, etc.).
 */
class TimeFormatsTest {

    // 2026-06-20T14:04:12.860Z — the true-UTC instant from the test export that exposed the bug.
    private val fixedUtcMs = 1781964252860L

    @Test
    fun iso_rendersUtcInstant() {
        val expected = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(fixedUtcMs))
        assertEquals(expected, TimeFormats.iso(fixedUtcMs))
        assertEquals("2026-06-20T14:04:12Z", TimeFormats.iso(fixedUtcMs))
    }

    @Test
    fun codeToken_rendersUtcToken() {
        assertEquals("260620-140412", TimeFormats.codeToken(fixedUtcMs))
    }

    @Test
    fun utcZone_isUtc() {
        assertEquals(0, TimeFormats.UTC.getRawOffset())
    }
}
