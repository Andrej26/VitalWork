package com.biometrix.operator.data.export

import com.biometrix.operator.data.db.FakeRecordingDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.FakeSudsEventDao
import com.biometrix.operator.data.db.RecordingEntity
import com.biometrix.operator.data.db.RecordingStatus
import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType
import com.biometrix.operator.data.db.SudsEventEntity
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.repository.SudsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionExportMapperTest {

    private lateinit var fakeRecordingDao: FakeRecordingDao
    private lateinit var fakeSensorSampleDao: FakeSensorSampleDao
    private lateinit var fakeSudsEventDao: FakeSudsEventDao

    private lateinit var recordingRepository: RecordingRepository
    private lateinit var sudsRepository: SudsRepository

    private lateinit var mapper: SessionExportMapper

    @Before
    fun setUp() {
        fakeRecordingDao = FakeRecordingDao()
        fakeSensorSampleDao = FakeSensorSampleDao()
        fakeSudsEventDao = FakeSudsEventDao()

        recordingRepository = RecordingRepository(fakeRecordingDao, fakeSensorSampleDao)
        sudsRepository = SudsRepository(fakeSudsEventDao)

        mapper = SessionExportMapper(recordingRepository, sudsRepository)
    }

    // -- Sensor type mapping --

    @Test
    fun buildRecordingData_sensorTypeMappedCorrectly() = runTest {
        val recording = recording(heartRateEnabled = true, respirationEnabled = true)
        val samples = listOf(
            sample(recording.id, SensorType.HEART_RATE),
            sample(recording.id, SensorType.ESENSE_RR_INTERVAL),
            sample(recording.id, SensorType.RESPIRATION)
        )

        val result = mapper.buildRecordingData(recording, samples)

        val types = result.data.map { it.sensorType }
        assertEquals(
            listOf("esensePulse_hr", "esensePulse_rr", "esenseResp_resp"),
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

    // -- Gap detection conditional logic --

    @Test
    fun buildRecordingData_heartRateDisabled_noGapsDetected() = runTest {
        val recording = recording(heartRateEnabled = false)
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
    fun buildExportData_statisticsReflectSessionEntity() = runTest {
        val test = SessionEntity(
            recordingCount = 2,
            totalHeartRateSampleCount = 100,
            totalRespirationSampleCount = 50,
            totalEsenseRrIntervalSampleCount = 80
        )

        val result = mapper.buildExportData(test, emptyList())

        val stats = result.test.statistics
        assertEquals(2, stats.recordingCount)
        assertEquals(100, stats.totalHeartRateSamples)
        assertEquals(50, stats.totalRespirationSamples)
        assertEquals(80, stats.totalEsenseRrIntervalSamples)
    }

    @Test
    fun buildExportData_sudsEventsMapped() = runTest {
        val test = SessionEntity()
        fakeSudsEventDao.events.addAll(listOf(
            SudsEventEntity(sessionId = test.id, timestampMs = 1000L, value = 3),
            SudsEventEntity(sessionId = test.id, timestampMs = 2000L, value = 7)
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
        val test = SessionEntity()
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
        val test = SessionEntity()

        val result = mapper.buildExportData(test, emptyList())

        assertTrue(result.test.recordings.isEmpty())
    }

    @Test
    fun buildExportData_testFieldsMapped() = runTest {
        val test = SessionEntity(
            sessionIdentifier = "BMX-260413-141530",
            sessionNumber = "260413-141530",
            status = SessionStatus.COMPLETED,
            notes = "Patient improved"
        )

        val result = mapper.buildExportData(test, emptyList())

        assertEquals("BMX-260413-141530", result.test.id)
        assertEquals("260413-141530", result.test.sessionNumber)
        assertEquals("COMPLETED", result.test.status)
        assertEquals("Patient improved", result.test.notes)
        assertEquals("1.0.0", result.version)
    }

    // -- Helpers --

    private var nextRecordingId = 1L

    private fun recording(
        sessionId: Long = 1L,
        heartRateEnabled: Boolean = false,
        respirationEnabled: Boolean = false,
        heartRateSampleCount: Int = 0,
        respirationSampleCount: Int = 0,
        esenseRrIntervalSampleCount: Int = 0
    ): RecordingEntity {
        val id = nextRecordingId++
        return RecordingEntity(
            id = id,
            sessionId = sessionId,
            recordingIdentifier = "BMX-260413-141530-R${String.format("%02d", id)}",
            sequenceNumber = id.toInt(),
            startedAt = 1000000L,
            endedAt = 1060000L,
            durationMs = 60000L,
            status = RecordingStatus.COMPLETED,
            heartRateEnabled = heartRateEnabled,
            respirationEnabled = respirationEnabled,
            heartRateSampleCount = heartRateSampleCount,
            respirationSampleCount = respirationSampleCount,
            esenseRrIntervalSampleCount = esenseRrIntervalSampleCount
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

    private fun SessionEntity(
        sessionIdentifier: String = "BMX-260413-141530",
        sessionNumber: String = "260413-141530",
        status: SessionStatus = SessionStatus.COMPLETED,
        notes: String = "",
        recordingCount: Int = 0,
        totalHeartRateSampleCount: Int = 0,
        totalRespirationSampleCount: Int = 0,
        totalEsenseRrIntervalSampleCount: Int = 0
    ) = SessionEntity(
        id = 1L,
        sessionNumber = sessionNumber,
        sessionIdentifier = sessionIdentifier,
        createdAt = 1000000L,
        endedAt = 1060000L,
        durationMs = 60000L,
        status = status,
        notes = notes,
        recordingCount = recordingCount,
        totalHeartRateSampleCount = totalHeartRateSampleCount,
        totalRespirationSampleCount = totalRespirationSampleCount,
        totalEsenseRrIntervalSampleCount = totalEsenseRrIntervalSampleCount
    )
}
