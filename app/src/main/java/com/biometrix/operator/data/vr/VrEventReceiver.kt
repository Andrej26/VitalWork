package com.biometrix.operator.data.vr

import com.biometrix.operator.data.db.ScenarioCode
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.repository.ScenarioRepository
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
 * Singleton sink for VR events arriving as HTTP POSTs (parsed by [com.biometrix.operator.data.vr.VrHttpServer]).
 *
 * Mirrors [com.biometrix.operator.data.sensor.watch.WatchSensorReceiver]: it exposes the parsed
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
    dispatcher: CoroutineDispatcher
) {
    /** Production constructor used by Hilt. Tests use the primary constructor to inject a fake clock/dispatcher. */
    @Inject
    constructor(scenarioRepository: ScenarioRepository) : this(
        scenarioRepository,
        clock = { System.currentTimeMillis() },
        dispatcher = Dispatchers.Default
    )


    private companion object {
        // The Quest sends no periodic traffic, only discrete events, so the timeout is generous:
        // a session can sit between scenarios for a while. This is a coarse "is VR alive" hint.
        const val INACTIVITY_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 1_000L
        // A reaction/event retry can land just after stop; keep the ended scenario addressable
        // briefly so a late, in-order measurement is saved instead of 409'd and lost.
        const val GRACE_WINDOW_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _events = MutableSharedFlow<VrEvent>(extraBufferCapacity = 16)
    /** Parsed VR events. Lifecycle (start/stop) is consumed by the ViewModel; replay = 0. */
    val events: SharedFlow<VrEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile private var lastMessageMs: Long = 0L
    private var watchdogJob: Job? = null

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
     * Validate and process a VR event. For `event`/`reaction` the timestamp DB write is awaited
     * before returning [VrEventResult.Accepted] (ack-after-write). Lifecycle events are emitted to
     * [events] for the VM to act on; they ack on accept.
     */
    suspend fun submit(event: VrEvent): VrEventResult {
        markActivity()
        return when (event) {
            is VrEvent.ScenarioStart -> {
                // Gating + row creation happen in the VM (it owns session state). Just forward.
                _events.emit(event)
                VrEventResult.Accepted()
            }
            is VrEvent.ScenarioStop -> {
                _events.emit(event)
                VrEventResult.Accepted()
            }
            is VrEvent.StimulusEvent -> writeTimestamp(event.code, event.receivedAtMs, isReaction = false)
            is VrEvent.Reaction -> writeTimestamp(event.code, event.receivedAtMs, isReaction = true)
        }
    }

    private suspend fun writeTimestamp(
        code: ScenarioCode,
        timestampMs: Long,
        isReaction: Boolean
    ): VrEventResult {
        val scenarioId = targetFor(code)
            ?: return VrEventResult.Rejected("no_active_scenario")

        // First-write-wins: don't let a delayed retry clobber an already-recorded timestamp.
        val scenario = scenarioRepository.getScenarioById(scenarioId)
            ?: return VrEventResult.Rejected("scenario_not_found")
        val already = if (isReaction) scenario.reactionTimestampMs else scenario.eventTimestampMs
        if (already != null) {
            return VrEventResult.Accepted(already) // idempotent: report the stored value
        }

        if (isReaction) {
            scenarioRepository.setReactionTimestamp(scenarioId, timestampMs)
        } else {
            scenarioRepository.setEventTimestamp(scenarioId, timestampMs)
        }
        return VrEventResult.Accepted(timestampMs)
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
}
