package com.vitalwork.app.data.sensor.watch

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends phone → watch commands over the Data Layer ([COMMAND_PATH]) — the new direction that lets the
 * tablet remotely START tracking, request a FLUSH of the watch's durable store, STOP, or ACK a flush.
 *
 * Interface + impl ([WatchCommandSenderImpl], bound in DI) so consumers (ViewModels, the listener
 * service) depend on the abstraction and can be unit-tested without an Android Context / Play Services.
 *
 * `MessageClient` (the impl's transport) is best-effort (no delivery guarantee) — which is fine here:
 *  - bulk data does NOT ride this channel (that's `DataClient`, reliable, see WatchFlushWriter);
 *  - a missed FLUSH command just means the operator taps the watch (manual fallback) and no data is
 *    lost (the watch store is durable until acked).
 */
interface WatchCommandSender {
    /** Ask the watch to begin tracking (remote equivalent of tapping Start on the wrist). */
    suspend fun sendStart(): Boolean
    /** Ask the watch to flush its durable store to the phone (as DataItems). */
    suspend fun sendFlush(): Boolean
    /** Ask the watch to stop tracking. */
    suspend fun sendStop(): Boolean
    /** Confirm to the watch that rows up to [throughTimestampMs] are persisted; it then truncates. */
    suspend fun sendFlushAck(throughTimestampMs: Long): Boolean

    companion object {
        const val WATCH_CAPABILITY = "vitalwork_watch"
        const val COMMAND_PATH = "/vitalwork/command"
        const val CMD_START = "START"
        const val CMD_STOP = "STOP"
        const val CMD_FLUSH = "FLUSH"
        const val CMD_FLUSH_ACK = "FLUSH_ACK" // sent as "FLUSH_ACK:<timestamp>"
    }
}

/** Resolves the watch node via [CapabilityClient] and sends a small `MessageClient` payload. */
@Singleton
class WatchCommandSenderImpl @Inject constructor(
    @ApplicationContext context: Context
) : WatchCommandSender {

    private companion object {
        const val TAG = "WatchCommandSender"
    }

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)

    override suspend fun sendStart(): Boolean = send(WatchCommandSender.CMD_START)

    override suspend fun sendFlush(): Boolean = send(WatchCommandSender.CMD_FLUSH)

    override suspend fun sendStop(): Boolean = send(WatchCommandSender.CMD_STOP)

    override suspend fun sendFlushAck(throughTimestampMs: Long): Boolean =
        send("${WatchCommandSender.CMD_FLUSH_ACK}:$throughTimestampMs")

    private suspend fun send(command: String): Boolean {
        val nodeId = resolveWatchNode() ?: run {
            Log.w(TAG, "no watch node advertising '${WatchCommandSender.WATCH_CAPABILITY}'; '$command' not sent")
            return false
        }
        return try {
            messageClient.sendMessage(
                nodeId, WatchCommandSender.COMMAND_PATH, command.toByteArray(Charsets.UTF_8)
            ).await()
            Log.i(TAG, "sent '$command' to $nodeId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage '$command' failed (best-effort)", e)
            false
        }
    }

    private suspend fun resolveWatchNode(): String? = try {
        val info = capabilityClient
            .getCapability(WatchCommandSender.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await()
        val nodes = info.nodes
        (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
    } catch (e: Exception) {
        Log.e(TAG, "watch node resolve failed", e)
        null
    }
}
