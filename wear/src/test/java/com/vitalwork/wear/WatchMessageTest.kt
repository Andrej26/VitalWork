package com.vitalwork.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for [WatchMessage]: the [WatchMessage.flushComplete] terminal marker the phone parses
 * ([com.vitalwork.app.data.sensor.watch.WatchListenerService]) — so its field names must stay
 * stable — plus the capture-time stamping ([WatchMessage.reading]) and IBI back-dating
 * ([WatchMessage.ibiTimestamps]) the alignment fix depends on.
 */
class WatchMessageTest {

    @Test
    fun reading_usesPassedTimestamp_notWallClock() {
        val captured = 1_781_449_253_402L // a real capture time, far from "now"
        val line = WatchMessage.reading("WATCH_HR", 74.0f, 1, captured)

        assertTrue(line, line.contains("\"t\":$captured"))
        assertTrue(line, line.contains("\"type\":\"WATCH_HR\""))
        assertTrue(line, line.contains("\"value\":74.0"))
        assertTrue(line, line.contains("\"accuracy\":1"))
    }

    @Test
    fun ibiTimestamps_backdateBeats_endAtSetTs_spacedByDurations() {
        val setTs = 1_000_000L
        val ibis = listOf(820, 810, 835, 800)
        val ts = WatchMessage.ibiTimestamps(setTs, ibis)

        assertEquals(4, ts.size)
        assertEquals(setTs, ts.last())                  // last beat lands on the set timestamp
        // strictly increasing
        assertTrue(ts.toString(), ts.zipWithNext().all { (a, b) -> a < b })
        // consecutive spacing == the IBI durations (each ibi is the interval before its beat)
        assertEquals(listOf(810L, 835L, 800L), ts.zipWithNext().map { (a, b) -> b - a })
        // explicit values: setTs - cumulative-of-later
        assertEquals(listOf(997_555L, 998_365L, 999_200L, 1_000_000L), ts)
    }

    @Test
    fun ibiTimestamps_edges_singleAndEmpty() {
        assertEquals(listOf(500L), WatchMessage.ibiTimestamps(500L, listOf(900)))
        assertEquals(emptyList<Long>(), WatchMessage.ibiTimestamps(500L, emptyList()))
    }

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
