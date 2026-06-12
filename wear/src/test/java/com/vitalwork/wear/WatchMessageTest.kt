package com.vitalwork.wear

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the [WatchMessage.flushComplete] terminal marker the watch sends after a store flush.
 * The phone parses this exact shape ([com.vitalwork.app.data.sensor.watch.WatchListenerService])
 * to learn how many chunks/rows to expect, so the field names must stay stable.
 */
class WatchMessageTest {

    @Test
    fun flushComplete_emitsTypeAndCounts() {
        val line = WatchMessage.flushComplete(batchId = 123L, chunkCount = 4, rowCount = 1200)

        assertTrue(line, line.contains("\"type\":\"FLUSH_COMPLETE\""))
        assertTrue(line, line.contains("\"batchId\":123"))
        assertTrue(line, line.contains("\"chunkCount\":4"))
        assertTrue(line, line.contains("\"rowCount\":1200"))
    }

    @Test
    fun flushComplete_emptyStore_reportsZeroes() {
        val line = WatchMessage.flushComplete(batchId = 0L, chunkCount = 0, rowCount = 0)

        assertTrue(line, line.contains("\"chunkCount\":0"))
        assertTrue(line, line.contains("\"rowCount\":0"))
    }
}
