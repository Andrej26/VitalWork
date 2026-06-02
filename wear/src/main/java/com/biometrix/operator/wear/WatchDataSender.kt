package com.biometrix.operator.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Sends sensor readings to the paired tablet as individual [MessageClient] messages.
 *
 * Stateless / fire-and-forget: each reading is one independent message on [MESSAGE_PATH].
 * There is no persistent stream to stall or "freeze" — a dropped message just loses that one
 * sample (fine for a live diagnostic display). Runs Bluetooth-direct over the Data Layer,
 * no internet required.
 *
 * One message body per reading (newline not required, but kept identical to the prior format
 * so the phone parser is unchanged):
 *   {"t":1717245600000,"type":"HR","value":72.0,"accuracy":0}
 *   {"t":1717245600000,"type":"CAPABILITIES","text":"HEART_RATE_CONTINUOUS,EDA_CONTINUOUS"}
 */
class WatchDataSender(context: Context) {

    companion object {
        private const val TAG = "WatchDataSender"
        const val PHONE_CAPABILITY = "biometrix_phone"
        const val MESSAGE_PATH = "/biometrix/sensors"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)

    @Volatile
    private var cachedNodeId: String? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    /** Resolve the paired tablet node. Safe to call repeatedly. */
    fun connect(onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            val node = resolveNodeId()
            isConnected = node != null
            if (node == null) Log.w(TAG, "No paired tablet advertising '$PHONE_CAPABILITY' found")
            onResult(node != null)
        }
    }

    private suspend fun resolveNodeId(): String? {
        cachedNodeId?.let { return it }
        return try {
            val capInfo = capabilityClient
                .getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
            val nodes = capInfo.nodes
            val id = (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
            cachedNodeId = id
            id
        } catch (e: Exception) {
            Log.e(TAG, "node resolve failed", e)
            null
        }
    }

    /** Send one already-encoded JSON line as a single best-effort message. */
    fun sendLine(jsonLine: String) = send(jsonLine)

    /** Send several readings from one SDK callback as ONE bundled best-effort message. */
    fun sendBatch(lines: List<String>) {
        if (lines.isEmpty()) return
        send(if (lines.size == 1) lines[0] else WatchMessage.batch(lines))
    }

    private fun send(body: String) {
        scope.launch {
            val node = resolveNodeId()
            if (node == null) {
                isConnected = false
                return@launch
            }
            messageClient.sendMessage(node, MESSAGE_PATH, body.toByteArray(Charsets.UTF_8))
                .addOnSuccessListener {
                    isConnected = true
                }
                .addOnFailureListener { e ->
                    // Best-effort: the node may have moved/disconnected. Drop the cache so the
                    // next send re-resolves; this batch is lost, which is acceptable.
                    Log.w(TAG, "sendMessage failed; will re-resolve node", e)
                    cachedNodeId = null
                    isConnected = false
                }
        }
    }

    /** Stateless transport — nothing to tear down except marking not-connected. */
    fun close() {
        cachedNodeId = null
        isConnected = false
    }
}
