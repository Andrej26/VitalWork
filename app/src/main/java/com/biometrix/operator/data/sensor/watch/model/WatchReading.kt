package com.biometrix.operator.data.sensor.watch.model

/**
 * One sensor reading received from the Galaxy Watch over the Data Layer channel.
 *
 * @param type   "WATCH_HR", "WATCH_IBI", "WATCH_EDA", "BATTERY", "HEARTBEAT", … (matches the watch stream)
 * @param value  numeric value (BPM, ms, microsiemens, %, °C, …)
 * @param accuracy per-reading status/accuracy flag from the SDK (0 = good); null if N/A
 * @param timestampMs watch wall-clock at sample time (System.currentTimeMillis on the watch)
 */
data class WatchReading(
    val type: String,
    val value: Float,
    val accuracy: Int?,
    val timestampMs: Long
)
