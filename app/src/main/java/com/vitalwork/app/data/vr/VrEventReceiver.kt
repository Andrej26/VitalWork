package com.vitalwork.app.data.vr

import com.vitalwork.app.data.db.ScenarioCode
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.repository.ScenarioRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * Singleton sink for VR events arriving as HTTP POSTs (parsed by [com.vitalwork.app.data.vr.VrHttpServer]).
 *
 * Mirrors [com.vitalwork.app.data.sensor.watch.WatchSensorReceiver]: it exposes the parsed
 * events as a flow and **infers** connection state from a time-since-last-event watchdog (the Quest
 * sends no heartbeat — liveness is just "did we hear from it recently").
 *
 * Ownership split (see plan): the **ViewModel** owns scenario lifecycle + gating — it observes
 * [events] and, on [VrEvent.ScenarioStart] (when a session is ACTIVE and a sensor is connected),
 * creates the scenario, starts recording, and calls [setActiveScenario]; on [VrEvent.ScenarioStop]
 * it stops recording, ends the scenario, and calls [clearActiveScenario]. The **receiver** owns the
 * synchronous accept/reject decision and — for `event`/`reaction` — the timestamp DB write itself,
 * so the HTTP route can return 200 only *after* the measurement is persisted (ack-after-write).
 */
