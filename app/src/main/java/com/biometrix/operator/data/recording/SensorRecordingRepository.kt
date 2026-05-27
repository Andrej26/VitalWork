package com.biometrix.operator.data.recording

import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.recording.model.RecordingMetadata
import kotlinx.coroutines.flow.StateFlow

interface SensorRecordingRepository {

    val recordingState: StateFlow<DataRecordingState>

    val recordingDurationMs: StateFlow<Long>

    val recordingMetadata: StateFlow<RecordingMetadata?>

    /**
     * Starts a new recording for the given test.
     * Sensor data is written directly to the database.
     *
     * @param testId The database ID of the test
     * @param sessionIdentifier The unique identifier (BMX-YYYY-NNN) for generating recording ID
     */
    suspend fun startRecording(sessionId: Long, sessionIdentifier: String)

    /**
     * Stops the current recording. Sample counts and metadata are finalized in the database.
     * Statistics are NOT computed here — use RecordingRepository.computeStatsForRecording() on demand.
     */
    suspend fun stopRecording()
}
