package com.biometrix.operator.presentation.screens.sessions

import androidx.lifecycle.SavedStateHandle
import com.biometrix.operator.data.db.FakeScenarioDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.FakeSessionDao
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.prefs.FakeSettingsRepository
import com.biometrix.operator.data.recording.FakeScenarioRecordingRepository
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.sensor.watch.WatchSensorReceiver
import com.biometrix.operator.data.repository.ScenarioRepository
import com.biometrix.operator.data.repository.SessionRepository
import com.biometrix.operator.data.time.TimeProvider
import com.biometrix.operator.data.sensor.FakeSensorDevice
import com.biometrix.operator.data.sensor.ble.FakeBleManager
import com.biometrix.operator.data.db.ScenarioCode
import com.biometrix.operator.data.system.FakeLocationChecker
import com.biometrix.operator.data.system.FakeSystemReadinessChecker
import com.biometrix.operator.data.vr.VrEvent
import com.biometrix.operator.data.vr.VrEventReceiver
import com.biometrix.operator.data.vr.VrLinkLog
import com.biometrix.operator.presentation.components.BleDialogState
import com.biometrix.operator.presentation.screens.sessions.components.NotesSaveStatus
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

    private lateinit var vrEventReceiver: VrEventReceiver
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
        vrEventReceiver = VrEventReceiver(scenarioRepository, VrLinkLog())
        watchReceiver = WatchSensorReceiver(TimeProvider.system())
        fakeWatchCommandSender = FakeWatchCommandSender()

        connectionRepository = ConnectionRepository(
            vrEventReceiver = vrEventReceiver,
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
            sessionCode = "BMX-260413-100000",
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
            vrEventReceiver = vrEventReceiver,
            locationChecker = fakeLocationChecker,
            readinessChecker = FakeSystemReadinessChecker(),
            watchCommandSender = fakeWatchCommandSender,
            savedStateHandle = savedState
        )
    }

    /** Records FLUSH / FLUSH_ACK calls (and their order, via [eventLog]) for the watch handshake tests. */
    private class FakeWatchCommandSender :
        com.biometrix.operator.data.sensor.watch.WatchCommandSender {
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
            com.biometrix.operator.data.sensor.watch.model.WatchReading(
                type = type, value = value, accuracy = 0, timestampMs = System.currentTimeMillis()
            )
        )
    }

    private suspend fun emitScenarioStart(code: ScenarioCode = ScenarioCode.FALLING_PALLET) {
        vrEventReceiver.submit(VrEvent.ScenarioStart(code, System.currentTimeMillis()))
    }

    private suspend fun emitScenarioStop(code: ScenarioCode = ScenarioCode.FALLING_PALLET) {
        vrEventReceiver.submit(VrEvent.ScenarioStop(code, System.currentTimeMillis()))
    }

    @Test
    fun init_loadsSessionFromRepository() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val seeded = seedActiveSession()

        val vm = createVm()
        advanceUntilIdle()

        assertEquals(seeded.id, vm.session.value?.id)
        assertEquals("BMX-260413-100000", vm.session.value?.sessionCode)
    }

    @Test
    fun init_unknownSessionId_returnsNullStateGracefully() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val vm = createVm(savedSessionId = 999L)
        advanceUntilIdle()

        assertNull(vm.session.value)
    }

    @Test
    fun vrScenarioStart_whenSensorConnected_createsRealScenarioAndStarts() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()
        fakeBleManager.connectionState.value = ConnectionState.CONNECTED

        val vm = createVm()
        advanceUntilIdle()

        emitScenarioStart(ScenarioCode.BLIND_CORNER)
        advanceUntilIdle()

        assertEquals(1, fakeScenarioRecordingRepo.startRecordingCallCount)
        assertEquals(1, fakeScenarioDao.scenarios.size)
        // The real scenario code is recorded (no placeholder).
        assertEquals(ScenarioCode.BLIND_CORNER, fakeScenarioDao.scenarios[0].scenarioCode)
        assertTrue(vm.vrTriggeredRecording.value)
    }

    @Test
    fun vrScenarioStart_whenNoSensorConnected_doesNotStartRecording() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()

        val vm = createVm()
        advanceUntilIdle()

        emitScenarioStart()
        advanceUntilIdle()

        assertEquals(0, fakeScenarioRecordingRepo.startRecordingCallCount)
        assertFalse(vm.vrTriggeredRecording.value)
    }

    @Test
    fun vrScenarioStop_whileRecording_stopsRecording() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()
        fakeBleManager.connectionState.value = ConnectionState.CONNECTED

        val vm = createVm()
        advanceUntilIdle()

        emitScenarioStart()
        advanceUntilIdle()
        assertEquals(DataRecordingState.RECORDING, fakeScenarioRecordingRepo.recordingState.value)

        emitScenarioStop()
        advanceUntilIdle()

        assertEquals(1, fakeScenarioRecordingRepo.stopRecordingCallCount)
        assertFalse(vm.vrTriggeredRecording.value)
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
    fun updateNotes_debounces500ms_thenSavesToDb() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        seedActiveSession()

        val vm = createVm()
        advanceUntilIdle()
        vm.setupNotesAutoSave()

        vm.updateNotes("hello")
        assertEquals(NotesSaveStatus.Saving, vm.notesSaveStatus.value)

        advanceTimeBy(400)
        runCurrent()
        assertEquals("", fakeSessionDao.sessions[0].notes)

        advanceTimeBy(200)
        runCurrent()
        assertEquals("hello", fakeSessionDao.sessions[0].notes)
        assertEquals(NotesSaveStatus.Saved, vm.notesSaveStatus.value)
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
