package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.RecordingDao
import com.biometrix.operator.data.db.RecordingEntity
import com.biometrix.operator.data.db.RecordingStatus
import com.biometrix.operator.data.db.SensorSampleDao
import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingRepository @Inject constructor(
    private val recordingDao: RecordingDao,
    private val sensorSampleDao: SensorSampleDao
) {

    fun getRecordingsForTest(testId: Long): Flow<List<RecordingEntity>> =
        recordingDao.getRecordingsForTest(testId)

    suspend fun getRecordingsForTestOnce(testId: Long): List<RecordingEntity> =
        recordingDao.getRecordingsForTestOnce(testId)

    fun getActiveRecording(testId: Long): Flow<RecordingEntity?> =
        recordingDao.getActiveRecording(testId)

    suspend fun getRecordingById(id: Long): RecordingEntity? =
        recordingDao.getRecordingById(id)

    /**
     * Creates a new recording for the given test.
     * Generates recordingIdentifier in format: BMX-YYYY-NNN-RNN
     */
    suspend fun createRecording(
        testId: Long,
        testIdentifier: String,
        heartRateEnabled: Boolean,
        respirationEnabled: Boolean
    ): RecordingEntity {
        val maxSeq = recordingDao.getMaxSequenceNumber(testId) ?: 0
        val sequenceNumber = maxSeq + 1
        val recordingIdentifier = String.format(
            Locale.US,
            "%s-R%02d",
            testIdentifier,
            sequenceNumber
        )

        val recording = RecordingEntity(
            testId = testId,
            recordingIdentifier = recordingIdentifier,
            sequenceNumber = sequenceNumber,
            startedAt = System.currentTimeMillis(),
            status = RecordingStatus.RECORDING,
            heartRateEnabled = heartRateEnabled,
            respirationEnabled = respirationEnabled
        )

        val id = recordingDao.insert(recording)
        return recording.copy(id = id)
    }

    /**
     * Adds multiple sensor samples in a batch.
     */
    suspend fun addSamples(samples: List<SensorSampleEntity>) {
        sensorSampleDao.insertAll(samples)
    }

    /**
     * Completes a recording: updates status, duration, and sample counts.
     */
    suspend fun completeRecording(recordingId: Long) {
        val recording = recordingDao.getRecordingById(recordingId) ?: return
        val endTime = System.currentTimeMillis()
        val duration = endTime - recording.startedAt

        val hrCount = sensorSampleDao.getSampleCountBySensorType(recordingId, SensorType.HEART_RATE)
        val respCount = sensorSampleDao.getSampleCountBySensorType(recordingId, SensorType.RESPIRATION)
        val esenseRrCount = sensorSampleDao.getSampleCountBySensorType(recordingId, SensorType.ESENSE_RR_INTERVAL)

        recordingDao.update(
            recording.copy(
                endedAt = endTime,
                durationMs = duration,
                status = RecordingStatus.COMPLETED,
                heartRateSampleCount = hrCount,
                respirationSampleCount = respCount,
                esenseRrIntervalSampleCount = esenseRrCount
            )
        )
    }

    /**
     * Gets all samples for a recording, ordered by timestamp.
     */
    suspend fun getSamplesForRecording(recordingId: Long) =
        sensorSampleDao.getSamplesForRecording(recordingId)
}
