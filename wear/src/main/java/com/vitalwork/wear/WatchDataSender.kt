package com.vitalwork.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 *   {"t":1717245600000,"type":"WATCH_HR","value":72.0,"accuracy":0}
 *   {"t":1717245600000,"type":"CAPABILITIES","text":"HEART_RATE_CONTINUOUS,EDA_CONTINUOUS"}
 */
class WatchDataSender(context: Context) {

    companion object {
        private const val TAG = "WatchDataSender"
        const val PHONE_CAPABILITY = "vitalwork_phone"
        const val MESSAGE_PATH = "/vitalwork/sensors"

        /** Retry node resolution at Start so a not-yet-propagated tablet capability isn't fatal. */
        private const val CONNECT_RETRIES = 10
        private const val CONNECT_RETRY_DELAY_MS = 1_500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)

    @Volatile
    private var cachedNodeId: String? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    /**
     * Resolve the paired tablet node, retrying for a while. The tablet only advertises the
     * [PHONE_CAPABILITY] while its app process is alive, and that capability can take several
     * seconds to propagate across the Data Layer (worse on a cross-vendor pairing). A single
     * lookup at Start therefore loses a race if the phone app isn't already up — which presented
     * as "tap Start, nothing happens". Retry with backoff so the watch keeps looking instead of
     * giving up after one attempt.
     */
    fun connect(onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            var node = resolveNodeId()
            var attempt = 0
            while (node == null && attempt < CONNECT_RETRIES) {
                attempt++
                Log.w(TAG, "Tablet '$PHONE_CAPABILITY' not found yet; retry $attempt/$CONNECT_RETRIES")
                delay(CONNECT_RETRY_DELAY_MS)
                node = resolveNodeId()
            }
            isConnected = node != null
            if (node == null) {
                Log.w(TAG, "No paired tablet advertising '$PHONE_CAPABILITY' found after $attempt retries")
            } else {
                Log.i(TAG, "Resolved tablet node $node")
            }
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
            // Only cache a real hit — never cache null, or a Start-time miss would latch forever
            // and no later send could ever re-resolve once the phone app comes up.
            if (id != null) cachedNodeId = id
            id
        } catch (e: Exception) {
            Log.e(TAG, "node resolve failed", e)
            null
        }
    }

    /** Send one already-encoded JSON line as a single best-effort message. */
    fun sendLine(jsonLine: String) = send(jsonLine)

    /**
     * Send one line and await the result, so a caller running inside a short-lived
     * [WearableListenerService] callback (e.g. the flush handler) can guarantee delivery is dispatched
     * before it returns and the service is unbound. Returns true if the message was handed off.
     */
    suspend fun sendLineBlocking(jsonLine: String): Boolean {
        val node = resolveNodeId() ?: run { isConnected = false; return false }
        return try {
            messageClient.sendMessage(node, MESSAGE_PATH, jsonLine.toByteArray(Charsets.UTF_8)).await()
            isConnected = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "sendLineBlocking failed; will re-resolve node", e)
            cachedNodeId = null
            isConnected = false
            false
        }
    }

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
