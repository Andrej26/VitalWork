package com.biometrix.operator.data.sensor.watch

import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.sensor.watch.model.WatchReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        // Data arrives at a steady ~1 Hz (flush loop), so ~4 s of silence reliably means a real drop,
        // not a hiccup. A normal Stop is signalled explicitly via onStop() for instant DISCONNECTED;
        // this watchdog is the safety net for the watch dying / going out of range (no goodbye sent).
        const val INACTIVITY_TIMEOUT_MS = 4_000L
        const val POLL_INTERVAL_MS = 1_000L
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
        markActivity()
        _availableTrackers.value = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun onReading(reading: WatchReading) {
        markActivity()
        _latestByType.value = _latestByType.value.toMutableMap().apply { put(reading.type, reading) }
        if (reading.type == "BATTERY") {
            _batteryLevel.value = reading.value.toInt()
        }
    }

    /** Watch signalled it stopped tracking → reflect DISCONNECTED immediately, no watchdog wait. */
    @Synchronized
    fun onStop() {
        watchdogJob?.cancel(); watchdogJob = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** Record a message arrival and ensure the inactivity watchdog is running. */
    private fun markActivity() {
        lastMessageMs = System.currentTimeMillis()
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
                }
            }
        }
    }
}
