package com.biometrix.operator.wear

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Append-only, durable on-watch store for sensor readings (HR / IBI / EDA).
 *
 * **Why this exists.** The watch keeps *sampling* at 1 Hz even in Doze, but live delivery to the
 * phone stalls when the AP suspends (Wear Doze, see [WatchSensorService] / doc/sensor_galaxy_watch.md).
 * Streaming alone therefore loses nothing from the *recorded* data only because the phone reconstructs
 * bursts by timestamp — but it is fragile (a dropped message = a lost sample, and a session can end
 * before a burst lands). Persisting every reading on the watch makes the dataset durable regardless of
 * link state: at session end the phone pulls the whole file, so screen-off/Doze gaps become irrelevant.
 *
 * **Format.** One JSON line per reading — the *same* shape [WatchMessage.reading] already produces, so
 * the phone parser is unchanged. Each line carries its own watch timestamp `t`, which is what makes
 * timestamp-window attribution (and truncate-after-ack) possible without ordering guarantees.
 *
 *   {"t":1717245600000,"type":"HR","value":72.0,"accuracy":1}
 *
 * **Reliability model.** Append is fire-and-forget durable (one `appendText`). Flush reads the whole
 * file; the phone acks the max timestamp it persisted; [truncateThrough] drops every line at-or-before
 * that timestamp and keeps the rest. So an interrupted/partial flush simply re-sends the un-acked tail
 * next time — no data loss, and the phone-side high-water-mark de-dup makes re-delivery idempotent.
 *
 * Thread-safe: every file operation is `synchronized(lock)`. Appends happen on the Samsung SDK callback
 * thread; flush/truncate happen on the command-handler thread.
 */
class WatchSampleStore(dir: File) {

    /** Production constructor: store lives in the app's private files dir. */
    constructor(context: Context) : this(context.filesDir)

    private companion object {
        const val TAG = "WatchSampleStore"
        const val FILE_NAME = "watch_samples.jsonl"
        /** Reading types that are study data and must be persisted (battery/heartbeat are not). */
        val PERSISTED_TYPES = setOf("HR", "IBI", "EDA")
    }

    private val lock = Any()
    private val file = File(dir, FILE_NAME)

    /** Append one already-encoded JSON reading line. Called per sample from the SDK callback. */
    fun append(jsonLine: String) {
        synchronized(lock) {
            try {
                file.appendText(jsonLine + "\n")
            } catch (e: Exception) {
                Log.e(TAG, "append failed", e)
            }
        }
    }

    /** True if [type] is study data that belongs in the durable store. */
    fun shouldPersist(type: String): Boolean = type in PERSISTED_TYPES

    /** Snapshot every stored line (oldest first). Empty if nothing buffered. */
    fun readAll(): List<String> = synchronized(lock) {
        try {
            if (!file.exists()) emptyList()
            else file.readLines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "readAll failed", e)
            emptyList()
        }
    }

    /**
     * Drop every persisted line whose timestamp `t` is `<= ackThroughTs`, keeping the rest. Called
     * after the phone confirms it stored up to [ackThroughTs]. Lines without a parseable `t` are kept
     * (can't prove they were acked). Rewrites the file atomically via a temp file + rename so a crash
     * mid-truncate can't corrupt the store.
     */
    fun truncateThrough(ackThroughTs: Long) {
        synchronized(lock) {
            try {
                if (!file.exists()) return
                val kept = file.readLines().filter { line ->
                    if (line.isBlank()) return@filter false
                    val t = parseTimestamp(line) ?: return@filter true // unknown ts → keep, be safe
                    t > ackThroughTs
                }
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
                if (!tmp.renameTo(file)) {
                    // Fallback if rename across the same dir somehow fails: overwrite in place.
                    file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "truncateThrough failed", e)
            }
        }
    }

    /** Wipe the store entirely (e.g. on a fresh START so a new session doesn't inherit stale rows). */
    fun clear() {
        synchronized(lock) {
            try {
                if (file.exists()) file.writeText("")
            } catch (e: Exception) {
                Log.e(TAG, "clear failed", e)
            }
        }
    }

    /**
     * Extract the `t` value from a reading line without a full JSON parse (keeps the hot append path
     * dependency-free and matches [WatchMessage]'s hand-built format `{"t":<long>,...}`).
     */
    private fun parseTimestamp(line: String): Long? {
        val key = "\"t\":"
        val start = line.indexOf(key)
        if (start < 0) return null
        var i = start + key.length
        val sb = StringBuilder()
        while (i < line.length && (line[i].isDigit() || line[i] == '-')) {
            sb.append(line[i]); i++
        }
        return sb.toString().toLongOrNull()
    }
}
