package com.biometrix.operator.presentation.screens.tests

import androidx.lifecycle.SavedStateHandle
import com.biometrix.operator.data.db.FakeRecordingDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.FakeSudsEventDao
import com.biometrix.operator.data.db.FakeTestDao
import com.biometrix.operator.data.db.TestEntity
import com.biometrix.operator.data.db.TestStatus
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.recording.FakeSensorRecordingRepository
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.repository.SudsRepository
import com.biometrix.operator.data.repository.TestRepository
import com.biometrix.operator.data.sensor.FakeSensorDevice
import com.biometrix.operator.data.sensor.ble.FakeBleManager
import com.biometrix.operator.data.system.FakeLocationChecker
import com.biometrix.operator.data.vr.FakeVRConnectionManager
import com.biometrix.operator.data.vr.FakeVrDeviceDiscovery
import com.biometrix.operator.data.vr.model.ServerMessage
import com.biometrix.operator.data.vr.model.WebSocketMessage
import com.biometrix.operator.presentation.components.BleDialogState
import com.biometrix.operator.presentation.screens.tests.components.NotesSaveStatus
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
class TestControlViewModelTest {

    private lateinit var fakeVrClient: FakeVRConnectionManager
    private lateinit var fakeDiscovery: FakeVrDeviceDiscovery
    private lateinit var fakeBleManager: FakeBleManager
    private lateinit var fakeRespiration: FakeSensorDevice
    private lateinit var fakeTestDao: FakeTestDao
    private lateinit var fakeRecordingDao: FakeRecordingDao
    private lateinit var fakeSensorSampleDao: FakeSensorSampleDao
    private lateinit var fakeSudsDao: FakeSudsEventDao
    private lateinit var fakeSensorRecordingRepo: FakeSensorRecordingRepository
    private lateinit var fakeLocationChecker: FakeLocationChecker
    private lateinit var connectionRepository: ConnectionRepository
    private lateinit var testRepository: TestRepository
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var sudsRepository: SudsRepository
    private lateinit var lanAvailableFlow: MutableStateFlow<Boolean>

    private val testId = 1L

    @Before
    fun setUp() {
        fakeVrClient = FakeVRConnectionManager()
        fakeDiscovery = FakeVrDeviceDiscovery()
        fakeBleManager = FakeBleManager()
        fakeRespiration = FakeSensorDevice()
        fakeTestDao = FakeTestDao()
        fakeRecordingDao = FakeRecordingDao()
        fakeSensorSampleDao = FakeSensorSampleDao()
        fakeSudsDao = FakeSudsEventDao()
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
        testRepository = TestRepository(fakeTestDao, fakeRecordingDao)
        sudsRepository = SudsRepository(fakeSudsDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun seedActiveTest(id: Long = testId): TestEntity {
        val test = TestEntity(
            id = id,
            testNumber = "260413-100000",
            testIdentifier = "BMX-260413-100000",
            createdAt = System.currentTimeMillis(),
            status = TestStatus.ACTIVE
        )
        fakeTestDao.tests.add(test)
        return test
    }

    private fun createVm(savedTestId: Long = testId): TestControlViewModel {
        val savedState = SavedStateHandle(mapOf("testId" to savedTestId))
        return TestControlViewModel(
            connectionRepository = connectionRepository,
            sensorRecordingRepository = fakeSensorRecordingRepo,
            testRepository = testRepository,
            recordingRepository = recordingRepository,
            sudsRepository = sudsRepository,
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
        val seeded = seedActiveTest()

        val vm = createVm()
        advanceUntilIdle()

        assertEquals(seeded.id, vm.test.value?.id)
        assertEquals("BMX-260413-100000", vm.test.value?.testIdentifier)
    }

    @Test
    fun init_unknownTestId_returnsNullStateGracefully() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val vm = createVm(savedTestId = 999L)
        advanceUntilIdle()

        assertNull(vm.test.value)
    }

    // ---- Group B: VR biofeedback automation ----

    @Test
    fun vrStartEvent_whenSensorConnected_startsRecording() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val seeded = seedActiveTest()
        fakeBleManager.connectionState.value = ConnectionState.CONNECTED

        val vm = createVm()
        advanceUntilIdle()

        fakeVrClient.messages.emit(vrEvent("start_recording"))
        advanceUntilIdle()

        assertEquals(1, fakeSensorRecordingRepo.startRecordingCallCount)
        assertEquals(seeded.id, fakeSensorRecordingRepo.lastStartTestId)
        assertEquals(seeded.testIdentifier, fakeSensorRecordingRepo.lastStartTestIdentifier)
        assertTrue(vm.vrTriggeredRecording.value)
    }

    @Test
    fun vrStartEvent_whenNoSensorConnected_doesNotStartRecording() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveTest()
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
        seedActiveTest()
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

    @Test
    fun vrSudsEvent_thirdInSession_deactivatesStressChamber() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveTest()

        val vm = createVm()
        advanceUntilIdle()

        connectionRepository.setStressChamberSceneActive(true)

        fakeVrClient.messages.emit(vrEvent("suds", 5))
        fakeVrClient.messages.emit(vrEvent("suds", 6))
        advanceUntilIdle()
        assertTrue(connectionRepository.isStressChamberSceneActive.value)

        fakeVrClient.messages.emit(vrEvent("suds", 7))
        advanceUntilIdle()
        assertFalse(connectionRepository.isStressChamberSceneActive.value)

        // All three SUDS events were persisted (scene was active, not tutorial)
        assertEquals(3, fakeSudsDao.events.size)
    }

