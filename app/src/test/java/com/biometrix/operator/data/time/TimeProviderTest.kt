package com.biometrix.operator.data.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeProviderTest {

    @Test
    fun `nowMs returns the wrapped source value`() {
        val provider = TimeProvider { 1_700_000_000_000L }
        assertEquals(1_700_000_000_000L, provider.nowMs())
    }

    @Test
    fun `ntpOffsetMs is the difference between the source and the device clock`() {
        // Source clock runs 5 s ahead of the device clock.
        val provider = TimeProvider { System.currentTimeMillis() + 5_000L }
        val offset = provider.ntpOffsetMs()
        // Allow a little slack for the two System.currentTimeMillis() reads inside ntpOffsetMs().
        assertTrue("offset was $offset", offset in 4_900L..5_100L)
    }

    @Test
    fun `system provider tracks the device clock with near-zero offset`() {
        val provider = TimeProvider.system()
        val before = System.currentTimeMillis()
        val now = provider.nowMs()
        val after = System.currentTimeMillis()
        assertTrue(now in before..after)
        assertTrue("offset was ${provider.ntpOffsetMs()}", provider.ntpOffsetMs() in -50L..50L)
    }
}
