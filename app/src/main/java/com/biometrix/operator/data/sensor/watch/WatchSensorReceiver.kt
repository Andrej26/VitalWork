package com.biometrix.operator.data.sensor.watch

import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.sensor.watch.model.WatchReading
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
class WatchSensorReceiver @Inject constructor() {

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var lastMessageMs: Long = 0L
    private var watchdogJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _latestByType = MutableStateFlow<Map<String, WatchReading>>(emptyMap())
    /** Most recent reading per sensor type (HR, IBI, EDA, BATTERY, …). */
    val latestByType: StateFlow<Map<String, WatchReading>> = _latestByType.asStateFlow()

    private val _availableTrackers = MutableStateFlow<List<String>>(emptyList())
    /** Tracker types the connected watch reports as supported. */
    val availableTrackers: StateFlow<List<String>> = _availableTrackers.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    /**
     * Per-sample EDA stream (carries the watch-stamped timestamp + µS value), used by the recording
     * layer. Hot, no replay; DROP_OLDEST so a slow collector can never block the binder thread that
     * calls [onReading]. Apply [correctedTimestamp] to each reading's timestamp before storage.
     */
    private val _edaSampleFlow = MutableSharedFlow<WatchReading>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val edaSampleFlow: SharedFlow<WatchReading> = _edaSampleFlow.asSharedFlow()

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

    /** Map a watch-stamped timestamp onto the phone clock using the captured offset. */
    fun correctedTimestamp(watchTimestampMs: Long): Long = watchTimestampMs + clockOffsetMs

    /**
     * Snapshot of the low-battery alert tier from the **last-known** battery level, read on demand
     * (e.g. when the Home screen resumes between sessions). Deliberately ignores the current
     * connection state: the check happens right at the start-a-new-session decision point, so the
     * last value the watch reported is the truth we care about even if the watch was just set down.
     * `_batteryLevel` is never cleared on disconnect, so this stays meaningful until a fresh reading
     * arrives (or the process is recreated). CRITICAL is checked first so it always wins.
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
        markActivity(arrivalMs)
        maybeCaptureClockOffset(reading, arrivalMs)
        _latestByType.value = _latestByType.value.toMutableMap().apply { put(reading.type, reading) }
        when (reading.type) {
            "BATTERY" -> _batteryLevel.value = reading.value.toInt()
            "EDA" -> _edaSampleFlow.tryEmit(reading)
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
        // Force a fresh clock offset on the next connection.
        offsetCaptured = false
    }

    /** Record a message arrival and ensure the inactivity watchdog is running. */
    private fun markActivity(arrivalMs: Long) {
        lastMessageMs = arrivalMs
        _connectionState.value = ConnectionState.CONNECTED
        ensureWatchdog()
    }

    @Synchronized
    private fun ensureWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (System.currentTimeMillis() - lastMessageMs > INACTIVITY_TIMEOUT_MS) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    // A real drop (6 s silence) — recompute the offset on the next reconnect.
                    offsetCaptured = false
                }
            }
        }
    }
}
