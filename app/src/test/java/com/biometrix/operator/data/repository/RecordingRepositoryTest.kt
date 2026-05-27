package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.FakeRecordingDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.RecordingStatus
import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingRepositoryTest {

    private lateinit var fakeRecordingDao: FakeRecordingDao
    private lateinit var fakeSensorSampleDao: FakeSensorSampleDao
    private lateinit var repository: RecordingRepository

    @Before
    fun setUp() {
        fakeRecordingDao = FakeRecordingDao()
        fakeSensorSampleDao = FakeSensorSampleDao()
        repository = RecordingRepository(fakeRecordingDao, fakeSensorSampleDao)
    }

    @Test
    fun createRecording_firstRecording_identifierEndsWithR01() = runTest {
        val recording = repository.createRecording(
            sessionId = 1L,
            sessionIdentifier = "BMX-260413-141530",
            heartRateEnabled = true,
            respirationEnabled = false
        )

        assertEquals("BMX-260413-141530-R01", recording.recordingIdentifier)
        assertEquals(1, recording.sequenceNumber)
        assertEquals(RecordingStatus.RECORDING, recording.status)
        assertEquals(1L, recording.sessionId)
    }

    @Test
    fun createRecording_secondRecording_identifierEndsWithR02() = runTest {
        repository.createRecording(
            sessionId = 1L,
            sessionIdentifier = "BMX-260413-141530",
            heartRateEnabled = true,
            respirationEnabled = false
        )
        val second = repository.createRecording(
            sessionId = 1L,
            sessionIdentifier = "BMX-260413-141530",
            heartRateEnabled = true,
            respirationEnabled = false
        )

        assertEquals("BMX-260413-141530-R02", second.recordingIdentifier)
        assertEquals(2, second.sequenceNumber)
    }

    @Test
    fun createRecording_sensorFlags_persisted() = runTest {
        val recording = repository.createRecording(
            sessionId = 1L,
            sessionIdentifier = "BMX-260413-141530",
            heartRateEnabled = true,
            respirationEnabled = true
        )

        assertTrue(recording.heartRateEnabled)
        assertTrue(recording.respirationEnabled)
    }

    @Test
    fun completeRecording_countsAllSensorTypes() = runTest {
        val recording = repository.createRecording(
            sessionId = 1L,
            sessionIdentifier = "BMX-260413-141530",
            heartRateEnabled = true,
            respirationEnabled = true
        )

        fakeSensorSampleDao.samples.addAll(listOf(
            sample(recording.id, SensorType.HEART_RATE, count = 3),
            sample(recording.id, SensorType.RESPIRATION, count = 5),
            sample(recording.id, SensorType.ESENSE_RR_INTERVAL, count = 17)
        ).flatten())

        repository.completeRecording(recording.id)

        val updated = fakeRecordingDao.getRecordingById(recording.id)!!
        assertEquals(3, updated.heartRateSampleCount)
        assertEquals(5, updated.respirationSampleCount)
        assertEquals(17, updated.esenseRrIntervalSampleCount)
    }

    @Test
    fun completeRecording_setsDurationAndStatus() = runTest {
        val recording = repository.createRecording(
            sessionId = 1L,
            sessionIdentifier = "BMX-260413-141530",
            heartRateEnabled = true,
            respirationEnabled = false
        )

        repository.completeRecording(recording.id)

        val updated = fakeRecordingDao.getRecordingById(recording.id)!!
        assertEquals(RecordingStatus.COMPLETED, updated.status)
        assertNotNull(updated.endedAt)
        assertTrue(updated.durationMs >= 0)
    }

    @Test
    fun completeRecording_nonExistentId_doesNothing() = runTest {
        repository.completeRecording(999L)

        assertTrue(fakeRecordingDao.recordings.isEmpty())
    }

    @Test
    fun addSamples_persistsAllSamples() = runTest {
        val samples = listOf(
            SensorSampleEntity(
                recordingId = 1L, timestampMs = 1000L, elapsedMs = 0L,
                sensorType = SensorType.HEART_RATE, value = 72f
            ),
            SensorSampleEntity(
                recordingId = 1L, timestampMs = 2000L, elapsedMs = 1000L,
                sensorType = SensorType.HEART_RATE, value = 75f
            )
        )

        repository.addSamples(samples)

        val retrieved = repository.getSamplesForRecording(1L)
        assertEquals(2, retrieved.size)
        assertEquals(72f, retrieved[0].value, 0f)
        assertEquals(75f, retrieved[1].value, 0f)
    }

    @Test
    fun getRecordingById_returnsNullForMissing() = runTest {
        assertNull(repository.getRecordingById(999L))
    }

    /** Creates [count] samples of the given sensor type for the given recording. */
    private fun sample(recordingId: Long, sensorType: SensorType, count: Int): List<SensorSampleEntity> =
        (1..count).map { i ->
            SensorSampleEntity(
                recordingId = recordingId,
                timestampMs = System.currentTimeMillis() + i,
                elapsedMs = i * 1000L,
                sensorType = sensorType,
                value = i.toFloat()
            )
        }
}
