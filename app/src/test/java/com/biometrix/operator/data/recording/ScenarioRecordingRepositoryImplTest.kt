package com.biometrix.operator.data.recording

import com.biometrix.operator.data.db.FakeScenarioDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.ScenarioCategory
import com.biometrix.operator.data.db.ScenarioCode
import com.biometrix.operator.data.db.ScenarioEntity
import com.biometrix.operator.data.db.SensorType
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.repository.ScenarioRepository
import com.biometrix.operator.data.sensor.DeviceState
import com.biometrix.operator.data.sensor.FakeSensorDevice
import com.biometrix.operator.data.sensor.ble.FakeBleManager
import com.biometrix.operator.data.sensor.watch.WatchSensorReceiver
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class ScenarioRecordingRepositoryImplTest {

    private lateinit var bleManager: FakeBleManager
    private lateinit var respirationDevice: FakeSensorDevice
    private lateinit var fakeScenarioDao: FakeScenarioDao
    private lateinit var fakeSensorSampleDao: FakeSensorSampleDao
    private lateinit var scenarioRepository: ScenarioRepository

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        bleManager = FakeBleManager()
        respirationDevice = FakeSensorDevice()
        fakeScenarioDao = FakeScenarioDao()
        fakeSensorSampleDao = FakeSensorSampleDao()
        scenarioRepository = ScenarioRepository(fakeScenarioDao, fakeSensorSampleDao)
    }

    private fun TestScope.createSut() = ScenarioRecordingRepositoryImpl(
        bleManager = bleManager,
        respirationDevice = respirationDevice,
        scenarioRepository = scenarioRepository,
        watchReceiver = WatchSensorReceiver(),
        scope = backgroundScope
    )

    /** Pre-seeds a scenario row so the buffering layer has something to write samples against. */
    private fun seedScenario(id: Long = 1L): ScenarioEntity {
        val s = ScenarioEntity(
            id = id,
            sessionId = 1L,
            scenarioCode = ScenarioCode.FALLING_PALLET,
            scenarioCategory = ScenarioCategory.A,
            startedAt = System.currentTimeMillis()
        )
        fakeScenarioDao.scenarios.add(s)
        return s
    }

    @Test
    fun initialState_isIdle() = runTest(testDispatcher) {
        val sut = createSut()
        assertEquals(DataRecordingState.IDLE, sut.recordingState.value)
        assertEquals(0L, sut.recordingDurationMs.value)
        assertNull(sut.recordingMetadata.value)
    }

    @Test
    fun startRecording_setsStateAndMetadata() = runTest(testDispatcher) {
        val sut = createSut()
        val scenario = seedScenario()
        connectEsensePulse()
        respirationDevice.state.value = DeviceState.Streaming

        sut.startRecording(scenarioId = scenario.id, scenarioIdentifier = "BMX-X-A1")

        assertEquals(DataRecordingState.RECORDING, sut.recordingState.value)
        val metadata = sut.recordingMetadata.value
        assertNotNull(metadata)
        assertEquals(scenario.id, metadata!!.scenarioId)
        assertTrue(metadata.heartRateRecording)
        assertTrue(metadata.respirationRecording)
    }

    @Test
    fun startRecording_whenAlreadyRecording_doesNothing() = runTest(testDispatcher) {
        val sut = createSut()
        val scenario = seedScenario()
        connectEsensePulse()

        sut.startRecording(scenario.id, "BMX-X-A1")
        sut.startRecording(scenario.id, "BMX-X-A1")

        // No second metadata snapshot ID change, no second start
        assertEquals(DataRecordingState.RECORDING, sut.recordingState.value)
    }

    @Test
    fun startRecording_esensePulse_connected_enablesNotifications() = runTest(testDispatcher) {
        val sut = createSut()
        val scenario = seedScenario()
        connectEsensePulse()

        sut.startRecording(scenario.id, "BMX-X-A1")

        assertEquals(1, bleManager.enableHrNotificationsCallCount)
    }

    @Test
    fun startRecording_respiration_connectedOnly_autoStartsStreaming() = runTest(testDispatcher) {
        val sut = createSut()
        val scenario = seedScenario()
        respirationDevice.state.value = DeviceState.Connected

        sut.startRecording(scenario.id, "BMX-X-A1")

        assertEquals(1, respirationDevice.startStreamingCallCount)
    }

    @Test
    fun samples_writtenWithCorrectScenarioIdAndType() = runTest(testDispatcher) {
        val sut = createSut()
        val scenario = seedScenario(id = 42L)
        connectEsensePulse()
        respirationDevice.state.value = DeviceState.Streaming

        sut.startRecording(scenario.id, "BMX-X-A1")

        bleManager.heartRateSampleFlow.emit(72f)
        bleManager.rrIntervalSampleFlow.emit(833f)
        respirationDevice.sampleFlow.emit(15f)

        sut.stopRecording()

        val samples = fakeSensorSampleDao.samples
        assertEquals(3, samples.size)
        assertTrue(samples.all { it.scenarioId == 42L })
        assertEquals(1, samples.count { it.sensorType == SensorType.HEART_RATE })
        assertEquals(1, samples.count { it.sensorType == SensorType.ESENSE_RR_INTERVAL })
        assertEquals(1, samples.count { it.sensorType == SensorType.RESPIRATION })
    }

    @Test
    fun stopRecording_marksScenarioEnded() = runTest(testDispatcher) {
        val sut = createSut()
        val scenario = seedScenario()
        connectEsensePulse()

        sut.startRecording(scenario.id, "BMX-X-A1")
        sut.stopRecording()

        val updated = fakeScenarioDao.getScenarioById(scenario.id)!!
        assertNotNull(updated.endedAt)
    }

    @Test
    fun stopRecording_whenNotRecording_doesNothing() = runTest(testDispatcher) {
        val sut = createSut()
        sut.stopRecording()
        assertEquals(DataRecordingState.IDLE, sut.recordingState.value)
    }

    @Test
    fun noSensorsConnected_noSamplesWritten() = runTest(testDispatcher) {
        val sut = createSut()
        val scenario = seedScenario()

        sut.startRecording(scenario.id, "BMX-X-A1")
        val metadata = sut.recordingMetadata.value!!
        assertFalse(metadata.heartRateRecording)
        assertFalse(metadata.respirationRecording)

        sut.stopRecording()
        assertEquals(0, fakeSensorSampleDao.samples.size)
    }

    @Test
    fun manySamples_flushedInBatchesBeforeStop() = runTest(testDispatcher) {
        val sut = createSut()
        val scenario = seedScenario()
        connectEsensePulse()
        sut.startRecording(scenario.id, "BMX-X-A1")

        repeat(55) { bleManager.heartRateSampleFlow.emit(72f + it) }

        assertTrue(
            "Expected >=50 samples flushed by batch-size trigger, got ${fakeSensorSampleDao.samples.size}",
            fakeSensorSampleDao.samples.size >= 50
        )

        sut.stopRecording()

        assertEquals(55, fakeSensorSampleDao.samples.size)
    }

    private fun connectEsensePulse() {
        bleManager.connectionState.value = ConnectionState.CONNECTED
    }
}
