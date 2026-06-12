package com.biometrix.operator.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Flushes the durable [WatchSampleStore] to the phone using **`DataClient` (DataItems)** — the
 * Wear primitive Google documents as buffering while disconnected and auto-syncing on reconnect
 * (unlike best-effort `MessageClient`). This is what makes the historical-data transfer reliable:
 * if the link is briefly down (Doze, range), the DataItems sync once it's back, even if the phone
 * app wasn't running (the system starts its listener).
 *
 * **Protocol.**
 *  - Read all stored lines, split into [CHUNK_SIZE] chunks, write each as a DataItem under
 *    [FLUSH_PATH_PREFIX]/<index>. A timestamp in the path key makes each flush's items unique so a
 *    re-flush doesn't collide with an un-consumed prior one.
 *  - The phone consumes each DataItem (persists rows), then **deletes** it (its ack to the Data Layer)
 *    and sends a `FLUSH_ACK` command back with the max timestamp it stored.
 *  - On `FLUSH_ACK` the service calls [WatchSampleStore.truncateThrough], dropping acked rows. Un-acked
 *    rows survive for the next flush. The phone's high-water-mark de-dup makes any re-send idempotent.
 *
 * A DataItem is capped at ~100 KB; chunking keeps each well under that. Rows are sent as a single
 * newline-joined string asset-free payload (small enough for DataItem data).
 */
class WatchFlushWriter(context: Context) {

    private companion object {
        const val TAG = "WatchFlushWriter"
        const val FLUSH_PATH_PREFIX = "/biometrix/flush"
        /** Rows per DataItem. ~1 Hz × 3 streams → a few hundred rows keeps each item well under 100 KB. */
        const val CHUNK_SIZE = 300
    }

    private val dataClient = Wearable.getDataClient(context)

    /** Outcome of a flush: lets the caller send the phone a `FLUSH_COMPLETE` marker it can wait on. */
    data class FlushResult(val batchId: Long, val chunkCount: Int, val rowCount: Int)

    /**
     * Write the current store contents to the phone as chunked DataItems. Returns a [FlushResult]
     * describing the batch (`chunkCount == 0` / `rowCount == 0` when the store was empty). Does NOT
     * truncate — truncation happens only on the phone's `FLUSH_ACK`, so an undelivered flush is
     * safely retried.
     */
    suspend fun flush(lines: List<String>): FlushResult {
        if (lines.isEmpty()) {
            Log.i(TAG, "flush: nothing to send")
            return FlushResult(batchId = 0L, chunkCount = 0, rowCount = 0)
        }
        val batchId = System.currentTimeMillis()
        val chunks = lines.chunked(CHUNK_SIZE)
        chunks.forEachIndexed { index, chunk ->
            val path = "$FLUSH_PATH_PREFIX/$batchId/$index"
            val req = PutDataMapRequest.create(path).apply {
                dataMap.putString("rows", chunk.joinToString("\n"))
                dataMap.putInt("index", index)
                dataMap.putInt("count", chunks.size)
                dataMap.putLong("batchId", batchId)
            }.asPutDataRequest().setUrgent() // urgent: deliver promptly when a link is available
            try {
                dataClient.putDataItem(req).await()
                Log.i(TAG, "flush chunk $index/${chunks.size} (${chunk.size} rows) → $path")
            } catch (e: Exception) {
                // DataClient still buffers the put locally and syncs later; log and continue.
                Log.w(TAG, "putDataItem failed for $path (will sync when linked)", e)
            }
        }
        return FlushResult(batchId = batchId, chunkCount = chunks.size, rowCount = lines.size)
    }
}
