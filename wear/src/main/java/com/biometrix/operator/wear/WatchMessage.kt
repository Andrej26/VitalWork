package com.biometrix.operator.wear

/**
 * Builds the newline-delimited JSON lines streamed to the tablet. Hand-built (no serializer)
 * to keep the payload tiny and the format explicit — the tablet parses the same shape.
 *
 *   {"t":1717245600000,"type":"HR","value":72.0,"accuracy":0}
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

    /** Wrap several already-encoded reading lines into one message (screen-off bundles). */
    fun batch(items: List<String>): String =
        """{"type":"BATCH","items":[${items.joinToString(",")}]}"""

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
