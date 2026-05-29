package com.biometrix.operator.presentation.screens.sessions

import androidx.lifecycle.SavedStateHandle
import com.biometrix.operator.data.db.FakeScenarioDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.FakeSessionDao
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.recording.FakeScenarioRecordingRepository
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.repository.ScenarioRepository
import com.biometrix.operator.data.repository.SessionRepository
import com.biometrix.operator.data.sensor.FakeSensorDevice
import com.biometrix.operator.data.sensor.ble.FakeBleManager
import com.biometrix.operator.data.system.FakeLocationChecker
import com.biometrix.operator.data.system.FakeSystemReadinessChecker
import com.biometrix.operator.data.vr.FakeVRConnectionManager
import com.biometrix.operator.data.vr.FakeVrDeviceDiscovery
import com.biometrix.operator.data.vr.model.ServerMessage
import com.biometrix.operator.data.vr.model.WebSocketMessage
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

    private lateinit var fakeVrClient: FakeVRConnectionManager
    private lateinit var fakeDiscovery: FakeVrDeviceDiscovery
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

    private val sessionId = 1L

    @Before
    fun setUp() {
        fakeVrClient = FakeVRConnectionManager()
        fakeDiscovery = FakeVrDeviceDiscovery()
        fakeBleManager = FakeBleManager()
        fakeRespiration = FakeSensorDevice()
        fakeSessionDao = FakeSessionDao()
        fakeScenarioDao = FakeScenarioDao()
        fakeSensorSampleDao = FakeSensorSampleDao()
        fakeScenarioRecordingRepo = FakeScenarioRecordingRepository()
        fakeLocationChecker = FakeLocationChecker(locationEnabled = true)
        lanAvailableFlow = MutableStateFlow(true)

        connectionRepository = ConnectionRepository(
            vrWebSocketClient = fakeVrClient,
            bleManager = fakeBleManager,
            respirationDevice = fakeRespiration,
            lanAvailableFlow = lanAvailableFlow
        )
        scenarioRepository = ScenarioRepository(fakeScenarioDao, fakeSensorSampleDao)
        sessionRepository = SessionRepository(fakeSessionDao, fakeScenarioDao, fakeSensorSampleDao)
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
            vrWebSocketClient = fakeVrClient,
            mdnsDiscovery = fakeDiscovery,
            locationChecker = fakeLocationChecker,
            readinessChecker = FakeSystemReadinessChecker(),
            savedStateHandle = savedState
        )
    }

    private fun vrEvent(name: String, value: Int? = null): WebSocketMessage.Event =
        WebSocketMessage.Event(ServerMessage(type = "event", success = true, msg = name, value = value))

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
    fun vrStartEvent_legacyPath_whenSensorConnected_createsPlaceholderScenarioAndStarts() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()
        fakeBleManager.connectionState.value = ConnectionState.CONNECTED

        val vm = createVm()
        advanceUntilIdle()

        fakeVrClient.messages.emit(vrEvent("start_recording"))
        advanceUntilIdle()

        assertEquals(1, fakeScenarioRecordingRepo.startRecordingCallCount)
        assertEquals(1, fakeScenarioDao.scenarios.size)
        assertTrue(vm.vrTriggeredRecording.value)
    }

    @Test
    fun vrStartEvent_whenNoSensorConnected_doesNotStartRecording() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()

        val vm = createVm()
        advanceUntilIdle()

        fakeVrClient.messages.emit(vrEvent("start_recording"))
        advanceUntilIdle()

        assertEquals(0, fakeScenarioRecordingRepo.startRecordingCallCount)
        assertFalse(vm.vrTriggeredRecording.value)
    }

    @Test
    fun vrStopEvent_whileRecording_stopsRecordingAndDeactivatesScene() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()
        fakeBleManager.connectionState.value = ConnectionState.CONNECTED

        val vm = createVm()
        advanceUntilIdle()

        fakeVrClient.messages.emit(vrEvent("start_recording"))
        advanceUntilIdle()
        assertEquals(DataRecordingState.RECORDING, fakeScenarioRecordingRepo.recordingState.value)

        connectionRepository.setStressChamberSceneActive(true)

        fakeVrClient.messages.emit(vrEvent("stop_recording"))
        advanceUntilIdle()

        assertEquals(1, fakeScenarioRecordingRepo.stopRecordingCallCount)
        assertFalse(vm.vrTriggeredRecording.value)
        assertFalse(connectionRepository.isStressChamberSceneActive.value)
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
    fun endSessionAndSave_stopsRecording_thenCompletesSession() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveSession()

        val vm = createVm()
        advanceUntilIdle()

        fakeScenarioRecordingRepo.recordingState.value = DataRecordingState.RECORDING

        vm.endSessionAndSave()
        advanceUntilIdle()

        assertEquals(1, fakeScenarioRecordingRepo.stopRecordingCallCount)
        assertEquals(SessionStatus.COMPLETED, fakeSessionDao.sessions[0].status)
        assertTrue(vm.endSessionResult.value is EndSessionResult.Success)
        assertEquals(1, fakeVrClient.suppressAutoReconnectCallCount)
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