@Singleton
class VrEventReceiver(
    private val scenarioRepository: ScenarioRepository,
    private val clock: () -> Long,
    dispatcher: CoroutineDispatcher,
    // Default lets the unit test construct this without a log; Hilt always injects the singleton.
    private val linkLog: VrLinkLog = VrLinkLog()
) {
    /** Production constructor used by Hilt. Tests use the primary constructor to inject a fake clock/dispatcher. */
    @Inject
    constructor(scenarioRepository: ScenarioRepository, linkLog: VrLinkLog) : this(
        scenarioRepository,
        clock = { System.currentTimeMillis() },
        dispatcher = Dispatchers.Default,
        linkLog = linkLog
    )


    private companion object {
        // The Quest sends no periodic *events*, only discrete ones, so this timeout is generous:
        // a session can sit between scenarios for a while. This is a coarse "any activity?" hint
        // for the internal event log only — it must NOT gate the bond (see heartbeat liveness below).
        const val INACTIVITY_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 1_000L
        // Heartbeat liveness: the bonded Quest POSTs /vr/heartbeat ~every 5 s. Declare it lost after
        // ~10 s (2 missed). This — not event-inactivity — is the real "is the Quest alive" signal,
        // so a 10-minute quiet scenario stays connected as long as heartbeats keep arriving.
        const val HEARTBEAT_TIMEOUT_MS = 10_000L
        // A reaction/event retry can land just after stop; keep the ended scenario addressable
        // briefly so a late, in-order measurement is saved instead of 409'd and lost.
        const val GRACE_WINDOW_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _events = MutableSharedFlow<VrEvent>(extraBufferCapacity = 16)
    /** Parsed VR events. Lifecycle (start/stop) is consumed by the ViewModel; replay = 0. */
    val events: SharedFlow<VrEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    /** Coarse event-activity state (internal log/badge only). Does NOT reflect bond liveness. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _heartbeatState = MutableStateFlow(ConnectionState.DISCONNECTED)
    /**
     * Heartbeat-driven liveness of the bonded Quest — the signal the app-wide VR indicator should
     * use. CONNECTED while heartbeats arrive; RECONNECTING (amber) after [HEARTBEAT_TIMEOUT_MS] of
     * silence — the bond is kept and the Quest auto-reconnects when it wakes, so this is a pause, not
     * a loss. Only a deliberate Stop ([onLinkStopped]) drops it to DISCONNECTED (gray).
     */
    val heartbeatState: StateFlow<ConnectionState> = _heartbeatState.asStateFlow()

    private val _heartbeatLost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted once each time heartbeats stop after having been alive; the service re-arms pairing. */
    val heartbeatLost: SharedFlow<Unit> = _heartbeatLost.asSharedFlow()

    @Volatile private var lastMessageMs: Long = 0L
    private var watchdogJob: Job? = null

    @Volatile private var lastHeartbeatMs: Long = 0L
    private var heartbeatWatchdogJob: Job? = null

    // Active-scenario mirror, written by the VM (single writer). Guarded by `this`.
    private var activeSessionId: Long? = null
    private var activeScenarioDbId: Long? = null
    private var activeCode: ScenarioCode? = null

    // Grace-window snapshot of the just-ended scenario (Phase 1 #5).
    private var graceScenarioDbId: Long? = null
    private var graceCode: ScenarioCode? = null
    private var graceUntilMs: Long = 0L

    // ── VM-owned active-scenario mirror ──────────────────────────────────────

    @Synchronized
    fun setActiveScenario(sessionId: Long, scenarioDbId: Long, code: ScenarioCode) {
        activeSessionId = sessionId
        activeScenarioDbId = scenarioDbId
        activeCode = code
        // A fresh start supersedes any lingering grace window.
        graceScenarioDbId = null
        graceCode = null
    }

    @Synchronized
    fun clearActiveScenario() {
        // No-op if already cleared — a duplicate /stop POST re-emits this event, and a second
        // call must not wipe the grace window set by the first.
        if (activeScenarioDbId == null) return
        // Keep the just-ended scenario addressable for a short grace period for late reactions.
        graceScenarioDbId = activeScenarioDbId
        graceCode = activeCode
        graceUntilMs = clock() + GRACE_WINDOW_MS
        activeScenarioDbId = null
        activeCode = null
        // activeSessionId is left for the VM to manage on session change.
    }

    /** Snapshot of the scenario an event/reaction should target, honoring the grace window. */
    @Synchronized
    private fun targetFor(code: ScenarioCode): Long? {
        val active = activeScenarioDbId
        if (active != null && activeCode == code) return active
        val grace = graceScenarioDbId
        if (grace != null && graceCode == code && clock() <= graceUntilMs) return grace
        return null
    }

    // ── HTTP route entry point ───────────────────────────────────────────────

    /**
     * Validate and process a VR event. For `stop`, any provided timestamps are persisted before
     * returning [VrEventResult.Accepted] (ack-after-write). Lifecycle events are emitted to
     * [events] for the VM to act on; they ack on accept.
     */
    suspend fun submit(event: VrEvent): VrEventResult {
        markActivity()
        return when (event) {
            is VrEvent.ScenarioStart -> {
                // Gating + row creation happen in the VM (it owns session state). Just forward.
                linkLog.add(VrLinkLog.Level.INFO, "Scenario start: ${event.code.officialCode}")
                _events.emit(event)
                VrEventResult.Accepted()
            }
            is VrEvent.ScenarioStop -> {
                // Resolve the target and persist timestamps BEFORE emitting — at this point
                // activeScenarioDbId is still set (the VM hasn't run clearActiveScenario() yet),
                // so this resolves deterministically via the active path, not the grace window.
                val scenarioId = targetFor(event.code)
                if (scenarioId == null) {
                    linkLog.add(
                        VrLinkLog.Level.WARNING,
                        "Scenario stop ignored: no active scenario for ${event.code.officialCode}"
                    )
                    return VrEventResult.Rejected("no_active_scenario")
                }

                writeTimestampsIfPresent(scenarioId, event)

                linkLog.add(
                    VrLinkLog.Level.INFO,
                    "Scenario stop: ${event.code.officialCode} " +
                        "(event=${event.eventTimestampMs ?: "—"}, reaction=${event.reactionTimestampMs ?: "—"})"
                )
                _events.emit(event)
                VrEventResult.Accepted()
            }
        }
    }

    /**
     * First-write-wins persistence of [VrEvent.ScenarioStop]'s optional timestamps: each is only
     * written if provided and not already recorded (so a retry doesn't clobber the original).
     */
    private suspend fun writeTimestampsIfPresent(scenarioId: Long, event: VrEvent.ScenarioStop) {
        if (event.eventTimestampMs == null && event.reactionTimestampMs == null) return

        val scenario = scenarioRepository.getScenarioById(scenarioId) ?: return

        if (event.eventTimestampMs != null && scenario.eventTimestampMs == null) {
            scenarioRepository.setEventTimestamp(scenarioId, event.eventTimestampMs)
        }
        if (event.reactionTimestampMs != null && scenario.reactionTimestampMs == null) {
            scenarioRepository.setReactionTimestamp(scenarioId, event.reactionTimestampMs)
        }
    }

    // ── Liveness watchdog (mirrors WatchSensorReceiver) ──────────────────────

    private fun markActivity() {
        lastMessageMs = clock()
        _connectionState.value = ConnectionState.CONNECTED
        ensureWatchdog()
    }

    @Synchronized
    private fun ensureWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (clock() - lastMessageMs > INACTIVITY_TIMEOUT_MS) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }
    }

    // ── Heartbeat liveness (independent of the event watchdog) ────────────────

    /**
     * Record a heartbeat from the bonded Quest. Deliberately does NOT touch [markActivity] / the
     * event watchdog — otherwise a single heartbeat would pin the event badge to CONNECTED forever.
     */
    fun markHeartbeat() {
        lastHeartbeatMs = clock()
        // Log the connect transition distinctly (SUCCESS) from the steady pulse (INFO), so the
        // operator can both confirm the link is alive beat-by-beat and spot when it (re)connected.
        if (_heartbeatState.value != ConnectionState.CONNECTED) {
            linkLog.add(VrLinkLog.Level.SUCCESS, "VR connected (heartbeat received)")
        } else {
            linkLog.add(VrLinkLog.Level.INFO, "Heartbeat")
        }
        _heartbeatState.value = ConnectionState.CONNECTED
        ensureHeartbeatWatchdog()
    }

    @Synchronized
    private fun ensureHeartbeatWatchdog() {
        if (heartbeatWatchdogJob?.isActive == true) return
        heartbeatWatchdogJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (_heartbeatState.value == ConnectionState.CONNECTED &&
                    clock() - lastHeartbeatMs > HEARTBEAT_TIMEOUT_MS
                ) {
                    // Was alive, now silent → flip the UI to RECONNECTING (amber), not DISCONNECTED
                    // (gray). The bond is deliberately KEPT (a quiet headset is usually just asleep),
                    // so this is a pause, not a loss — the Quest auto-reconnects when heartbeats
                    // resume (markHeartbeat → CONNECTED). Only the operator's Stop drops it to gray
                    // DISCONNECTED ([onLinkStopped]). heartbeatLost is still emitted for any
                    // interested observer; nothing currently drops the bond on it.
                    _heartbeatState.value = ConnectionState.RECONNECTING
                    linkLog.add(
                        VrLinkLog.Level.WARNING,
                        "No heartbeat for >${HEARTBEAT_TIMEOUT_MS / 1000}s — headset asleep? " +
                            "Bond kept; will auto-reconnect when it wakes."
                    )
                    _heartbeatLost.tryEmit(Unit)
                }
            }
        }
    }

    /**
     * The operator stopped the VR link (VR Control "Stop"). Reset liveness to DISCONNECTED and
     * cancel the watchdogs **without** going through the heartbeat-loss path — so deliberately
     * ending the link doesn't fire a false "VR connection lost" warning (or [heartbeatLost] re-arm)
     * when the headset's heartbeats then cease. A later [markHeartbeat] cleanly revives everything.
     */
    @Synchronized
    fun onLinkStopped() {
        heartbeatWatchdogJob?.cancel()
        heartbeatWatchdogJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        _heartbeatState.value = ConnectionState.DISCONNECTED
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Cancel the internal watchdog coroutines. Lets tests (whose dispatcher shares the runTest
     * scheduler) terminate the otherwise-infinite poll loops so the scheduler can go idle.
     */
    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        heartbeatWatchdogJob?.cancel()
        heartbeatWatchdogJob = null
    }
}
