package com.vitalwork.app.data.recording

import com.vitalwork.app.data.db.FakeScenarioDao
import com.vitalwork.app.data.db.FakeSensorSampleDao
import com.vitalwork.app.data.db.ScenarioCategory
import com.vitalwork.app.data.db.ScenarioCode
import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SensorSampleEntity
import com.vitalwork.app.data.db.SensorType
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.recording.model.DataRecordingState
import com.vitalwork.app.data.repository.ScenarioRepository
import com.vitalwork.app.data.sensor.DeviceState
import com.vitalwork.app.data.sensor.FakeSensorDevice
import com.vitalwork.app.data.sensor.ble.FakeBleManager
import com.vitalwork.app.data.sensor.watch.WatchSensorReceiver
import com.vitalwork.app.data.sensor.watch.model.WatchReading
import com.vitalwork.app.data.time.TimeProvider
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
    private lateinit var watchReceiver: WatchSensorReceiver

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        bleManager = FakeBleManager()
        respirationDevice = FakeSensorDevice()
        fakeScenarioDao = FakeScenarioDao()
        fakeSensorSampleDao = FakeSensorSampleDao()
        scenarioRepository = ScenarioRepository(fakeScenarioDao, fakeSensorSampleDao, TimeProvider.system())
        watchReceiver = WatchSensorReceiver(TimeProvider.system())
    }

    private fun TestScope.createSut() = ScenarioRecordingRepositoryImpl(
        bleManager = bleManager,
        respirationDevice = respirationDevice,
        scenarioRepository = scenarioRepository,
        watchReceiver = watchReceiver,
        scope = backgroundScope
    )

    /** Seed a closed scenario window (has endedAt) so the watch drain can attribute readings to it. */
    private fun seedClosedScenario(id: Long, startedAt: Long, endedAt: Long): ScenarioEntity {
        val s = ScenarioEntity(
            id = id,
            sessionId = 1L,
            scenarioCode = ScenarioCode.FALLING_PALLET,
            scenarioCategory = ScenarioCategory.A,
            startedAt = startedAt,
            endedAt = endedAt
        )
        fakeScenarioDao.scenarios.add(s)
        return s
    }

    /** Drive the receiver to a COMPLETE flush carrying [readings] (rowCount == readings.size). */
    private fun deliverCompleteFlush(readings: List<WatchReading>) {
        watchReceiver.onFlushStarted()
        watchReceiver.onFlushedReadings(readings)
        val maxTs = readings.maxOfOrNull { it.timestampMs } ?: Long.MIN_VALUE
        watchReceiver.onFlushChunk(batchId = 1L, index = 0, count = 1, maxWatchTsInChunk = maxTs)
        watchReceiver.onFlushComplete(batchId = 1L, chunkCount = 1, rowCount = readings.size)
    }

    private fun hr(t: Long, v: Float = 70f) = WatchReading("WATCH_HR", v, 1, t)

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

        sut.startRecording(scenarioId = scenario.id, scenarioIdentifier = "VW-X-A1")

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

        sut.startRecording(scenario.id, "VW-X-A1")
        sut.startRecording(scenario.id, "VW-X-A1")

        // No second metadata snapshot ID change, no second start
        assertEquals(DataRecordingState.RECORDING, sut.recordingState.value)
    }

    @Test
    fun startRecording_esensePulse_connected_enablesNotifications() = runTest(testDispatcher) {
        val sut = createSut()
        val scenario = seedScenario()
        connectEsensePulse()

        sut.startRecording(scenario.id, "VW-X-A1")

        assertEquals(1, bleManager.enableHrNotificationsCallCount)
    }

    @Test
    fun startRecording_respiration_connectedOnly_autoStartsStreaming() = runTest(testDispatcher) {
        val sut = createSut()
        val scenario = seedScenario()
        respirationDevice.state.value = DeviceState.Connected

        sut.startRecording(scenario.id, "VW-X-A1")

        assertEquals(1, respirationDevice.startStreamingCallCount)
    }

    @Test
    fun samples_writtenWithCorrectScenarioIdAndType() = runTest(testDispatcher) {
        val sut = createSut()
        val scenario = seedScenario(id = 42L)
        connectEsensePulse()
        respirationDevice.state.value = DeviceState.Streaming

        sut.startRecording(scenario.id, "VW-X-A1")

        bleManager.heartRateSampleFlow.emit(72f)
        bleManager.rrIntervalSampleFlow.emit(833f)
        respirationDevice.sampleFlow.emit(15f)

        sut.stopRecording()

        val samples = fakeSensorSampleDao.samples
        assertEquals(3, samples.size)
        assertTrue(samples.all { it.scenarioId == 42L })
        assertEquals(1, samples.count { it.sensorType == SensorType.ESENSE_HEART_RATE })
        assertEquals(1, samples.count { it.sensorType == SensorType.ESENSE_RR_INTERVAL })
        assertEquals(1, samples.count { it.sensorType == SensorType.RESPIRATION })
    }

    @Test
    fun stopRecording_marksScenarioEnded() = runTest(testDispatcher) {
        val sut = createSut()
        val scenario = seedScenario()
        connectEsensePulse()

        sut.startRecording(scenario.id, "VW-X-A1")
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

        sut.startRecording(scenario.id, "VW-X-A1")
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
        sut.startRecording(scenario.id, "VW-X-A1")

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

    // --- Galaxy Watch session-end: authoritative rebuild from a complete flush ---

    @Test
    fun drainFinalize_completeFlush_rebuildsFromStore_replacingLiveRows_noLossNoDup() =
        runTest(testDispatcher) {
            val sut = createSut()
            val s = seedClosedScenario(id = 7L, startedAt = 1_000L, endedAt = 2_000L)
            // Provisional live rows already in the DB (some, not all of the window) — must be replaced.
            fakeSensorSampleDao.samples.add(
                SensorSampleEntity(scenarioId = 7L, timestampMs = 1_100L, elapsedMs = 100L, sensorType = SensorType.WATCH_HR, value = 70f)
            )
            // Complete store: 3 in-window (incl. one the live path never had: 1500) + 1 out-of-window.
            deliverCompleteFlush(listOf(hr(1_100L), hr(1_200L), hr(1_500L), hr(2_500L)))

            val report = sut.drainAndFinalizeWatchEda(listOf(s))

            assertNotNull(report)
            assertTrue(report!!.summary(), report.ok)
            assertEquals(4, report.claimed)
            assertEquals(4, report.received)
            assertEquals(3, report.inScenario)
            assertEquals(1, report.betweenScenario)
            assertEquals(3, report.dbRows)
            // DB holds exactly the 3 in-window store rows (the stale live row replaced, the gap row dropped).
            val watchRows = fakeSensorSampleDao.samples.filter { it.sensorType == SensorType.WATCH_HR }
            assertEquals(3, watchRows.size)
            assertEquals(setOf(1_100L, 1_200L, 1_500L), watchRows.map { it.timestampMs }.toSet())
        }

    @Test
    fun drainFinalize_incompleteFlush_keepsLiveRows_returnsNull() = runTest(testDispatcher) {
        val sut = createSut()
        val s = seedClosedScenario(id = 7L, startedAt = 1_000L, endedAt = 2_000L)
        // A provisional live row exists; the flush never completes (no onFlushComplete) → unverified.
        fakeSensorSampleDao.samples.add(
            SensorSampleEntity(scenarioId = 7L, timestampMs = 1_100L, elapsedMs = 100L, sensorType = SensorType.WATCH_HR, value = 70f)
        )

        val report = sut.drainAndFinalizeWatchEda(listOf(s))

        assertNull(report) // not verified
        // Fallback path must NOT delete the provisional live row.
        assertEquals(1, fakeSensorSampleDao.samples.count { it.sensorType == SensorType.WATCH_HR })
    }

    @Test
    fun drainFinalize_rowCountMismatch_fallsBackUnverified() = runTest(testDispatcher) {
        val sut = createSut()
        val s = seedClosedScenario(id = 7L, startedAt = 1_000L, endedAt = 2_000L)
        fakeSensorSampleDao.samples.add(
            SensorSampleEntity(scenarioId = 7L, timestampMs = 1_100L, elapsedMs = 100L, sensorType = SensorType.WATCH_HR, value = 70f)
        )
        // Watch claims 5 rows but only 2 were buffered (a row was lost in transport) → don't trust it.
        watchReceiver.onFlushStarted()
        watchReceiver.onFlushedReadings(listOf(hr(1_100L), hr(1_200L)))
        watchReceiver.onFlushChunk(batchId = 1L, index = 0, count = 1, maxWatchTsInChunk = 1_200L)
        watchReceiver.onFlushComplete(batchId = 1L, chunkCount = 1, rowCount = 5)

        val report = sut.drainAndFinalizeWatchEda(listOf(s))

        assertNull(report) // unverified: don't trust an incomplete store to rebuild
        // The authoritative delete-and-replace must NOT have run, so the provisional live row survives.
        assertTrue(
            fakeSensorSampleDao.samples.any { it.timestampMs == 1_100L && it.sensorType == SensorType.WATCH_HR }
        )
    }
}
