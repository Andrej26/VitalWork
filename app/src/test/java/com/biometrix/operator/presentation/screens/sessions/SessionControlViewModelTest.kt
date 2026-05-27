package com.biometrix.operator.presentation.screens.sessions

import androidx.lifecycle.SavedStateHandle
import com.biometrix.operator.data.db.FakeRecordingDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.FakeSessionDao
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.recording.FakeSensorRecordingRepository
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.repository.SessionRepository
import com.biometrix.operator.data.sensor.FakeSensorDevice
import com.biometrix.operator.data.sensor.ble.FakeBleManager
import com.biometrix.operator.data.system.FakeLocationChecker
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
import org.junit.Assert.assertNotNull
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
    private lateinit var fakeRecordingDao: FakeRecordingDao
    private lateinit var fakeSensorSampleDao: FakeSensorSampleDao
    private lateinit var fakeSensorRecordingRepo: FakeSensorRecordingRepository
    private lateinit var fakeLocationChecker: FakeLocationChecker
    private lateinit var connectionRepository: ConnectionRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var lanAvailableFlow: MutableStateFlow<Boolean>

    private val sessionId = 1L

    @Before
    fun setUp() {
        fakeVrClient = FakeVRConnectionManager()
        fakeDiscovery = FakeVrDeviceDiscovery()
        fakeBleManager = FakeBleManager()
        fakeRespiration = FakeSensorDevice()
        fakeSessionDao = FakeSessionDao()
        fakeRecordingDao = FakeRecordingDao()
        fakeSensorSampleDao = FakeSensorSampleDao()
        fakeSensorRecordingRepo = FakeSensorRecordingRepository()
        fakeLocationChecker = FakeLocationChecker(locationEnabled = true)
        lanAvailableFlow = MutableStateFlow(true)

        connectionRepository = ConnectionRepository(
            vrWebSocketClient = fakeVrClient,
            bleManager = fakeBleManager,
            respirationDevice = fakeRespiration,
            lanAvailableFlow = lanAvailableFlow
        )
        recordingRepository = RecordingRepository(fakeRecordingDao, fakeSensorSampleDao)
        sessionRepository = SessionRepository(fakeSessionDao, fakeRecordingDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun seedactiveSession(id: Long = sessionId): SessionEntity {
        val test = SessionEntity(
            id = id,
            sessionNumber = "260413-100000",
            sessionIdentifier = "BMX-260413-100000",
            createdAt = System.currentTimeMillis(),
            status = SessionStatus.ACTIVE
        )
        fakeSessionDao.tests.add(test)
        return test
    }

    private fun createVm(savedsessionId: Long = sessionId): SessionControlViewModel {
        val savedState = SavedStateHandle(mapOf("testId" to savedsessionId))
        return SessionControlViewModel(
            connectionRepository = connectionRepository,
            sensorRecordingRepository = fakeSensorRecordingRepo,
            sessionRepository = sessionRepository,
            recordingRepository = recordingRepository,
            vrWebSocketClient = fakeVrClient,
            mdnsDiscovery = fakeDiscovery,
            locationChecker = fakeLocationChecker,
            savedStateHandle = savedState
        )
    }

    private fun vrEvent(name: String, value: Int? = null): WebSocketMessage.Event =
        WebSocketMessage.Event(ServerMessage(type = "event", success = true, msg = name, value = value))

    // ---- Group A: Initialization ----

    @Test
    fun init_loadsTestFromRepository() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val seeded = seedactiveSession()

        val vm = createVm()
        advanceUntilIdle()

        assertEquals(seeded.id, vm.test.value?.id)
        assertEquals("BMX-260413-100000", vm.test.value?.sessionIdentifier)
    }

    @Test
    fun init_unknownTestId_returnsNullStateGracefully() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val vm = createVm(savedsessionId = 999L)
        advanceUntilIdle()

        assertNull(vm.test.value)
    }

    // ---- Group B: VR biofeedback automation ----

    @Test
    fun vrStartEvent_whenSensorConnected_startsRecording() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val seeded = seedactiveSession()
        fakeBleManager.connectionState.value = ConnectionState.CONNECTED

        val vm = createVm()
        advanceUntilIdle()

        fakeVrClient.messages.emit(vrEvent("start_recording"))
        advanceUntilIdle()

        assertEquals(1, fakeSensorRecordingRepo.startRecordingCallCount)
        assertEquals(seeded.id, fakeSensorRecordingRepo.lastStartsessionId)
        assertEquals(seeded.sessionIdentifier, fakeSensorRecordingRepo.lastStartsessionIdentifier)
        assertTrue(vm.vrTriggeredRecording.value)
    }

    @Test
    fun vrStartEvent_whenNoSensorConnected_doesNotStartRecording() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedactiveSession()
        // All sensors remain disconnected (default fake state)

        val vm = createVm()
        advanceUntilIdle()

        fakeVrClient.messages.emit(vrEvent("start_recording"))
        advanceUntilIdle()

        assertEquals(0, fakeSensorRecordingRepo.startRecordingCallCount)
        assertFalse(vm.vrTriggeredRecording.value)
    }

    @Test
    fun vrStopEvent_whileRecording_stopsRecordingAndDeactivatesScene() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedactiveSession()
        fakeBleManager.connectionState.value = ConnectionState.CONNECTED

        val vm = createVm()
        advanceUntilIdle()

        // Begin recording via VR start
        fakeVrClient.messages.emit(vrEvent("start_recording"))
        advanceUntilIdle()
        assertEquals(DataRecordingState.RECORDING, fakeSensorRecordingRepo.recordingState.value)

        // Activate stress chamber scene so we can verify it gets deactivated
        connectionRepository.setStressChamberSceneActive(true)

        fakeVrClient.messages.emit(vrEvent("stop_recording"))
        advanceUntilIdle()

        assertEquals(1, fakeSensorRecordingRepo.stopRecordingCallCount)
        assertFalse(vm.vrTriggeredRecording.value)
        assertFalse(connectionRepository.isStressChamberSceneActive.value)
    }

    // ---- Group C: Scan timeout ----

    @Test
    fun bleScan_noDevicesAfter15s_flagsTimeout() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        seedactiveSession()
        // bluetoothEnabled is true in FakeBleManager by default
        // bleConnectionState starts DISCONNECTED → onHeartRateCardClick will scan

        val vm = createVm()
        advanceUntilIdle()

        vm.onHeartRateCardClick()
        // Simulate scanner actually starting (fake's startScan() is a no-op)
        (fakeBleManager.isScanning as MutableStateFlow<Boolean>).value = true
        runCurrent()

        // scan not timed out yet
        assertFalse(vm.scanTimeoutReached.value)

        advanceTimeBy(15_001)
        runCurrent()

        assertTrue("Expected scan timeout after 15s", vm.scanTimeoutReached.value)
    }

    // ---- Group E: End-test cleanup ----

    @Test
    fun endTestAndSave_stopsRecording_thenCompletesTest() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedactiveSession()

        val vm = createVm()
        advanceUntilIdle()

        // Put recording into RECORDING state so the stop call happens
        fakeSensorRecordingRepo.recordingState.value = DataRecordingState.RECORDING

        vm.endTestAndSave()
        advanceUntilIdle()

        assertEquals(1, fakeSensorRecordingRepo.stopRecordingCallCount)
        assertEquals(SessionStatus.COMPLETED, fakeSessionDao.tests[0].status)
        assertTrue(vm.endTestResult.value is EndTestResult.Success)
        assertEquals(1, fakeVrClient.suppressAutoReconnectCallCount)
    }

    @Test
    fun discardTest_stopsRecordingAndDeletesTest() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedactiveSession()

        val vm = createVm()
        advanceUntilIdle()

        fakeSensorRecordingRepo.recordingState.value = DataRecordingState.RECORDING

        vm.discardTest()
        advanceUntilIdle()

        assertEquals(1, fakeSensorRecordingRepo.stopRecordingCallCount)
        assertTrue("Test should be deleted", fakeSessionDao.tests.isEmpty())
    }

    // ---- Group F: Notes debounce ----

    @Test
    fun updateNotes_debounces500ms_thenSavesToDb() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        seedactiveSession()

        val vm = createVm()
        advanceUntilIdle()
        vm.setupNotesAutoSave()

        vm.updateNotes("hello")
        assertEquals(NotesSaveStatus.Saving, vm.notesSaveStatus.value)

        // Before debounce fires, DB has no notes
        advanceTimeBy(400)
        runCurrent()
        assertEquals("", fakeSessionDao.tests[0].notes)

        // Just past 500ms debounce: DB written, status briefly Saved
        advanceTimeBy(200)
        runCurrent()
        assertEquals("hello", fakeSessionDao.tests[0].notes)
        assertEquals(NotesSaveStatus.Saved, vm.notesSaveStatus.value)
    }

    // ---- Group G: Location-gated scan ----

    @Test
    fun onHeartRateCardClick_locationDisabled_showsLocationDialog() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedactiveSession()
        fakeLocationChecker.locationEnabled = false

        val vm = createVm()
        advanceUntilIdle()

        vm.onHeartRateCardClick()

        assertEquals(BleDialogState.LocationServicesRequired, vm.bleDialogState.value)
        // scan dialog should NOT open
        assertFalse(vm.showBleScanDialog.value)
    }
}
