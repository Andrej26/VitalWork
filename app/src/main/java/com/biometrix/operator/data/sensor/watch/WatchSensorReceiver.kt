package com.biometrix.operator.data.sensor.watch

import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.sensor.watch.model.WatchReading
import com.biometrix.operator.data.time.TimeProvider
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
 * [com.biometrix.operator.data.repository.ConnectionRepository] observe the exposed flows.
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
        // Data arrives ~1 Hz (flush loop). 6 s of silence reliably means a real drop, not a hiccup —
        // the extra headroom over the bare 1 Hz cadence absorbs an occasional 1–2 s gap (e.g. a 2 s
        // flush cadence, or a brief Doze stall) so the state doesn't flap CONNECTED↔DISCONNECTED.
        // A normal Stop is signalled explicitly via onStop() for instant DISCONNECTED; this watchdog
        // is the safety net for the watch dying / going out of range (no goodbye sent).
        const val INACTIVITY_TIMEOUT_MS = 6_000L
        const val POLL_INTERVAL_MS = 1_000L

        // A reading whose watch-stamp is within this window of arrival is "live" (not Doze backlog),
        // so its (arrival − t) is a trustworthy phone↔watch clock offset. Burst-delivered samples
        // have a much larger (arrival − t) and must NOT set the offset.
        const val LIVE_READING_WINDOW_MS = 2_000L

        // The watch beats a HEARTBEAT every ~30 s. If no readings arrive but a heartbeat lands within
        // this window, the watch is alive-but-dozing (buffering), NOT gone — so we show DOZING instead
        // of DISCONNECTED. ~95 s ≈ 3 missed beats of headroom so a single dropped beat doesn't flap.
        // Only after this longer silence (no readings AND no heartbeat) do we declare DISCONNECTED.
        const val HEARTBEAT_TIMEOUT_MS = 95_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Last time ANY message arrived (reading, heartbeat, capabilities) — drives "truly gone". */
    @Volatile
    private var lastMessageMs: Long = 0L
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
     * Phone↔watch clock offset in ms: `phoneClock ≈ watchClock + offsetMs`. Captured once from the
     * first genuinely-live reading after each (re)connect (see [LIVE_READING_WINDOW_MS]); reset to
     * "uncaptured" on disconnect so a fresh offset is taken next session. 0 until captured (= trust
     * the watch clock as-is, the safe fallback).
     */
    @Volatile
    private var clockOffsetMs: Long = 0L
    @Volatile
    private var offsetCaptured: Boolean = false

    /**
     * Map a watch-stamped timestamp onto the NTP-corrected timeline used for all persisted samples.
     * [clockOffsetMs] (captured on the raw device clock) lifts the watch stamp onto the tablet's
     * device clock; [TimeProvider.ntpOffsetMs] then lifts that onto true UTC. Keeping the capture on
     * the raw device clock (see [maybeCaptureClockOffset]) keeps the ±[LIVE_READING_WINDOW_MS] window
     * valid even when the device clock is far from NTP.
     */
    fun correctedTimestamp(watchTimestampMs: Long): Long =
        watchTimestampMs + clockOffsetMs + timeProvider.ntpOffsetMs()

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
        maybeCaptureClockOffset(reading, arrivalMs)
        _latestByType.value = _latestByType.value.toMutableMap().apply { put(reading.type, reading) }
        when (reading.type) {
            "BATTERY" -> _batteryLevel.value = reading.value.toInt()
            "EDA", "HR", "IBI" -> _watchSampleFlow.tryEmit(reading)
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
     * Feed historical readings flushed from the watch's durable store (DataItems) into the same
     * sample stream the live path uses, so the recording layer's session buffer + End-Session drain
     * handle them identically. These are NOT "live" (their watch-stamp is old), so they must not move
     * the connection state or the clock offset — hence this bypasses [markActivity]/offset capture.
     */
    fun onFlushedReadings(readings: List<WatchReading>) {
        readings.forEach { reading ->
            if (reading.type == "EDA" || reading.type == "HR" || reading.type == "IBI") {
                _watchSampleFlow.tryEmit(reading)
            }
        }
    }

    /**
     * Capture the phone↔watch clock offset once per connection, from the first reading that is
     * genuinely live (arrival ≈ watch-stamp). Burst-delivered samples (large arrival − t) are skipped
     * so a watch connecting mid-Doze-backlog can't poison the offset for the whole session.
     */
    private fun maybeCaptureClockOffset(reading: WatchReading, arrivalMs: Long) {
        if (offsetCaptured) return
        val delta = arrivalMs - reading.timestampMs
        if (kotlin.math.abs(delta) <= LIVE_READING_WINDOW_MS) {
            clockOffsetMs = delta
            offsetCaptured = true
        }
    }

    /** Watch signalled it stopped tracking → reflect DISCONNECTED immediately, no watchdog wait. */
    @Synchronized
    fun onStop() {
        watchdogJob?.cancel(); watchdogJob = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _linkStatus.value = WatchLinkStatus.DISCONNECTED
        lastReadingMs = 0L
        // Force a fresh clock offset on the next connection.
        offsetCaptured = false
        clearLiveData()
    }

    /**
     * Drop the last-seen live values so a disconnected watch doesn't leave stale data on screen: the
     * Sensors screen's "Live readings" return to "Waiting for data…", and the Home low-battery banner
     * clears (no more warning for a watch that is no longer connected). Called on any transition to
     * DISCONNECTED — explicit Stop ([onStop]) or the watchdog declaring the link gone ([recomputeStatus]).
     */
    private fun clearLiveData() {
        _latestByType.value = emptyMap()
        _batteryLevel.value = null
        _availableTrackers.value = emptyList()
    }

    /** Record a message arrival, recompute the link status, and ensure the watchdog is running. */
    private fun markActivity(arrivalMs: Long) {
        lastMessageMs = arrivalMs
        recomputeStatus(arrivalMs)
        ensureWatchdog()
    }

    /**
     * Derive the three-way link status from the two timestamps:
     *  - a recent **reading** → LIVE,
     *  - else a recent **message** (heartbeat) → DOZING (alive, buffering),
     *  - else → DISCONNECTED (truly gone).
     * [connectionState] mirrors this coarsely: LIVE/DOZING → CONNECTED, DISCONNECTED → DISCONNECTED,
     * so existing consumers treat a dozing watch as still present.
     */
    private fun recomputeStatus(now: Long) {
        val previous = _linkStatus.value
        val status = when {
            now - lastReadingMs <= INACTIVITY_TIMEOUT_MS -> WatchLinkStatus.LIVE
            now - lastMessageMs <= HEARTBEAT_TIMEOUT_MS -> WatchLinkStatus.DOZING
            else -> WatchLinkStatus.DISCONNECTED
        }
        _linkStatus.value = status
        _connectionState.value =
            if (status == WatchLinkStatus.DISCONNECTED) ConnectionState.DISCONNECTED
            else ConnectionState.CONNECTED
        if (status == WatchLinkStatus.DISCONNECTED) {
            offsetCaptured = false
            // Clear stale live values only on the edge into DISCONNECTED (the watchdog polls ~1 Hz,
            // so guard against re-clearing every tick while the watch stays gone).
            if (previous != WatchLinkStatus.DISCONNECTED) clearLiveData()
        }
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
