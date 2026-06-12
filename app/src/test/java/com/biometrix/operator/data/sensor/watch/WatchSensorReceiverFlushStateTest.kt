package com.biometrix.operator.data.sensor.watch

import com.biometrix.operator.data.time.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the End-Session store-flush handshake in [WatchSensorReceiver]: how chunk arrivals
 * and the `FLUSH_COMPLETE` marker drive [WatchFlushState] to Complete, with per-index de-dup and the
 * max raw watch timestamp carried for the (post-persist) `FLUSH_ACK`.
 */
class WatchSensorReceiverFlushStateTest {

    private fun receiver() = WatchSensorReceiver(TimeProvider.system())

    private fun WatchFlushState.asComplete() = this as WatchFlushState.Complete
    private fun WatchFlushState.asInProgress() = this as WatchFlushState.InProgress

    @Test
    fun emptyStore_completesImmediately_withNoAckTimestamp() {
        val r = receiver()
        r.onFlushStarted()
        r.onFlushComplete(batchId = 0L, chunkCount = 0, rowCount = 0)

        val s = r.flushState.value.asComplete()
        assertEquals(0, s.rowsReceived)
        assertNull(s.maxWatchTimestampMs)
    }

    @Test
    fun chunks_completeWhenAllReceived_carryingMaxWatchTs() {
        val r = receiver()
        r.onFlushStarted()

        r.onFlushChunk(batchId = 1L, index = 0, count = 2, maxWatchTsInChunk = 100L)
        val mid = r.flushState.value.asInProgress()
        assertEquals(1, mid.received)
        assertEquals(2, mid.expected)

        r.onFlushChunk(batchId = 1L, index = 1, count = 2, maxWatchTsInChunk = 250L)
        assertEquals(250L, r.flushState.value.asComplete().maxWatchTimestampMs)
    }

    @Test
    fun duplicateChunk_isIdempotent() {
        val r = receiver()
        r.onFlushStarted()

        r.onFlushChunk(batchId = 1L, index = 0, count = 2, maxWatchTsInChunk = 100L)
        r.onFlushChunk(batchId = 1L, index = 0, count = 2, maxWatchTsInChunk = 100L) // re-delivery
        assertTrue("still waiting on chunk 1", r.flushState.value is WatchFlushState.InProgress)

        r.onFlushChunk(batchId = 1L, index = 1, count = 2, maxWatchTsInChunk = 200L)
        assertTrue(r.flushState.value is WatchFlushState.Complete)
    }

    @Test
    fun flushComplete_arrivingBeforeChunks_setsExpected_thenCompletes() {
        val r = receiver()
        r.onFlushStarted()

        // FLUSH_COMPLETE (MessageClient) can beat the DataItem chunks (DataClient) to the phone.
        r.onFlushComplete(batchId = 1L, chunkCount = 1, rowCount = 5)
        assertTrue(r.flushState.value is WatchFlushState.InProgress)

        r.onFlushChunk(batchId = 1L, index = 0, count = 1, maxWatchTsInChunk = 42L)
        val s = r.flushState.value.asComplete()
        assertEquals(5, s.rowsReceived)
        assertEquals(42L, s.maxWatchTimestampMs)
    }

    @Test
    fun onFlushStarted_resetsToInProgress() {
        val r = receiver()
        r.onFlushComplete(batchId = 1L, chunkCount = 0, rowCount = 0) // prior Complete
        assertTrue(r.flushState.value is WatchFlushState.Complete)

        r.onFlushStarted()
        val s = r.flushState.value.asInProgress()
        assertEquals(0, s.received)
        assertNull(s.expected)
    }

    @Test
    fun flushedReadings_areBufferedLosslessly_notDroppedLikeTheBoundedLiveFlow() {
        val r = receiver()
        r.onFlushStarted()

        // A whole screen-off session is thousands of rows — far more than the live flow's 256-slot
        // DROP_OLDEST buffer. They must ALL survive so the drain can split them across every scenario.
        val total = 5_000
        val batch = (0 until total).map {
            com.biometrix.operator.data.sensor.watch.model.WatchReading(
                type = "WATCH_EDA", value = it.toFloat(), accuracy = 0, timestampMs = 1_000L + it
            )
        }
        r.onFlushedReadings(batch)

        val taken = r.takeFlushedReadings()
        assertEquals(total, taken.size)
        // Oldest is retained (the old bug dropped exactly these).
        assertEquals(0f, taken.first().value, 0f)
        assertEquals((total - 1).toFloat(), taken.last().value, 0f)
        // Drains the buffer.
        assertEquals(0, r.takeFlushedReadings().size)
    }

    @Test
    fun onFlushedReadings_keepsOnlyStudySensorTypes() {
        val r = receiver()
        r.onFlushStarted()
        fun reading(t: String) =
            com.biometrix.operator.data.sensor.watch.model.WatchReading(t, 1f, 0, 1L)
        r.onFlushedReadings(listOf(reading("WATCH_EDA"), reading("WATCH_HR"), reading("WATCH_IBI"), reading("BATTERY")))

        val taken = r.takeFlushedReadings()
        assertEquals(3, taken.size)
        assertTrue(taken.none { it.type == "BATTERY" })
    }
}
