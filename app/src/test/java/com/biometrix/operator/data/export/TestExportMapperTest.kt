package com.biometrix.operator.data.export

import com.biometrix.operator.data.db.FakeRecordingDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.FakeSudsEventDao
import com.biometrix.operator.data.db.RecordingEntity
import com.biometrix.operator.data.db.RecordingStatus
import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType
import com.biometrix.operator.data.db.SudsEventEntity
import com.biometrix.operator.data.db.TestEntity
import com.biometrix.operator.data.db.TestStatus
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.repository.SudsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TestExportMapperTest {

    private lateinit var fakeRecordingDao: FakeRecordingDao
    private lateinit var fakeSensorSampleDao: FakeSensorSampleDao
    private lateinit var fakeSudsEventDao: FakeSudsEventDao

    private lateinit var recordingRepository: RecordingRepository
    private lateinit var sudsRepository: SudsRepository

    private lateinit var mapper: TestExportMapper

    @Before
    fun setUp() {
        fakeRecordingDao = FakeRecordingDao()
        fakeSensorSampleDao = FakeSensorSampleDao()
        fakeSudsEventDao = FakeSudsEventDao()

        recordingRepository = RecordingRepository(fakeRecordingDao, fakeSensorSampleDao)
        sudsRepository = SudsRepository(fakeSudsEventDao)

        mapper = TestExportMapper(recordingRepository, sudsRepository)
    }

    // -- Sensor type mapping --

    @Test
    fun buildRecordingData_sensorTypeMappedCorrectly() = runTest {
        val recording = recording(heartRateEnabled = true, respirationEnabled = true, fibionEnabled = true)
        val samples = listOf(
            sample(recording.id, SensorType.HEART_RATE),
            sample(recording.id, SensorType.ESENSE_RR_INTERVAL),
            sample(recording.id, SensorType.RESPIRATION),
            sample(recording.id, SensorType.FIBION_HEART_RATE),
            sample(recording.id, SensorType.FIBION_ECG),
            sample(recording.id, SensorType.FIBION_RR_INTERVAL)
        )

        val result = mapper.buildRecordingData(recording, samples)

        val types = result.data.map { it.sensorType }
        assertEquals(
            listOf("esensePulse_hr", "esensePulse_rr", "esenseResp_resp", "fibion_hr", "fibion_ecg", "fibion_rr"),
            types
        )
    }

    // -- Sensor enable/disable flags --

    @Test
    fun buildRecordingData_heartRateDisabled_sensorInfoNull() = runTest {
        val recording = recording(heartRateEnabled = false, respirationEnabled = true)

        val result = mapper.buildRecordingData(recording, emptyList())

        assertNull(result.sensors.heartRate)
        assertNull(result.sensors.esenseRrInterval)
        assertNotNull(result.sensors.respiration)
    }

    @Test
    fun buildRecordingData_respirationDisabled_sensorInfoNull() = runTest {
        val recording = recording(heartRateEnabled = true, respirationEnabled = false)

        val result = mapper.buildRecordingData(recording, emptyList())

        assertNotNull(result.sensors.heartRate)
        assertNull(result.sensors.respiration)
    }

    @Test
    fun buildRecordingData_esenseRrIntervalPopulated_whenEnabledWithSamples() = runTest {
        val recording = recording(heartRateEnabled = true, esenseRrIntervalSampleCount = 10)

        val result = mapper.buildRecordingData(recording, emptyList())

        assertNotNull(result.sensors.esenseRrInterval)
        assertEquals(10, result.sensors.esenseRrInterval!!.sampleCount)
    }

    @Test
    fun buildRecordingData_fibionEnabled_subsensorsPopulated() = runTest {
        val recording = recording(
            fibionEnabled = true,
            fibionHeartRateSampleCount = 10,
            fibionEcgSampleCount = 20,
            fibionRrIntervalSampleCount = 5
        )

        val result = mapper.buildRecordingData(recording, emptyList())

        assertNotNull(result.sensors.fibionFlash)
        val fibion = result.sensors.fibionFlash!!
        assertTrue(fibion.enabled)
        assertEquals(10, fibion.heartRate!!.sampleCount)
        assertEquals(20, fibion.ecg!!.sampleCount)
        assertEquals(5, fibion.rrInterval!!.sampleCount)
    }

    @Test
    fun buildRecordingData_fibionEnabled_zeroSamples_subsensorsNull() = runTest {
        val recording = recording(fibionEnabled = true)

        val result = mapper.buildRecordingData(recording, emptyList())

        val fibion = result.sensors.fibionFlash!!
        assertNull(fibion.heartRate)
        assertNull(fibion.ecg)
        assertNull(fibion.rrInterval)
    }

    @Test
    fun buildRecordingData_fibionDisabled_fibionFlashNull() = runTest {
        val recording = recording(fibionEnabled = false)

        val result = mapper.buildRecordingData(recording, emptyList())

        assertNull(result.sensors.fibionFlash)
    }

    // -- Gap detection conditional logic --

    @Test
    fun buildRecordingData_heartRateDisabled_noGapsDetected() = runTest {
        val recording = recording(heartRateEnabled = false)
        // Samples that would produce gaps if detection ran
        val samples = listOf(
            sample(recording.id, SensorType.HEART_RATE, elapsedMs = 0),
            sample(recording.id, SensorType.HEART_RATE, elapsedMs = 60_000)
        )

        val result = mapper.buildRecordingData(recording, samples)

        assertNull(result.recordingGaps)
    }

    @Test
    fun buildRecordingData_heartRateEnabled_gapsDetected() = runTest {
        val recording = recording(heartRateEnabled = true, heartRateSampleCount = 3)
        // Three samples: first two past the 10s startup threshold, with a >5s gap between them
        val samples = listOf(
            sample(recording.id, SensorType.HEART_RATE, elapsedMs = 11_000),
            sample(recording.id, SensorType.HEART_RATE, elapsedMs = 12_000),
            sample(recording.id, SensorType.HEART_RATE, elapsedMs = 25_000)
        )

        val result = mapper.buildRecordingData(recording, samples)

        assertNotNull(result.recordingGaps)
        val hrGaps = result.recordingGaps!!.esensePulseHeartRate
        assertNotNull(hrGaps)
        assertTrue(hrGaps!!.gapCount > 0)
    }

    // -- buildExportData: statistics and event mapping --

    @Test
    fun buildExportData_statisticsReflectTestEntity() = runTest {
        val test = testEntity(
            recordingCount = 2,
            totalHeartRateSampleCount = 100,
            totalRespirationSampleCount = 50,
            totalFibionHeartRateSampleCount = 30,
            totalFibionEcgSampleCount = 200,
            totalFibionRrIntervalSampleCount = 25,
            totalEsenseRrIntervalSampleCount = 80
        )

        val result = mapper.buildExportData(test, emptyList())

        val stats = result.test.statistics
        assertEquals(2, stats.recordingCount)
        assertEquals(100, stats.totalHeartRateSamples)
        assertEquals(50, stats.totalRespirationSamples)
        assertEquals(30, stats.totalFibionHeartRateSamples)
        assertEquals(200, stats.totalFibionEcgSamples)
        assertEquals(25, stats.totalFibionRrIntervalSamples)
        assertEquals(80, stats.totalEsenseRrIntervalSamples)
    }

    @Test
    fun buildExportData_sudsEventsMapped() = runTest {
        val test = testEntity()
        fakeSudsEventDao.events.addAll(listOf(
            SudsEventEntity(testId = test.id, timestampMs = 1000L, value = 3),
            SudsEventEntity(testId = test.id, timestampMs = 2000L, value = 7)
        ))

        val result = mapper.buildExportData(test, emptyList())

        assertEquals(2, result.test.sudsEvents.size)
        assertEquals(3, result.test.sudsEvents[0].value)
        assertEquals(7, result.test.sudsEvents[1].value)
        assertEquals(2, result.test.statistics.totalSudsEvents)
    }

    // -- Recording data in export --

    @Test
    fun buildExportData_recordingSamplesIncluded() = runTest {
        val test = testEntity()
        val recording = recording(heartRateEnabled = true, heartRateSampleCount = 2)
        fakeSensorSampleDao.samples.addAll(listOf(
            sample(recording.id, SensorType.HEART_RATE, value = 72f),
            sample(recording.id, SensorType.HEART_RATE, value = 75f)
        ))

        val result = mapper.buildExportData(test, listOf(recording))

        assertEquals(1, result.test.recordings.size)
        assertEquals(2, result.test.recordings[0].data.size)
        assertEquals(72f, result.test.recordings[0].data[0].value, 0f)
    }

    @Test
    fun buildExportData_emptyRecordings_emptyList() = runTest {
        val test = testEntity()

        val result = mapper.buildExportData(test, emptyList())

        assertTrue(result.test.recordings.isEmpty())
    }

    @Test
    fun buildExportData_testFieldsMapped() = runTest {
        val test = testEntity(
            testIdentifier = "BMX-260413-141530",
            testNumber = "260413-141530",
            status = TestStatus.COMPLETED,
            notes = "Patient improved"
        )

        val result = mapper.buildExportData(test, emptyList())

        assertEquals("BMX-260413-141530", result.test.id)
        assertEquals("260413-141530", result.test.testNumber)
        assertEquals("COMPLETED", result.test.status)
        assertEquals("Patient improved", result.test.notes)
        assertEquals("1.0.0", result.version)
    }

    // -- Helpers --

    private var nextRecordingId = 1L

    private fun recording(
        testId: Long = 1L,
        heartRateEnabled: Boolean = false,
        respirationEnabled: Boolean = false,
        fibionEnabled: Boolean = false,
        heartRateSampleCount: Int = 0,
        respirationSampleCount: Int = 0,
        esenseRrIntervalSampleCount: Int = 0,
        fibionHeartRateSampleCount: Int = 0,
        fibionEcgSampleCount: Int = 0,
        fibionRrIntervalSampleCount: Int = 0
    ): RecordingEntity {
        val id = nextRecordingId++
        return RecordingEntity(
            id = id,
            testId = testId,
            recordingIdentifier = "BMX-260413-141530-R${String.format("%02d", id)}",
            sequenceNumber = id.toInt(),
            startedAt = 1000000L,
            endedAt = 1060000L,
            durationMs = 60000L,
            status = RecordingStatus.COMPLETED,
            heartRateEnabled = heartRateEnabled,
            respirationEnabled = respirationEnabled,
            fibionEnabled = fibionEnabled,
            heartRateSampleCount = heartRateSampleCount,
            respirationSampleCount = respirationSampleCount,
            esenseRrIntervalSampleCount = esenseRrIntervalSampleCount,
            fibionHeartRateSampleCount = fibionHeartRateSampleCount,
            fibionEcgSampleCount = fibionEcgSampleCount,
            fibionRrIntervalSampleCount = fibionRrIntervalSampleCount
        )
    }

    private fun sample(
        recordingId: Long,
        sensorType: SensorType,
        elapsedMs: Long = 0L,
        value: Float = 72f
    ) = SensorSampleEntity(
        recordingId = recordingId,
        timestampMs = 1000000L + elapsedMs,
        elapsedMs = elapsedMs,
        sensorType = sensorType,
        value = value
    )

    private fun testEntity(
        testIdentifier: String = "BMX-260413-141530",
        testNumber: String = "260413-141530",
        status: TestStatus = TestStatus.COMPLETED,
        notes: String = "",
        recordingCount: Int = 0,
        totalHeartRateSampleCount: Int = 0,
        totalRespirationSampleCount: Int = 0,
        totalFibionHeartRateSampleCount: Int = 0,
        totalFibionEcgSampleCount: Int = 0,
        totalFibionRrIntervalSampleCount: Int = 0,
        totalEsenseRrIntervalSampleCount: Int = 0
    ) = TestEntity(
        id = 1L,
        testNumber = testNumber,
        testIdentifier = testIdentifier,
        createdAt = 1000000L,
        endedAt = 1060000L,
        durationMs = 60000L,
        status = status,
        notes = notes,
        recordingCount = recordingCount,
        totalHeartRateSampleCount = totalHeartRateSampleCount,
        totalRespirationSampleCount = totalRespirationSampleCount,
        totalFibionHeartRateSampleCount = totalFibionHeartRateSampleCount,
        totalFibionEcgSampleCount = totalFibionEcgSampleCount,
        totalFibionRrIntervalSampleCount = totalFibionRrIntervalSampleCount,
        totalEsenseRrIntervalSampleCount = totalEsenseRrIntervalSampleCount
    )
}
