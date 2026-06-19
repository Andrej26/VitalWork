package com.vitalwork.app.presentation.screens.sessions

import androidx.lifecycle.SavedStateHandle
import com.vitalwork.app.data.db.FakeScenarioDao
import com.vitalwork.app.data.db.FakeSensorSampleDao
import com.vitalwork.app.data.db.FakeSessionDao
import com.vitalwork.app.data.db.SessionEntity
import com.vitalwork.app.data.db.SessionStatus
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.prefs.FakeSettingsRepository
import com.vitalwork.app.data.recording.FakeScenarioRecordingRepository
import com.vitalwork.app.data.recording.model.DataRecordingState
import com.vitalwork.app.data.repository.ConnectionRepository
import com.vitalwork.app.data.sensor.watch.WatchSensorReceiver
import com.vitalwork.app.data.repository.ScenarioRepository
import com.vitalwork.app.data.repository.SessionRepository
import com.vitalwork.app.data.time.TimeProvider
import com.vitalwork.app.data.sensor.FakeSensorDevice
import com.vitalwork.app.data.sensor.ble.FakeBleManager
import com.vitalwork.app.data.db.ScenarioCode
import com.vitalwork.app.data.system.FakeLocationChecker
import com.vitalwork.app.data.system.FakeSystemReadinessChecker
import com.vitalwork.app.presentation.components.BleDialogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionControlViewModelTest {

    private lateinit var fakeBleManager: FakeBleManager
    private lateinit var fakeRespiration: FakeSensorDevice
    private lateinit var fakeSessionDao: FakeSessionDao
    private lateinit var fakeScenarioDao: FakeScenarioDao
    private lateinit var fakeSensorSampleDao: FakeSensorSampleDao
    private lateinit var fakeScenarioRecordingRepo: FakeScenarioRecordingRepository
    private lateinit var fakeLocationChecker: FakeLocationChecker
    private lateinit var connectionRepository: ConnectionRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var scenarioRepository: ScenarioRepository
    private lateinit var lanAvailableFlow: MutableStateFlow<Boolean>
    private lateinit var watchReceiver: WatchSensorReceiver
    private lateinit var fakeWatchCommandSender: FakeWatchCommandSender

    private val sessionId = 1L

    @Before
    fun setUp() {
        fakeBleManager = FakeBleManager()
        fakeRespiration = FakeSensorDevice()
        fakeSessionDao = FakeSessionDao()
        fakeScenarioDao = FakeScenarioDao()
        fakeSensorSampleDao = FakeSensorSampleDao()
        fakeScenarioRecordingRepo = FakeScenarioRecordingRepository()
        fakeLocationChecker = FakeLocationChecker(locationEnabled = true)
        lanAvailableFlow = MutableStateFlow(true)
        scenarioRepository = ScenarioRepository(fakeScenarioDao, fakeSensorSampleDao, TimeProvider.system())
        watchReceiver = WatchSensorReceiver(TimeProvider.system())
        fakeWatchCommandSender = FakeWatchCommandSender()

        connectionRepository = ConnectionRepository(
            bleManager = fakeBleManager,
            respirationDevice = fakeRespiration,
            watchReceiver = watchReceiver,
            lanAvailableFlow = lanAvailableFlow
        )
        sessionRepository = SessionRepository(
            fakeSessionDao,
            fakeScenarioDao,
            fakeSensorSampleDao,
            FakeSettingsRepository("A"),
            TimeProvider.system()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedActiveSession(id: Long = sessionId): SessionEntity {
        val session = SessionEntity(
            id = id,
            participantId = 1L,
            sessionCode = "VW-260413-100000",
            startedAt = System.currentTimeMillis(),
            status = SessionStatus.ACTIVE
        )
        fakeSessionDao.sessions.add(session)
        return session
    }

    private fun createVm(savedSessionId: Long = sessionId): SessionControlViewModel {
        val savedState = SavedStateHandle(mapOf("sessionId" to savedSessionId))
        return SessionControlViewModel(
            connectionRepository = connectionRepository,
            sensorRecordingRepository = fakeScenarioRecordingRepo,
            sessionRepository = sessionRepository,
            scenarioRepository = scenarioRepository,
            locationChecker = fakeLocationChecker,
            readinessChecker = FakeSystemReadinessChecker(),
            watchCommandSender = fakeWatchCommandSender,
            savedStateHandle = savedState
        )
    }

    /** Records FLUSH / FLUSH_ACK calls (and their order, via [eventLog]) for the watch handshake tests. */
    private class FakeWatchCommandSender :
        com.vitalwork.app.data.sensor.watch.WatchCommandSender {
        var sendFlushCallCount = 0; private set
        var sendFlushAckCallCount = 0; private set
        var lastAckTimestampMs: Long? = null; private set
        var eventLog: MutableList<String>? = null

        override suspend fun sendStart(): Boolean = true
        override suspend fun sendStop(): Boolean = true
        override suspend fun sendFlush(): Boolean {
            sendFlushCallCount++
            eventLog?.add("flush")
            return true
        }
        override suspend fun sendFlushAck(throughTimestampMs: Long): Boolean {
            sendFlushAckCallCount++
            lastAckTimestampMs = throughTimestampMs
            eventLog?.add("ack")
            return true
        }
    }

    /** Feed a reading into the watch receiver — marks the link LIVE; a BATTERY reading also marks the
     *  watch "in use" (battery level non-null), which is the End-Session watch-flow trigger. */
    private fun feedWatchReading(type: String, value: Float) {
        watchReceiver.onReading(
            com.vitalwork.app.data.sensor.watch.model.WatchReading(
                type = type, value = value, accuracy = 0, timestampMs = System.currentTimeMillis()
            )
        )
    }

    @Test
    fun init_loadsSessionFromRepository() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val seeded = seedActiveSession()

        val vm = createVm()
        advanceUntilIdle()

        assertEquals(seeded.id, vm.session.value?.id)
        assertEquals("VW-260413-100000", vm.session.value?.sessionCode)
    }

    @Test
    fun init_unknownSessionId_returnsNullStateGracefully() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val vm = createVm(savedSessionId = 999L)
        advanceUntilIdle()

        assertNull(vm.session.value)
    }

    @Test
    fun manualStart_whenSensorConnected_createsScenarioAndStarts() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()
        fakeBleManager.connectionState.value = ConnectionState.CONNECTED

        val vm = createVm()
        advanceUntilIdle()

        vm.startManualRecording()
        advanceUntilIdle()

        assertEquals(1, fakeScenarioRecordingRepo.startRecordingCallCount)
        assertEquals(1, fakeScenarioDao.scenarios.size)
        assertEquals(ScenarioCode.FALLING_PALLET, fakeScenarioDao.scenarios[0].scenarioCode)
    }

    @Test
    fun manualStart_whenNoSensorConnected_doesNotStartRecording() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()

        val vm = createVm()
        advanceUntilIdle()

        vm.startManualRecording()
        advanceUntilIdle()

        assertEquals(0, fakeScenarioRecordingRepo.startRecordingCallCount)
    }

    @Test
    fun manualStop_whileRecording_stopsRecording() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()
        fakeBleManager.connectionState.value = ConnectionState.CONNECTED

        val vm = createVm()
        advanceUntilIdle()

        vm.startManualRecording()
        advanceUntilIdle()
        assertEquals(DataRecordingState.RECORDING, fakeScenarioRecordingRepo.recordingState.value)

        vm.stopManualRecording()
        advanceUntilIdle()

        assertEquals(1, fakeScenarioRecordingRepo.stopRecordingCallCount)
    }

    @Test
    fun bleScan_noDevicesAfter15s_flagsTimeout() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        seedActiveSession()

        val vm = createVm()
        advanceUntilIdle()

        vm.onHeartRateCardClick()
        (fakeBleManager.isScanning as MutableStateFlow<Boolean>).value = true
        runCurrent()

        assertFalse(vm.scanTimeoutReached.value)

        advanceTimeBy(15_001)
        runCurrent()

        assertTrue("Expected scan timeout after 15s", vm.scanTimeoutReached.value)
    }

    @Test
    fun endSession_noWatch_stopsRecording_finalizesWithoutFlush() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()

        val vm = createVm()
        advanceUntilIdle()

        fakeScenarioRecordingRepo.recordingState.value = DataRecordingState.RECORDING

        vm.endSessionAndSave()
        advanceUntilIdle()

        assertEquals(1, fakeScenarioRecordingRepo.stopRecordingCallCount)
        assertEquals(SessionStatus.COMPLETED, fakeSessionDao.sessions[0].status)
        assertTrue(vm.endSessionPhase.value is EndSessionPhase.Complete)
        // No watch in use → no FLUSH and no FLUSH_ACK.
        assertEquals(0, fakeWatchCommandSender.sendFlushCallCount)
        assertEquals(0, fakeWatchCommandSender.sendFlushAckCallCount)
    }

    @Test
    fun endSession_watchLive_sendsFlush_thenAcksOnlyAfterDrain() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()

        val vm = createVm()
        advanceUntilIdle()

        // Watch in use + LIVE → End Session skips the wake prompt and transfers straight away.
        feedWatchReading("BATTERY", 80f)
        advanceUntilIdle()

        val log = mutableListOf<String>()
        fakeWatchCommandSender.eventLog = log
        fakeScenarioRecordingRepo.eventLog = log

        // Unconfined dispatcher runs the end-session job eagerly to its transfer-wait suspension; do
        // NOT advance time here or the bounded transfer wait would elapse before the chunk lands.
        vm.endSessionAndSave()

        // The watch streams its store; a chunk completes the batch (max watch ts = 5000).
        watchReceiver.onFlushChunk(batchId = 1L, index = 0, count = 1, maxWatchTsInChunk = 5_000L)
        advanceUntilIdle()

        // FLUSH first, drain (persist) before FLUSH_ACK — never ack data that wasn't persisted.
        assertEquals(listOf("flush", "drain", "ack"), log)
        assertEquals(5_000L, fakeWatchCommandSender.lastAckTimestampMs)
        assertEquals(SessionStatus.COMPLETED, fakeSessionDao.sessions[0].status)
        assertTrue(vm.endSessionPhase.value is EndSessionPhase.Complete)
    }

    @Test
    fun endSession_watchLive_transferTimesOut_failsWithoutAck() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()

        val vm = createVm()
        advanceUntilIdle()

        feedWatchReading("BATTERY", 80f)
        advanceUntilIdle()

        vm.endSessionAndSave()
        advanceUntilIdle() // no flush ever completes → the bounded transfer wait elapses

        assertTrue(vm.endSessionPhase.value is EndSessionPhase.Failed)
        // Store untouched: no FLUSH_ACK, so the watch keeps its data for a later session.
        assertEquals(0, fakeWatchCommandSender.sendFlushAckCallCount)
        // Session not yet completed (operator can retry or end without watch data).
        assertEquals(SessionStatus.ACTIVE, fakeSessionDao.sessions[0].status)
    }

    @Test
    fun endWithoutWatchData_duringTransfer_finalizesWithoutAck() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()

        val vm = createVm()
        advanceUntilIdle()

        feedWatchReading("BATTERY", 80f)
        advanceUntilIdle()

        // Runs eagerly to the transfer-wait suspension (no time advanced yet).
        vm.endSessionAndSave()

        vm.endWithoutWatchData() // operator aborts the wait
        advanceUntilIdle()

        assertEquals(1, fakeScenarioRecordingRepo.drainWatchEdaCallCount)
        assertEquals(0, fakeWatchCommandSender.sendFlushAckCallCount)
        assertEquals(SessionStatus.COMPLETED, fakeSessionDao.sessions[0].status)
        assertTrue(vm.endSessionPhase.value is EndSessionPhase.Complete)
    }

    @Test
    fun discardSession_stopsRecordingAndDeletesSession() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()

        val vm = createVm()
        advanceUntilIdle()

        fakeScenarioRecordingRepo.recordingState.value = DataRecordingState.RECORDING

        vm.discardSession()
        advanceUntilIdle()

        assertEquals(1, fakeScenarioRecordingRepo.stopRecordingCallCount)
        assertTrue("Session should be deleted", fakeSessionDao.sessions.isEmpty())
    }

    @Test
    fun onHeartRateCardClick_locationDisabled_showsLocationDialog() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()
        fakeLocationChecker.locationEnabled = false

        val vm = createVm()
        advanceUntilIdle()

        vm.onHeartRateCardClick()

        assertEquals(BleDialogState.LocationServicesRequired, vm.bleDialogState.value)
        assertFalse(vm.showBleScanDialog.value)
    }
}
