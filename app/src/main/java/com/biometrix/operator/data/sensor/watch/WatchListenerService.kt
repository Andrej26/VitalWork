package com.biometrix.operator.data.sensor.watch

import android.util.Log
import com.biometrix.operator.data.sensor.watch.model.WatchReading
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * Receives the Galaxy Watch's sensor readings as individual Data Layer messages.
 *
 * Framework-instantiated via the manifest intent-filter (action MESSAGE_RECEIVED, path
 * /biometrix/sensors); auto-starts the app on a matching message even if it isn't running.
 * Each message body is one JSON reading — parsed and forwarded to the singleton
 * [WatchSensorReceiver]. Stateless: there is no channel/stream to stall, so no "freeze."
 */
@AndroidEntryPoint
class WatchListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WatchListenerService"
        private const val MESSAGE_PATH = "/biometrix/sensors"
        private const val FLUSH_PATH_PREFIX = "/biometrix/flush"
    }

    @Inject lateinit var receiver: WatchSensorReceiver

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != MESSAGE_PATH) return
        // Runs on a binder thread; parse + StateFlow set is light and thread-safe.
        val line = String(event.data, Charsets.UTF_8)
        Log.d(TAG, "rx ${line.take(120)}")
        parseLine(line)
    }

    private fun parseLine(line: String) {
        try {
            val obj = json.parseToJsonElement(line).jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
            when (type) {
                "CAPABILITIES" -> {
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    receiver.onCapabilities(text)
                }
                "BATCH" -> {
                    // Several 1 Hz readings bundled from one watch callback; unpack 1:1.
                    obj["items"]?.jsonArray?.forEach { runCatching { parseReading(it.jsonObject) } }
                }
                "STOP" -> receiver.onStop() // explicit "tracking stopped" → DISCONNECTED instantly
                "HEARTBEAT" -> receiver.onHeartbeat() // "alive, just dozing" → keeps state off DISCONNECTED
                "FLUSH_COMPLETE" -> {
                    // Terminal marker for a store flush: how many chunks/rows to expect for this batch.
                    val batchId = obj["batchId"]?.jsonPrimitive?.longOrNull ?: 0L
                    val chunkCount = obj["chunkCount"]?.jsonPrimitive?.intOrNull ?: 0
                    val rowCount = obj["rowCount"]?.jsonPrimitive?.intOrNull ?: 0
                    receiver.onFlushComplete(batchId, chunkCount, rowCount)
                }
                else -> parseReading(obj)
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad message: $line", e)
        }
    }

    /** Parse a single reading object ({t,type,value,accuracy}) into the receiver. */
    private fun parseReading(obj: JsonObject) {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
        val value = obj["value"]?.jsonPrimitive?.floatOrNull ?: return
        val accuracy = obj["accuracy"]?.jsonPrimitive?.intOrNull
        val t = obj["t"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
        receiver.onReading(WatchReading(type, value, accuracy, t))
    }

    /**
     * Receives the watch's historical store flush as DataItems (path [FLUSH_PATH_PREFIX]/…). For each
     * changed item: parse its newline-joined `rows`, ingest them into the receiver (NOT as live data —
     * they keep their original watch timestamps), report chunk progress to [WatchSensorReceiver] so the
     * End-Session flow can wait for the whole batch, then **delete the DataItem** (the Data-Layer
     * cleanup — it does NOT touch the watch's durable store).
     *
     * Crucially, this no longer sends `FLUSH_ACK`. The ack (which truncates the watch store) is sent by
     * the session-end flow *after* the readings are drained into the DB, so a late or partial flush can
     * never destroy data the phone hasn't persisted. Idempotent: re-delivery is de-duped downstream by
     * the per-(scenario,type) high-water marks (readings) and the per-index chunk set (progress).
     */
    override fun onDataChanged(events: DataEventBuffer) {
        val toDelete = ArrayList<android.net.Uri>()

        for (event in events) {
            val uri = event.dataItem.uri
            if (!uri.path.orEmpty().startsWith(FLUSH_PATH_PREFIX)) continue
            if (event.type != DataEvent.TYPE_CHANGED) continue
            try {
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                val rows = map.getString("rows").orEmpty()
                val batchId = map.getLong("batchId", 0L)
                val index = map.getInt("index", 0)
                val count = map.getInt("count", 1)
                var chunkMaxTs = Long.MIN_VALUE
                val flushed = ArrayList<WatchReading>()
                rows.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    parseFlushedLine(line)?.let {
                        flushed += it
                        if (it.timestampMs > chunkMaxTs) chunkMaxTs = it.timestampMs
                    }
                }
                if (flushed.isNotEmpty()) receiver.onFlushedReadings(flushed)
                receiver.onFlushChunk(batchId, index, count, chunkMaxTs)
                Log.i(TAG, "flush chunk $index/$count: ${flushed.size} readings (maxTs=$chunkMaxTs)")
                toDelete += uri
            } catch (e: Exception) {
                Log.w(TAG, "bad flush item $uri", e)
            }
        }

        // Delete consumed DataItems (Data-Layer cleanup only; the watch store is untouched until the
        // session-end flow sends FLUSH_ACK after persisting).
        if (toDelete.isNotEmpty()) {
            val dataClient = Wearable.getDataClient(this)
            toDelete.forEach { uri -> runCatching { dataClient.deleteDataItems(uri) } }
        }
    }

    /** Parse one stored reading line into a [WatchReading], or null if malformed. */
    private fun parseFlushedLine(line: String): WatchReading? = try {
        val obj = json.parseToJsonElement(line).jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        val value = obj["value"]?.jsonPrimitive?.floatOrNull
        val t = obj["t"]?.jsonPrimitive?.longOrNull
        if (type != null && value != null && t != null) {
            WatchReading(type, value, obj["accuracy"]?.jsonPrimitive?.intOrNull, t)
        } else null
    } catch (e: Exception) {
        null
    }
}
