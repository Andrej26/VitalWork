package com.biometrix.operator.data.recording

import com.biometrix.operator.data.db.FakeRecordingDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.RecordingStatus
import com.biometrix.operator.data.db.SensorType
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.prefs.HeartRateDevice
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.sensor.DeviceState
import com.biometrix.operator.data.sensor.FakeSensorDevice
import com.biometrix.operator.data.sensor.ble.FakeBleManager
import com.biometrix.operator.data.sensor.fibion.FakeFibionFlashManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SensorRecordingRepositoryImplTest {

    private lateinit var bleManager: FakeBleManager
    private lateinit var respirationDevice: FakeSensorDevice
    private lateinit var fibionFlashManager: FakeFibionFlashManager
    private lateinit var fakeRecordingDao: FakeRecordingDao
    private lateinit var fakeSensorSampleDao: FakeSensorSampleDao
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var selectedDeviceFlow: MutableStateFlow<HeartRateDevice>

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        bleManager = FakeBleManager()
        respirationDevice = FakeSensorDevice()
        fibionFlashManager = FakeFibionFlashManager()
        fakeRecordingDao = FakeRecordingDao()
        fakeSensorSampleDao = FakeSensorSampleDao()
        recordingRepository = RecordingRepository(fakeRecordingDao, fakeSensorSampleDao)
        selectedDeviceFlow = MutableStateFlow(HeartRateDevice.ESENSE_PULSE)
    }

    private fun TestScope.createSut() = SensorRecordingRepositoryImpl(
        bleManager = bleManager,
        respirationDevice = respirationDevice,
        fibionFlashManager = fibionFlashManager,
        recordingRepository = recordingRepository,
        selectedDeviceFlow = selectedDeviceFlow,
        scope = backgroundScope
    )

    // -- Initial state --

    @Test
    fun initialState_isIdle() = runTest(testDispatcher) {
        val sut = createSut()
        assertEquals(DataRecordingState.IDLE, sut.recordingState.value)
        assertEquals(0L, sut.recordingDurationMs.value)
        assertNull(sut.recordingMetadata.value)
    }

    // -- Start recording: guards & state --

    @Test
    fun startRecording_setsStateAndCreatesRecording() = runTest(testDispatcher) {
        val sut = createSut()
        connectEsensePulse()
        respirationDevice.state.value = DeviceState.Streaming

        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")

        assertEquals(DataRecordingState.RECORDING, sut.recordingState.value)
        assertEquals(1, fakeRecordingDao.recordings.size)

        val recording = fakeRecordingDao.recordings[0]
        assertEquals(1L, recording.testId)
        assertTrue(recording.heartRateEnabled)
        assertTrue(recording.respirationEnabled)
        assertFalse(recording.fibionEnabled)

        val metadata = sut.recordingMetadata.value
        assertNotNull(metadata)
        assertTrue(metadata!!.heartRateRecording)
        assertTrue(metadata.respirationRecording)
        assertFalse(metadata.fibionRecording)
    }

    @Test
    fun startRecording_whenAlreadyRecording_doesNotCreateSecond() = runTest(testDispatcher) {
        val sut = createSut()
        connectEsensePulse()

        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")
        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")

        assertEquals(1, fakeRecordingDao.recordings.size)
    }

    // -- Sensor detection --

    @Test
    fun startRecording_esensePulse_whenConnected_enablesNotifications() = runTest(testDispatcher) {
        val sut = createSut()
        connectEsensePulse()
        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")
        assertEquals(1, bleManager.enableHrNotificationsCallCount)
    }

    @Test
    fun startRecording_esensePulse_whenDisconnected_doesNotEnableNotifications() = runTest(testDispatcher) {
        val sut = createSut()
        // Default state: bleManager disconnected
        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")
        assertEquals(0, bleManager.enableHrNotificationsCallCount)
    }

    @Test
    fun startRecording_respiration_whenConnectedOnly_autoStartsStreaming() = runTest(testDispatcher) {
        val sut = createSut()
        respirationDevice.state.value = DeviceState.Connected
        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")
        assertEquals(1, respirationDevice.startStreamingCallCount)
    }

    @Test
    fun startRecording_respiration_whenAlreadyStreaming_doesNotCallStartStreaming() = runTest(testDispatcher) {
        val sut = createSut()
        respirationDevice.state.value = DeviceState.Streaming
        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")
        assertEquals(0, respirationDevice.startStreamingCallCount)
    }

    @Test
    fun startRecording_fibion_whenConnected_subscribes() = runTest(testDispatcher) {
        val sut = createSut()
        connectFibionFlash()
        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")

        assertEquals(1, fibionFlashManager.subscribeHeartRateCallCount)
        assertEquals(1, fibionFlashManager.subscribeEcgCallCount)
        assertEquals(125, fibionFlashManager.lastSubscribeEcgSampleRate)
        assertTrue(sut.recordingMetadata.value!!.fibionRecording)
    }

    @Test
    fun startRecording_fibion_whenDisconnected_doesNotSubscribe() = runTest(testDispatcher) {
        val sut = createSut()
        // Select Fibion device but leave it disconnected
        selectedDeviceFlow.value = HeartRateDevice.FIBION_FLASH
        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")

        assertEquals(0, fibionFlashManager.subscribeHeartRateCallCount)
        assertEquals(0, fibionFlashManager.subscribeEcgCallCount)
        assertFalse(sut.recordingMetadata.value!!.fibionRecording)
    }

    // -- Sensor collection --

    @Test
    fun sensorSamples_writtenWithCorrectTypes() = runTest(testDispatcher) {
        val sut = createSut()
        connectEsensePulse()
        respirationDevice.state.value = DeviceState.Streaming

        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")

        bleManager.heartRateSampleFlow.emit(72f)
        bleManager.rrIntervalSampleFlow.emit(833f)
        respirationDevice.sampleFlow.emit(15f)

        sut.stopRecording()

        val samples = fakeSensorSampleDao.samples
        assertEquals(3, samples.size)
        assertEquals(1, samples.count { it.sensorType == SensorType.HEART_RATE })
        assertEquals(1, samples.count { it.sensorType == SensorType.ESENSE_RR_INTERVAL })
        assertEquals(1, samples.count { it.sensorType == SensorType.RESPIRATION })
    }

    @Test
    fun sampleEntity_hasCorrectRecordingIdAndTimestamps() = runTest(testDispatcher) {
        val sut = createSut()
        connectEsensePulse()
        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")

        val recordingId = fakeRecordingDao.recordings[0].id

        bleManager.heartRateSampleFlow.emit(72f)
        sut.stopRecording()

        val sample = fakeSensorSampleDao.samples[0]
        assertEquals(recordingId, sample.recordingId)
        assertTrue("timestampMs should be > 0", sample.timestampMs > 0)
        assertTrue("elapsedMs should be >= 0", sample.elapsedMs >= 0)
        assertEquals(72f, sample.value, 0f)
    }

    @Test
    fun sampleCounts_updatedInMetadata() = runTest(testDispatcher) {
        val sut = createSut()
        connectEsensePulse()
        respirationDevice.state.value = DeviceState.Streaming

        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")

        repeat(5) { bleManager.heartRateSampleFlow.emit(72f) }
        repeat(3) { respirationDevice.sampleFlow.emit(15f) }

        val metadata = sut.recordingMetadata.value!!
        assertEquals(5, metadata.heartRateSampleCount)
        assertEquals(3, metadata.respirationSampleCount)
    }

    // -- Fibion sensor collection --

    @Test
    fun fibionSamples_allThreeTypesWritten() = runTest(testDispatcher) {
        val sut = createSut()
        connectFibionFlash()
        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")

        fibionFlashManager.heartRateSampleFlow.emit(75f)
        fibionFlashManager.ecgSampleFlow.emit(0.5f)
        fibionFlashManager.rrIntervalSampleFlow.emit(800f)

        sut.stopRecording()

        val samples = fakeSensorSampleDao.samples
        assertEquals(3, samples.size)
        assertEquals(1, samples.count { it.sensorType == SensorType.FIBION_HEART_RATE })
        assertEquals(1, samples.count { it.sensorType == SensorType.FIBION_ECG })
        assertEquals(1, samples.count { it.sensorType == SensorType.FIBION_RR_INTERVAL })
    }

    // -- Stop recording --

    @Test
    fun stopRecording_whenNotRecording_doesNothing() = runTest(testDispatcher) {
        val sut = createSut()
        sut.stopRecording()
        assertEquals(DataRecordingState.IDLE, sut.recordingState.value)
    }

    @Test
    fun stopRecording_resetsStateAndMetadata() = runTest(testDispatcher) {
        val sut = createSut()
        connectEsensePulse()
        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")
        sut.stopRecording()

        assertEquals(DataRecordingState.IDLE, sut.recordingState.value)
        assertNull(sut.recordingMetadata.value)
        assertEquals(0L, sut.recordingDurationMs.value)
    }

    @Test
    fun stopRecording_completesRecordingAndFlushesSamples() = runTest(testDispatcher) {
        val sut = createSut()
        connectEsensePulse()
        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")

        repeat(3) { bleManager.heartRateSampleFlow.emit(72f + it) }

        sut.stopRecording()

        // Recording should be COMPLETED
        val recording = fakeRecordingDao.recordings[0]
        assertEquals(RecordingStatus.COMPLETED, recording.status)

        // All samples flushed to DB
        assertEquals(3, fakeSensorSampleDao.samples.size)
    }

    // -- Edge cases --

    @Test
    fun noSensorsConnected_allFlagsDisabledNoSamplesWritten() = runTest(testDispatcher) {
        val sut = createSut()
        // All sensors disconnected (default state)
        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")

        val metadata = sut.recordingMetadata.value!!
        assertFalse(metadata.heartRateRecording)
        assertFalse(metadata.respirationRecording)
        assertFalse(metadata.fibionRecording)

        sut.stopRecording()

        assertEquals(0, fakeSensorSampleDao.samples.size)
    }

    @Test
    fun startStopStart_secondRecordingGetsNewId() = runTest(testDispatcher) {
        val sut = createSut()
        connectEsensePulse()

        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")
        bleManager.heartRateSampleFlow.emit(72f)
        sut.stopRecording()

        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")
        bleManager.heartRateSampleFlow.emit(75f)
        sut.stopRecording()

        assertEquals(2, fakeRecordingDao.recordings.size)
        val ids = fakeRecordingDao.recordings.map { it.id }.toSet()
        assertEquals(2, ids.size) // Different IDs

        // Both recordings have their samples
        assertEquals(2, fakeSensorSampleDao.samples.size)
    }

    @Test
    fun fullCycle_multipleSensorTypes_allPersisted() = runTest(testDispatcher) {
        val sut = createSut()
        connectEsensePulse()
        respirationDevice.state.value = DeviceState.Streaming

        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")

        // Emit a mix of samples
        bleManager.heartRateSampleFlow.emit(72f)
        bleManager.heartRateSampleFlow.emit(74f)
        bleManager.rrIntervalSampleFlow.emit(833f)
        respirationDevice.sampleFlow.emit(15f)
        respirationDevice.sampleFlow.emit(16f)

        sut.stopRecording()

        val samples = fakeSensorSampleDao.samples
        assertEquals(5, samples.size)
        assertEquals(2, samples.count { it.sensorType == SensorType.HEART_RATE })
        assertEquals(1, samples.count { it.sensorType == SensorType.ESENSE_RR_INTERVAL })
        assertEquals(2, samples.count { it.sensorType == SensorType.RESPIRATION })

        // Recording completed
        assertEquals(RecordingStatus.COMPLETED, fakeRecordingDao.recordings[0].status)
    }

    @Test
    fun manySamples_flushedInBatchesBeforeStop() = runTest(testDispatcher) {
        val sut = createSut()
        connectEsensePulse()
        sut.startRecording(testId = 1L, testIdentifier = "BMX-260413-100000")

        // Emit 55 samples — exceeds the 50-sample batch threshold.
        // The size-based flush path should write at least 50 to the DAO before stopRecording().
        repeat(55) { bleManager.heartRateSampleFlow.emit(72f + it) }

        assertTrue(
            "Expected >=50 samples flushed by batch-size trigger, got ${fakeSensorSampleDao.samples.size}",
            fakeSensorSampleDao.samples.size >= 50
        )

        sut.stopRecording()

        // After stop, all 55 samples should be persisted (final flush on channel close).
        assertEquals(55, fakeSensorSampleDao.samples.size)
    }

    // -- Helpers --

    private fun connectEsensePulse() {
        selectedDeviceFlow.value = HeartRateDevice.ESENSE_PULSE
        bleManager.connectionState.value = ConnectionState.CONNECTED
    }

    private fun connectFibionFlash() {
        selectedDeviceFlow.value = HeartRateDevice.FIBION_FLASH
        fibionFlashManager.connectionState.value = ConnectionState.CONNECTED
    }
}
