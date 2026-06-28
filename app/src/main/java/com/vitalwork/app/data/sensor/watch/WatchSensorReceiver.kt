package com.vitalwork.app.data.sensor.watch

import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.sensor.watch.model.WatchReading
import com.vitalwork.app.data.time.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton sink for Galaxy Watch sensor data arriving as Data Layer messages.
 *
 * [WatchListenerService] (framework-instantiated) feeds parsed readings in here; the UI and
 * [com.vitalwork.app.data.repository.ConnectionRepository] observe the exposed flows.
 *
 * Connection state is **inferred** (messages are stateless, fire-and-forget): CONNECTED while
 * messages keep arriving, DISCONNECTED after [INACTIVITY_TIMEOUT_MS] of silence. A single poll
 * loop watches a volatile timestamp — race-free vs. rearming a debounce job from a binder thread.
 *
 * Phase 1 = live display only (no DB / no recording).
 */
@Singleton
class WatchSensorReceiver @Inject constructor(
    private val timeProvider: TimeProvider
) {

    private companion object {
        // Data arrives ~1 Hz (flush loop). 6 s of silence reliably means readings have stopped, not a
        // hiccup — the extra headroom over the bare 1 Hz cadence absorbs an occasional 1–2 s gap (e.g. a
        // 2 s flush cadence, or a brief Doze stall) so the status doesn't flap LIVE↔DOZING.
        const val INACTIVITY_TIMEOUT_MS = 6_000L
        const val POLL_INTERVAL_MS = 1_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Last time an actual sensor READING arrived — drives LIVE vs DOZING. */
    @Volatile
    private var lastReadingMs: Long = 0L
    private var watchdogJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Finer-grained watch link state for the UI and session-end logic. Distinguishes a watch that is
     * **dozing/buffering** (still alive, just delivering in bursts during Doze) from one that is truly
     * **disconnected** — so the operator isn't alarmed by an *expected* screen-off gap. [connectionState]
     * stays the coarse CONNECTED/DISCONNECTED that the rest of the app consumes (DOZING maps to CONNECTED
     * there, since a dozing watch is not "gone").
     */
    private val _linkStatus = MutableStateFlow(WatchLinkStatus.DISCONNECTED)
    val linkStatus: StateFlow<WatchLinkStatus> = _linkStatus.asStateFlow()

    private val _latestByType = MutableStateFlow<Map<String, WatchReading>>(emptyMap())
    /** Most recent reading per sensor type (HR, IBI, EDA, BATTERY, …). */
    val latestByType: StateFlow<Map<String, WatchReading>> = _latestByType.asStateFlow()

    private val _availableTrackers = MutableStateFlow<List<String>>(emptyList())
    /** Tracker types the connected watch reports as supported. */
    val availableTrackers: StateFlow<List<String>> = _availableTrackers.asStateFlow()

    /**
     * Progress of the End-Session store flush handshake (see [WatchFlushState]). The session-end flow
     * resets it via [onFlushStarted] before sending `FLUSH`, then waits for [WatchFlushState.Complete]
     * before persisting + acking — so the watch store is never truncated before the phone has the data.
     */
    private val _flushState = MutableStateFlow<WatchFlushState>(WatchFlushState.Idle)
    val flushState: StateFlow<WatchFlushState> = _flushState.asStateFlow()

    // --- Flush handshake bookkeeping (mutated from binder threads; guarded by @Synchronized) ---
    private var flushBatchId: Long? = null
    private val flushReceivedIdx = HashSet<Int>()
    private var flushExpectedChunks: Int? = null
    private var flushRowCount: Int = 0
    private var flushMaxWatchTs: Long = Long.MIN_VALUE

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    /**
     * Per-sample stream of the recordable watch sensors — EDA, HR, and IBI — each carrying its
     * watch-stamped timestamp, type, and value. Used by the recording layer. Hot, no replay;
     * DROP_OLDEST so a slow collector can never block the binder thread that calls [onReading].
     * Apply [correctedTimestamp] to each reading's timestamp before storage. Larger buffer than the
     * old EDA-only flow because a screen-off burst can carry HR+IBI+EDA together.
     */
    private val _watchSampleFlow = MutableSharedFlow<WatchReading>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val watchSampleFlow: SharedFlow<WatchReading> = _watchSampleFlow.asSharedFlow()

    /**
     * Smallest observed `(arrival − captureTime)` over live readings this connection — a non-corrective
     * proxy for delivery latency + watch↔phone clock skew (bursts have large positive deltas, so the
     * min reflects the most-live sample). Reset on disconnect; surfaced in the session-end report to
     * flag a mis-set watch clock. `null` until a reading is seen.
     */
    @Volatile
    private var minLiveSkewMs: Long = Long.MAX_VALUE

    /** Observed clock-skew proxy (see [minLiveSkewMs]); null if no live reading was seen. */
    fun observedClockSkewMs(): Long? = minLiveSkewMs.takeIf { it != Long.MAX_VALUE }

    /**
     * Map a watch-stamped timestamp onto the NTP-corrected timeline used for all persisted samples.
     * The watch now stamps each reading's **true capture time** on its own system clock, which Wear OS
     * keeps in sync with the phone over Bluetooth (auto-time) — so a single [TimeProvider.ntpOffsetMs]
     * lift onto true UTC is all that's needed, identical to the correction eSense samples already get
     * ([com.vitalwork.app.data.recording.ScenarioRecordingRepositoryImpl] via `timeProvider.nowMs()`).
     * No per-connection offset capture: that was fragile (single-sample, poisonable by Doze bursts) and,
     * with honest capture times, would wrongly bake in transmission/sensor latency.
     */
    fun correctedTimestamp(watchTimestampMs: Long): Long =
        watchTimestampMs + timeProvider.ntpOffsetMs()

    /**
     * Snapshot of the low-battery alert tier from the **last-known** battery level, read on demand
     * (e.g. when the Home screen resumes between sessions). Reflects the last value the watch reported
     * while connected; a disconnect clears `_batteryLevel` (see [clearLiveData]) so a watch that is no
     * longer connected leaves no stale warning. CRITICAL is checked first so it always wins.
     *
     * Boundary semantics: `<=`, matching the eSense Pulse low-battery check.
     */
    fun currentBatteryAlert(): WatchBatteryAlert {
        val lvl = _batteryLevel.value ?: return WatchBatteryAlert.NONE
        return when {
            lvl <= WatchBatteryThresholds.CRITICAL_PCT -> WatchBatteryAlert.CRITICAL
            lvl <= WatchBatteryThresholds.WARNING_PCT -> WatchBatteryAlert.WARNING
            else -> WatchBatteryAlert.NONE
        }
    }

    fun onCapabilities(csv: String) {
        markActivity(System.currentTimeMillis())
        _availableTrackers.value = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun onReading(reading: WatchReading) {
        val arrivalMs = System.currentTimeMillis()
        lastReadingMs = arrivalMs // an actual reading → eligible for LIVE
        markActivity(arrivalMs)
        val skew = arrivalMs - reading.timestampMs
        if (skew < minLiveSkewMs) minLiveSkewMs = skew // running min → clock-skew health proxy
        _latestByType.value = _latestByType.value.toMutableMap().apply { put(reading.type, reading) }
        when (reading.type) {
            "BATTERY" -> _batteryLevel.value = reading.value.toInt()
            "WATCH_EDA", "WATCH_HR", "WATCH_IBI" -> _watchSampleFlow.tryEmit(reading)
        }
    }

    /**
     * A heartbeat arrived: the watch is alive but may be dozing (no readings flowing). Counts as
     * activity (keeps us off DISCONNECTED) but NOT as a reading (so the status can be DOZING, not LIVE).
     */
    fun onHeartbeat() {
        markActivity(System.currentTimeMillis())
    }

    /**
     * Historical readings flushed from the watch's durable store, buffered **losslessly** until the
     * End-Session drain pulls them via [takeFlushedReadings]. A whole screen-off session is thousands
     * of rows delivered in one burst; routing those through the bounded live [_watchSampleFlow]
     * (extraBufferCapacity 256, DROP_OLDEST) silently dropped all but the newest ~256 — so only the
     * last scenario's data survived. This unbounded list never drops, so every scenario's window gets
     * its readings. Written from a binder thread (onDataChanged), read on the End-Session path.
     */
    private val flushedBuffer = java.util.Collections.synchronizedList(ArrayList<WatchReading>())

    /**
     * Buffer historical readings flushed from the watch's durable store (DataItems). These are NOT
     * "live" (their watch-stamp is old), so they must not move the connection state or the clock offset
     * (hence no [markActivity]/offset capture) and must not enter the bounded live flow. They are held
     * verbatim and corrected onto the phone clock by the recording layer at drain time.
     */
    fun onFlushedReadings(readings: List<WatchReading>) {
        readings.forEach { reading ->
            if (reading.type == "WATCH_EDA" || reading.type == "WATCH_HR" || reading.type == "WATCH_IBI") {
                flushedBuffer.add(reading)
            }
        }
    }

    /** Take (and clear) all flushed readings buffered since the last [onFlushStarted]. */
    fun takeFlushedReadings(): List<WatchReading> = synchronized(flushedBuffer) {
        ArrayList(flushedBuffer).also { flushedBuffer.clear() }
    }

    /** Reset the flush handshake before the phone sends a fresh `FLUSH` command. */
    @Synchronized
    fun onFlushStarted() {
        flushBatchId = null
        flushReceivedIdx.clear()
        flushExpectedChunks = null
        flushRowCount = 0
        flushMaxWatchTs = Long.MIN_VALUE
        flushedBuffer.clear()
        _flushState.value = WatchFlushState.InProgress(received = 0, expected = null)
    }

    /**
     * A flush DataItem chunk landed (from [WatchListenerService.onDataChanged]). Track it by index so
     * re-delivery is idempotent, learn the expected total from the chunk's `count`, and carry the max
     * raw watch timestamp seen (used as the `FLUSH_ACK` truncation point once persisted).
     */
    @Synchronized
    fun onFlushChunk(batchId: Long, index: Int, count: Int, maxWatchTsInChunk: Long) {
        adoptBatch(batchId)
        flushReceivedIdx.add(index)
        flushExpectedChunks = count
        if (maxWatchTsInChunk > flushMaxWatchTs) flushMaxWatchTs = maxWatchTsInChunk
        recomputeFlushState()
    }

    /**
     * The watch's `FLUSH_COMPLETE` marker arrived: it states the authoritative chunk/row totals for
     * this batch. An empty store (`chunkCount == 0`) completes immediately; otherwise this sets the
     * expected total so completion fires once all chunks are in.
     */
    @Synchronized
    fun onFlushComplete(batchId: Long, chunkCount: Int, rowCount: Int) {
        adoptBatch(batchId)
        flushExpectedChunks = chunkCount
        flushRowCount = rowCount
        recomputeFlushState()
    }

    /** Start tracking a new batch (clears prior received-index bookkeeping). */
    private fun adoptBatch(batchId: Long) {
        if (flushBatchId != batchId) {
            flushBatchId = batchId
            flushReceivedIdx.clear()
            flushExpectedChunks = null
            flushRowCount = 0
            flushMaxWatchTs = Long.MIN_VALUE
        }
    }

    private fun recomputeFlushState() {
        val expected = flushExpectedChunks
        val received = flushReceivedIdx.size
        _flushState.value = if (expected != null && received >= expected) {
            WatchFlushState.Complete(
                rowsReceived = flushRowCount,
                maxWatchTimestampMs = if (flushMaxWatchTs == Long.MIN_VALUE) null else flushMaxWatchTs
            )
        } else {
            WatchFlushState.InProgress(received = received, expected = expected)
        }
    }

    /** Watch signalled it stopped tracking → reflect DISCONNECTED immediately, no watchdog wait. */
    @Synchronized
    fun onStop() {
        watchdogJob?.cancel(); watchdogJob = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _linkStatus.value = WatchLinkStatus.DISCONNECTED
        lastReadingMs = 0L
        minLiveSkewMs = Long.MAX_VALUE // fresh skew measurement on the next connection
        clearLiveData()
    }

    /**
     * Drop the last-seen live values so a disconnected watch doesn't leave stale data on screen: the
     * Sensors screen's "Live readings" return to "Waiting for data…", and the Home low-battery banner
     * clears (no more warning for a watch that is no longer connected). Called only on an explicit Stop
     * ([onStop]) — the watchdog never transitions to DISCONNECTED, so a dozing watch keeps its values.
     */
    private fun clearLiveData() {
        _latestByType.value = emptyMap()
        _batteryLevel.value = null
        _availableTrackers.value = emptyList()
    }

    /** Record a message arrival, recompute the link status, and ensure the watchdog is running. */
    private fun markActivity(arrivalMs: Long) {
        recomputeStatus(arrivalMs)
        ensureWatchdog()
    }

    /**
     * Derive the link status from the last-reading timestamp:
     *  - a recent **reading** → LIVE,
     *  - else → DOZING (alive, buffering on the watch).
     * Once the watch has connected (any message seen), the watchdog never declares DISCONNECTED on its
     * own: during a session the watch is *expected* to sleep for long stretches, sampling locally and
     * shipping everything at session end, so a silence-based "Disconnected" would be a false alarm. The
     * watch keeps its last-measured values on screen while DOZING. Only an explicit Stop from the watch
     * ([onStop], normal session end) — or never having connected — reads DISCONNECTED.
     * [connectionState] mirrors this coarsely: LIVE/DOZING → CONNECTED.
     */
    private fun recomputeStatus(now: Long) {
        _linkStatus.value =
            if (now - lastReadingMs <= INACTIVITY_TIMEOUT_MS) WatchLinkStatus.LIVE
            else WatchLinkStatus.DOZING
        _connectionState.value = ConnectionState.CONNECTED
    }

    @Synchronized
    private fun ensureWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                recomputeStatus(System.currentTimeMillis())
            }
        }
    }
}

/** Three-way Galaxy Watch link state for UI + session-end logic. See [WatchSensorReceiver.linkStatus]. */
enum class WatchLinkStatus { LIVE, DOZING, DISCONNECTED }

/**
 * State of the End-Session store-flush handshake. See [WatchSensorReceiver.flushState].
 *  - [Idle]: no flush in progress.
 *  - [InProgress]: `FLUSH` sent; `received` chunks of `expected` (null until the first chunk or the
 *    `FLUSH_COMPLETE` marker reveals the total).
 *  - [Complete]: every chunk the watch sent has been received and buffered. [maxWatchTimestampMs] is
 *    the truncation point for `FLUSH_ACK` (null when the store was empty — nothing to ack).
 */
sealed interface WatchFlushState {
    data object Idle : WatchFlushState
    data class InProgress(val received: Int, val expected: Int?) : WatchFlushState
    data class Complete(val rowsReceived: Int, val maxWatchTimestampMs: Long?) : WatchFlushState
}
