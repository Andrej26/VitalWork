package com.vitalwork.wear

/**
 * Builds the newline-delimited JSON lines streamed to the tablet. Hand-built (no serializer)
 * to keep the payload tiny and the format explicit — the tablet parses the same shape.
 *
 *   {"t":1717245600000,"type":"WATCH_HR","value":72.0,"accuracy":0}
 *   {"t":1717245600000,"type":"CAPABILITIES","text":"HEART_RATE_CONTINUOUS,EDA_CONTINUOUS"}
 */
object WatchMessage {

    /**
     * One sensor reading. [timestampMs] is the sample's **true capture time** (from
     * `DataPoint.getTimestamp()`), NOT wall-clock at send time — so a screen-off backlog drained in a
     * burst keeps each sample's real 1 Hz spacing instead of collapsing onto the wake instant. The
     * stored copy and the streamed copy are built from this same value, which the phone's de-dup relies
     * on. See [ibiTimestamps] for how IBI beats within one HeartRateSet get their per-beat times.
     */
    fun reading(type: String, value: Float, accuracy: Int, timestampMs: Long): String =
        """{"t":$timestampMs,"type":"$type","value":$value,"accuracy":$accuracy}"""

    /**
     * Per-beat capture times for the IBIs in one HeartRateSet. The SDK stamps the whole set with a
     * single [setTs] (≈ the most recent beat), but each IBI is the interval *before* its beat — so the
     * beats land at `setTs`, `setTs - ibi[last]`, `setTs - ibi[last] - ibi[last-1]`, … i.e.
     * `beat[i] = setTs - sum(ibi[i+1..last])`. Result is strictly increasing and ends at [setTs];
     * consecutive spacing equals the IBI durations. Empty in → empty out. Pure + unit-tested.
     */
    fun ibiTimestamps(setTs: Long, ibiDurations: List<Int>): List<Long> {
        val out = LongArray(ibiDurations.size)
        var acc = 0L
        for (i in ibiDurations.indices.reversed()) {
            out[i] = setTs - acc
            acc += ibiDurations[i]
        }
        return out.toList()
    }

    fun capabilities(csv: String): String =
        """{"t":${System.currentTimeMillis()},"type":"CAPABILITIES","text":"${escape(csv)}"}"""

    /** Explicit "tracking stopped" notice so the phone can show DISCONNECTED instantly. */
    fun stop(): String =
        """{"t":${System.currentTimeMillis()},"type":"STOP"}"""

    /**
     * Low-rate "I'm alive, just dozing" beacon. Lets the phone distinguish a watch that is buffering
     * through Doze (heartbeat still lands at maintenance windows) from one that is truly gone, so the
     * UI can show "dozing/buffering" instead of a scary "Disconnected". Carries no sample data.
     */
    fun heartbeat(): String =
        """{"t":${System.currentTimeMillis()},"type":"HEARTBEAT"}"""

    /** Wrap several already-encoded reading lines into one message (screen-off bundles). */
    fun batch(items: List<String>): String =
        """{"type":"BATCH","items":[${items.joinToString(",")}]}"""

    /**
     * Terminal marker the watch sends after dispatching a store flush. Tells the phone exactly how
     * many DataItem chunks (and rows) to expect for this `batchId`, so it knows when the transfer is
     * complete (and so an empty store — `chunkCount == 0` — completes instantly instead of waiting
     * out a timeout). Sent on the same `MessageClient` path as readings.
     */
    fun flushComplete(batchId: Long, chunkCount: Int, rowCount: Int): String =
        """{"t":${System.currentTimeMillis()},"type":"FLUSH_COMPLETE","batchId":$batchId,"chunkCount":$chunkCount,"rowCount":$rowCount}"""

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
