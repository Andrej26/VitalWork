package com.vitalwork.wear

/**
 * Builds the newline-delimited JSON lines streamed to the tablet. Hand-built (no serializer)
 * to keep the payload tiny and the format explicit — the tablet parses the same shape.
 *
 *   {"t":1717245600000,"type":"WATCH_HR","value":72.0,"accuracy":0}
 *   {"t":1717245600000,"type":"CAPABILITIES","text":"HEART_RATE_CONTINUOUS,EDA_CONTINUOUS"}
 */
object WatchMessage {

    fun reading(type: String, value: Float, accuracy: Int): String =
        """{"t":${System.currentTimeMillis()},"type":"$type","value":$value,"accuracy":$accuracy}"""

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
