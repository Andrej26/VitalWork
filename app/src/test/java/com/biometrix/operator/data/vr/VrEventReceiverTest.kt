package com.biometrix.operator.data.vr

import com.biometrix.operator.data.db.FakeScenarioDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.ScenarioCode
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.repository.ScenarioRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        scenarioRepository = ScenarioRepository(scenarioDao, FakeSensorSampleDao())
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
    fun stimulusEvent_withActiveScenario_persistsTimestampAndAccepts() = runTest {
        val receiver = newReceiver()
        val id = seedActiveScenario(receiver)

        val result = receiver.submit(
            VrEvent.StimulusEvent(1L, ScenarioCode.FALLING_PALLET, receivedAtMs = 42L)
        )

        assertTrue(result is VrEventResult.Accepted)
        assertEquals(42L, scenarioDao.getScenarioById(id)?.eventTimestampMs)
    }

    @Test
    fun reaction_withActiveScenario_persistsReactionTimestamp() = runTest {
        val receiver = newReceiver()
        val id = seedActiveScenario(receiver)

        receiver.submit(VrEvent.Reaction(1L, ScenarioCode.FALLING_PALLET, receivedAtMs = 99L))

        assertEquals(99L, scenarioDao.getScenarioById(id)?.reactionTimestampMs)
    }

    @Test
    fun event_withNoActiveScenario_isRejected() = runTest {
        val receiver = newReceiver()

        val result = receiver.submit(
            VrEvent.StimulusEvent(1L, ScenarioCode.FALLING_PALLET, receivedAtMs = 1L)
        )

        assertTrue(result is VrEventResult.Rejected)
    }

    @Test
    fun secondEvent_isIgnored_firstWriteWins() = runTest {
        val receiver = newReceiver()
        val id = seedActiveScenario(receiver)

        receiver.submit(VrEvent.StimulusEvent(1L, ScenarioCode.FALLING_PALLET, receivedAtMs = 10L))
        receiver.submit(VrEvent.StimulusEvent(1L, ScenarioCode.FALLING_PALLET, receivedAtMs = 20L))

        // The original timestamp is preserved; the retry did not clobber it.
        assertEquals(10L, scenarioDao.getScenarioById(id)?.eventTimestampMs)
    }

    @Test
    fun reactionWithinGraceWindow_afterStop_stillLands() = runTest {
        val receiver = newReceiver()
        val id = seedActiveScenario(receiver)

        receiver.clearActiveScenario() // scenario "ended"
        now += 1_000L // 1 s later, within the 3 s grace window

        val result = receiver.submit(
            VrEvent.Reaction(1L, ScenarioCode.FALLING_PALLET, receivedAtMs = 555L)
        )

        assertTrue(result is VrEventResult.Accepted)
        assertEquals(555L, scenarioDao.getScenarioById(id)?.reactionTimestampMs)
    }

    @Test
    fun reactionAfterGraceWindow_isRejected() = runTest {
        val receiver = newReceiver()
        val id = seedActiveScenario(receiver)

        receiver.clearActiveScenario()
        now += 5_000L // past the 3 s grace window

        val result = receiver.submit(
            VrEvent.Reaction(1L, ScenarioCode.FALLING_PALLET, receivedAtMs = 1L)
        )

        assertTrue(result is VrEventResult.Rejected)
        assertNull(scenarioDao.getScenarioById(id)?.reactionTimestampMs)
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

        receiver.submit(VrEvent.StimulusEvent(1L, ScenarioCode.FALLING_PALLET, receivedAtMs = 1L))
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, receiver.connectionState.value)

        // Advance the watchdog poll loop past the inactivity timeout without bumping `now`'s
        // relationship to lastMessageMs: lastMessageMs was set to `now`; push `now` forward so the
        // watchdog sees a stale lastMessageMs.
        now += 31_000L
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, receiver.connectionState.value)
    }
}
