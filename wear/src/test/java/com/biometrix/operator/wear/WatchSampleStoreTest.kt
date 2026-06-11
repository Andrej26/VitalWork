package com.biometrix.operator.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Host unit tests for [WatchSampleStore]'s durability + truncate-after-ack contract — the core that
 * makes the watch store-and-forward lossless (un-acked rows survive; acked rows are dropped).
 */
class WatchSampleStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = WatchSampleStore(tmp.root)

    private fun line(t: Long, type: String = "EDA", value: Float = 1.0f) =
        """{"t":$t,"type":"$type","value":$value,"accuracy":0}"""

    @Test
    fun appendThenReadAll_returnsLinesInOrder() {
        val s = store()
        s.append(line(1_000))
        s.append(line(2_000))
        val rows = s.readAll()
        assertEquals(2, rows.size)
        assertTrue(rows[0].contains("\"t\":1000"))
        assertTrue(rows[1].contains("\"t\":2000"))
    }

    @Test
    fun truncateThrough_dropsAtOrBeforeAck_keepsLater() {
        val s = store()
        s.append(line(1_000))
        s.append(line(2_000))
        s.append(line(3_000))

        // Phone acked through 2_000 → 1_000 and 2_000 dropped, 3_000 kept (un-acked tail survives).
        s.truncateThrough(2_000)

        val rows = s.readAll()
        assertEquals(1, rows.size)
        assertTrue(rows[0].contains("\"t\":3000"))
    }

    @Test
    fun truncateThrough_inclusiveBoundary() {
        val s = store()
        s.append(line(2_000))
        s.truncateThrough(2_000) // <= ack → dropped
        assertTrue(s.readAll().isEmpty())
    }

    @Test
    fun truncateThrough_keepsLinesWithoutParseableTimestamp() {
        val s = store()
        s.append("garbage-without-t")
        s.append(line(1_000))
        s.truncateThrough(5_000) // would drop 1_000; the unparseable line is kept (can't prove acked)
        val rows = s.readAll()
        assertEquals(1, rows.size)
        assertEquals("garbage-without-t", rows[0])
    }

    @Test
    fun clear_emptiesTheStore() {
        val s = store()
        s.append(line(1_000))
        s.clear()
        assertTrue(s.readAll().isEmpty())
    }

    @Test
    fun shouldPersist_onlyStudyTypes() {
        val s = store()
        assertTrue(s.shouldPersist("HR"))
        assertTrue(s.shouldPersist("IBI"))
        assertTrue(s.shouldPersist("EDA"))
        assertFalse(s.shouldPersist("BATTERY"))
        assertFalse(s.shouldPersist("HEARTBEAT"))
    }

    @Test
    fun readAll_onEmptyOrMissingFile_returnsEmpty() {
        assertTrue(store().readAll().isEmpty())
    }
}