    // ---- Group C: SUDS vs. tutorial gating ----

    @Test
    fun vrSudsEvent_duringTutorial_doesNotSaveToDb() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveTest()
        fakeVrClient.connectionState.value = ConnectionState.CONNECTED

        val vm = createVm()
        advanceUntilIdle()

        // sendTutorialCommand flips isTutorialActive = true
        vm.sendTutorialCommand()
        advanceUntilIdle()

        fakeVrClient.messages.emit(vrEvent("suds", 4))
        advanceUntilIdle()

        assertTrue("No SUDS event should be saved during tutorial", fakeSudsDao.events.isEmpty())
    }

    // ---- Group D: Scan timeout ----

    @Test
    fun bleScan_noDevicesAfter15s_flagsTimeout() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        seedActiveTest()
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
        seedActiveTest()

        val vm = createVm()
        advanceUntilIdle()

        // Put recording into RECORDING state so the stop call happens
        fakeSensorRecordingRepo.recordingState.value = DataRecordingState.RECORDING

        vm.endTestAndSave()
        advanceUntilIdle()

        assertEquals(1, fakeSensorRecordingRepo.stopRecordingCallCount)
        assertEquals(TestStatus.COMPLETED, fakeTestDao.tests[0].status)
        assertTrue(vm.endTestResult.value is EndTestResult.Success)
        assertEquals(1, fakeVrClient.suppressAutoReconnectCallCount)
    }

    @Test
    fun discardTest_stopsRecordingAndDeletesTest() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveTest()

        val vm = createVm()
        advanceUntilIdle()

        fakeSensorRecordingRepo.recordingState.value = DataRecordingState.RECORDING

        vm.discardTest()
        advanceUntilIdle()

        assertEquals(1, fakeSensorRecordingRepo.stopRecordingCallCount)
        assertTrue("Test should be deleted", fakeTestDao.tests.isEmpty())
    }

    // ---- Group F: Notes debounce ----

    @Test
    fun updateNotes_debounces500ms_thenSavesToDb() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        seedActiveTest()

        val vm = createVm()
        advanceUntilIdle()
        vm.setupNotesAutoSave()

        vm.updateNotes("hello")
        assertEquals(NotesSaveStatus.Saving, vm.notesSaveStatus.value)

        // Before debounce fires, DB has no notes
        advanceTimeBy(400)
        runCurrent()
        assertEquals("", fakeTestDao.tests[0].notes)

        // Just past 500ms debounce: DB written, status briefly Saved
        advanceTimeBy(200)
        runCurrent()
        assertEquals("hello", fakeTestDao.tests[0].notes)
        assertEquals(NotesSaveStatus.Saved, vm.notesSaveStatus.value)
    }

    // ---- Group G: Location-gated scan ----

    @Test
    fun onHeartRateCardClick_locationDisabled_showsLocationDialog() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        seedActiveTest()
        fakeLocationChecker.locationEnabled = false

        val vm = createVm()
        advanceUntilIdle()

        vm.onHeartRateCardClick()

        assertEquals(BleDialogState.LocationServicesRequired, vm.bleDialogState.value)
        // scan dialog should NOT open
        assertFalse(vm.showBleScanDialog.value)
    }
}
