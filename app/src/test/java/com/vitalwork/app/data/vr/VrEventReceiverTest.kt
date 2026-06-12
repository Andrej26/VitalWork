package com.vitalwork.app.data.vr

import com.vitalwork.app.data.db.FakeScenarioDao
import com.vitalwork.app.data.db.FakeSensorSampleDao
import com.vitalwork.app.data.db.ScenarioCode
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.repository.ScenarioRepository
import com.vitalwork.app.data.time.TimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VrEventReceiverTest {

    private lateinit var scenarioDao: FakeScenarioDao
    private lateinit var scenarioRepository: ScenarioRepository
    private var now = 1_000_000L

    @Before
    fun setUp() {
        scenarioDao = FakeScenarioDao()
        scenarioRepository = ScenarioRepository(scenarioDao, FakeSensorSampleDao(), TimeProvider.system())
        now = 1_000_000L
    }

    private fun newReceiver() = VrEventReceiver(
        scenarioRepository = scenarioRepository,
        clock = { now },
        dispatcher = StandardTestDispatcher()
    )

    private suspend fun seedActiveScenario(
        receiver: VrEventReceiver,
        code: ScenarioCode = ScenarioCode.FALLING_PALLET
    ): Long {
        val scenario = scenarioRepository.createScenario(sessionId = 1L, scenarioCode = code)
        receiver.setActiveScenario(sessionId = 1L, scenarioDbId = scenario.id, code = code)
        return scenario.id
    }

    @Test
    fun stop_withTimestamps_persistsBothAndAccepts() = runTest {
        val receiver = newReceiver()
        val id = seedActiveScenario(receiver)

        val result = receiver.submit(
            VrEvent.ScenarioStop(
                code = ScenarioCode.FALLING_PALLET,
                receivedAtMs = 1_000L,
                eventTimestampMs = 10L,
                reactionTimestampMs = 20L
            )
        )

        assertTrue(result is VrEventResult.Accepted)
        assertEquals(10L, scenarioDao.getScenarioById(id)?.eventTimestampMs)
        assertEquals(20L, scenarioDao.getScenarioById(id)?.reactionTimestampMs)

        receiver.stop()
    }

    @Test
    fun stop_withNullReaction_persistsEventOnly() = runTest {
        val receiver = newReceiver()
        val id = seedActiveScenario(receiver)

        val result = receiver.submit(
            VrEvent.ScenarioStop(
                code = ScenarioCode.FALLING_PALLET,
                receivedAtMs = 1_000L,
                eventTimestampMs = 10L,
                reactionTimestampMs = null
            )
        )

        assertTrue(result is VrEventResult.Accepted)
        assertEquals(10L, scenarioDao.getScenarioById(id)?.eventTimestampMs)
        assertNull(scenarioDao.getScenarioById(id)?.reactionTimestampMs)

        receiver.stop()
    }

    @Test
    fun stop_withNoActiveScenario_isRejected() = runTest {
        val receiver = newReceiver()

        val result = receiver.submit(
            VrEvent.ScenarioStop(
                code = ScenarioCode.FALLING_PALLET,
                receivedAtMs = 1_000L,
                eventTimestampMs = 10L,
                reactionTimestampMs = 20L
            )
        )

        assertTrue(result is VrEventResult.Rejected)
        assertEquals("no_active_scenario", (result as VrEventResult.Rejected).reason)

        receiver.stop()
    }

    @Test
    fun stop_retry_firstWriteWins() = runTest {
        val receiver = newReceiver()
        val id = seedActiveScenario(receiver)

        val first = receiver.submit(
            VrEvent.ScenarioStop(
                code = ScenarioCode.FALLING_PALLET,
                receivedAtMs = 1_000L,
                eventTimestampMs = 10L,
                reactionTimestampMs = 20L
            )
        )
        val second = receiver.submit(
            VrEvent.ScenarioStop(
                code = ScenarioCode.FALLING_PALLET,
                receivedAtMs = 1_001L,
                eventTimestampMs = 999L,
                reactionTimestampMs = 999L
            )
        )

        assertTrue(first is VrEventResult.Accepted)
        assertTrue(second is VrEventResult.Accepted)
        assertEquals(10L, scenarioDao.getScenarioById(id)?.eventTimestampMs)
        assertEquals(20L, scenarioDao.getScenarioById(id)?.reactionTimestampMs)

        receiver.stop()
    }

    @Test
    fun clearActiveScenario_calledTwice_doesNotResetGraceWindow() = runTest {
        val receiver = newReceiver()
        val id = seedActiveScenario(receiver)

        receiver.clearActiveScenario() // scenario "ended" — now in grace window
        now += 1_000L // still within the 3 s grace window

        // Simulate the ViewModel reacting to a duplicate ScenarioStop emit by calling
        // clearActiveScenario() a second time. Without the idempotency fix, this would null out
        // the grace window (graceScenarioDbId = activeScenarioDbId, which is already null), and
        // the ScenarioStop below would be rejected.
        receiver.clearActiveScenario()

        val result = receiver.submit(
            VrEvent.ScenarioStop(
                code = ScenarioCode.FALLING_PALLET,
                receivedAtMs = now,
                eventTimestampMs = 10L,
                reactionTimestampMs = 20L
            )
        )

        assertTrue(result is VrEventResult.Accepted)
        assertEquals(10L, scenarioDao.getScenarioById(id)?.eventTimestampMs)
        assertEquals(20L, scenarioDao.getScenarioById(id)?.reactionTimestampMs)

        receiver.stop()
    }

    @Test
    fun connectionState_becomesConnectedOnEvent_thenDisconnectsAfterTimeout() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val receiver = VrEventReceiver(
            scenarioRepository = scenarioRepository,
            clock = { now },
            dispatcher = dispatcher
        )
        seedActiveScenario(receiver)

        receiver.submit(
            VrEvent.ScenarioStop(ScenarioCode.FALLING_PALLET, receivedAtMs = 1L)
        )
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, receiver.connectionState.value)

        // Advance the watchdog poll loop past the inactivity timeout without bumping `now`'s
        // relationship to lastMessageMs: lastMessageMs was set to `now`; push `now` forward so the
        // watchdog sees a stale lastMessageMs.
        now += 31_000L
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, receiver.connectionState.value)

        receiver.stop() // cancel the watchdog so the shared test scheduler can drain
    }

    @Test
    fun heartbeat_doesNotFlipEventWatchdog() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val receiver = VrEventReceiver(
            scenarioRepository = scenarioRepository,
            clock = { now },
            dispatcher = dispatcher
        )

        receiver.markHeartbeat()
        runCurrent()

        // Heartbeat drives its own state but must NOT touch the event watchdog (regression guard
        // for the conflation bug: a single heartbeat used to pin the event badge CONNECTED forever).
        assertEquals(ConnectionState.CONNECTED, receiver.heartbeatState.value)
        assertEquals(ConnectionState.DISCONNECTED, receiver.connectionState.value)

        // Cancel the watchdog poll loop so runTest's end-of-body advanceUntilIdle() can drain the
        // shared test scheduler — an uncancelled `while(isActive){ delay() }` loop spins it forever.
        receiver.stop()
    }

    @Test
    fun onLinkStopped_resetsLivenessWithoutFiringLost() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val receiver = VrEventReceiver(
            scenarioRepository = scenarioRepository,
            clock = { now },
            dispatcher = dispatcher
        )

        // Record whether the heartbeat-loss signal (which the service uses to re-arm + which surfaces
        // the "connection lost" warning) ever fires after a deliberate stop. It must not.
        val lostEvents = mutableListOf<Unit>()
        val collectJob = launch { receiver.heartbeatLost.collect { lostEvents.add(it) } }

        receiver.markHeartbeat()
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, receiver.heartbeatState.value)

        // Operator taps Stop while connected.
        receiver.onLinkStopped()
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, receiver.heartbeatState.value)

        // Past the heartbeat timeout with no further beats: because the watchdog was cancelled by the
        // deliberate stop, no late "lost" is emitted (no false warning when the headset then quits).
        now += 11_000L
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue("onLinkStopped must not emit heartbeatLost", lostEvents.isEmpty())

        collectJob.cancel()
        receiver.stop()
    }

    @Test
    fun heartbeat_survivesLongEventQuietGap_thenReconnectingWhenHeartbeatsStop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val receiver = VrEventReceiver(
            scenarioRepository = scenarioRepository,
            clock = { now },
            dispatcher = dispatcher
        )

        receiver.markHeartbeat()
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, receiver.heartbeatState.value)

        // A long event-quiet gap (> the 30 s event timeout) with heartbeats still arriving keeps the
        // bond alive — this is the 10-minute-quiet-scenario case.
        now += 40_000L
        receiver.markHeartbeat()
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, receiver.heartbeatState.value)

        // Now stop heartbeats: after the ~10 s heartbeat timeout it flips to RECONNECTING (amber) —
        // NOT DISCONNECTED (gray). The bond is kept; only a deliberate Stop drops it to disconnected.
        now += 11_000L
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(ConnectionState.RECONNECTING, receiver.heartbeatState.value)

        // Heartbeats resume → it auto-reconnects back to CONNECTED (no re-pair needed).
        receiver.markHeartbeat()
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, receiver.heartbeatState.value)

        // Cancel the watchdog poll loop so runTest's end-of-body advanceUntilIdle() can drain the
        // shared test scheduler — an uncancelled `while(isActive){ delay() }` loop spins it forever.
        receiver.stop()
    }
}
