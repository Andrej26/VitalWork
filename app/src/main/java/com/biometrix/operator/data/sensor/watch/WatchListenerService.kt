package com.biometrix.operator.data.sensor.watch

import android.util.Log
import com.biometrix.operator.data.sensor.watch.model.WatchReading
import com.google.android.gms.wearable.MessageEvent
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
}
