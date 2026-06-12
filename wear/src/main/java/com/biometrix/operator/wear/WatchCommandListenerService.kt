package com.biometrix.operator.wear

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking

/**
 * Receives phone → watch commands over the Data Layer ([COMMAND_PATH]). This is the side that lets the
 * phone "wake" the watch and pull stored data without a manual tap on the wrist.
 *
 * The system starts (and, if needed, launches the app for) this [WearableListenerService] on an
 * incoming message even when our app isn't in the foreground — that's the documented mechanism behind
 * remote wake. Uses Play-Services Wearable (not the deprecated Support Library).
 *
 * Commands (plain string payloads):
 *  - `START`     — begin continuous tracking. Promotes [WatchSensorService] to a foreground `health`
 *                  service. ⚠️ Starting an FGS from this background-delivered callback is only legal via
 *                  an exemption: we declare `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`
 *                  in the manifest (Companion Device Manager). Without it this throws
 *                  `ForegroundServiceStartNotAllowedException` on API 31+.
 *  - `FLUSH`     — push the durable store to the phone as DataItems. **No FGS needed** — this listener
 *                  is already alive for the callback; the read+write is a short one-shot job.
 *  - `FLUSH_ACK:<ts>` — phone confirms it persisted rows up to `<ts>`; truncate the store through it.
 *  - `STOP`      — stop tracking (mirrors the on-watch Stop button).
 */
class WatchCommandListenerService : WearableListenerService() {

    private companion object {
        const val TAG = "WatchCommandListener"
        const val COMMAND_PATH = "/biometrix/command"
        const val CMD_START = "START"
        const val CMD_STOP = "STOP"
        const val CMD_FLUSH = "FLUSH"
        const val CMD_FLUSH_ACK = "FLUSH_ACK" // followed by ":<timestamp>"
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != COMMAND_PATH) return
        // onMessageReceived already runs on a background binder thread, and the system keeps this
        // service bound for the duration of the call — so doing the (short) flush/truncate work
        // synchronously here is safe and keeps us alive until it completes (no goAsync needed; that's
        // a BroadcastReceiver API, not available on a Service).
        val command = String(event.data, Charsets.UTF_8).trim()
        Log.i(TAG, "command received: $command")
        when {
            command == CMD_START -> startTracking()
            command == CMD_STOP -> stopTracking()
            command == CMD_FLUSH -> flushStore()
            command.startsWith("$CMD_FLUSH_ACK:") -> {
                val ts = command.substringAfter(":").toLongOrNull()
                if (ts != null) truncateStore(ts) else Log.w(TAG, "FLUSH_ACK without ts: $command")
            }
            else -> Log.w(TAG, "unknown command: $command")
        }
    }

    private fun startTracking() {
        // FGS-from-background — legal only via the Companion exemption declared in the manifest.
        val i = Intent(this, WatchSensorService::class.java).apply {
            action = WatchSensorService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(this, i)
        } catch (e: Exception) {
            // Most likely ForegroundServiceStartNotAllowedException if the exemption is missing/denied.
            Log.e(TAG, "remote START failed (FGS background-start blocked?)", e)
        }
    }

    private fun stopTracking() {
        val i = Intent(this, WatchSensorService::class.java).apply {
            action = WatchSensorService.ACTION_STOP
        }
        try {
            startService(i)
        } catch (e: Exception) {
            Log.e(TAG, "remote STOP failed", e)
        }
    }

    /**
     * Read the store and write it to the phone as DataItems, then send a `FLUSH_COMPLETE` marker so
     * the phone knows the transfer is finished (and how many chunks to expect). No FGS — short
     * one-shot job; we run it (incl. the completion send) inside [runBlocking] so it finishes before
     * the service is unbound.
     */
    private fun flushStore() {
        try {
            val store = WatchSampleStore(applicationContext)
            val writer = WatchFlushWriter(applicationContext)
            val sender = WatchDataSender(applicationContext)
            runBlocking {
                // Drain any screen-off backlog still buffered in the SDK INTO the store before reading
                // it — a just-woken watch otherwise reads its store before the flush loop has persisted
                // those samples, and the screen-off data is missed.
                WatchSensorService.flushSdkBufferToStore()
                val rows = store.readAll()
                val result = writer.flush(rows)
                Log.i(TAG, "flush dispatched ${result.rowCount} rows in ${result.chunkCount} chunks")
                sender.sendLineBlocking(
                    WatchMessage.flushComplete(result.batchId, result.chunkCount, result.rowCount)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "flush failed", e)
        }
    }

    private fun truncateStore(ackThroughTs: Long) {
        try {
            WatchSampleStore(applicationContext).truncateThrough(ackThroughTs)
            Log.i(TAG, "store truncated through $ackThroughTs")
        } catch (e: Exception) {
            Log.e(TAG, "truncate failed", e)
        }
    }
}
