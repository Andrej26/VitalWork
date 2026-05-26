package com.biometrix.operator.data.recording

import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.recording.model.RecordingMetadata
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSensorRecordingRepository : SensorRecordingRepository {

    override val recordingState = MutableStateFlow(DataRecordingState.IDLE)
    override val recordingDurationMs = MutableStateFlow(0L)
    override val recordingMetadata = MutableStateFlow<RecordingMetadata?>(null)

    var startRecordingCallCount = 0
        private set
    var stopRecordingCallCount = 0
        private set
    var lastStartTestId: Long? = null
        private set
    var lastStartTestIdentifier: String? = null
        private set

    override suspend fun startRecording(testId: Long, testIdentifier: String) {
        startRecordingCallCount++
        lastStartTestId = testId
        lastStartTestIdentifier = testIdentifier
        recordingState.value = DataRecordingState.RECORDING
        recordingMetadata.value = RecordingMetadata(
            recordingId = 1L,
            recordingIdentifier = "$testIdentifier-R01",
            startTimestampMs = System.currentTimeMillis()
        )
    }

    override suspend fun stopRecording() {
        stopRecordingCallCount++
        recordingState.value = DataRecordingState.IDLE
        recordingMetadata.value = null
    }
}
