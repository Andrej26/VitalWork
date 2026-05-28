package com.biometrix.operator.data.recording

import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.recording.model.ScenarioMetadata
import kotlinx.coroutines.flow.MutableStateFlow

class FakeScenarioRecordingRepository : ScenarioRecordingRepository {

    override val recordingState = MutableStateFlow(DataRecordingState.IDLE)
    override val recordingDurationMs = MutableStateFlow(0L)
    override val recordingMetadata = MutableStateFlow<ScenarioMetadata?>(null)

    var startRecordingCallCount = 0
        private set
    var stopRecordingCallCount = 0
        private set
    var lastStartScenarioId: Long? = null
        private set
    var lastStartScenarioIdentifier: String? = null
        private set

    override suspend fun startRecording(scenarioId: Long, scenarioIdentifier: String) {
        startRecordingCallCount++
        lastStartScenarioId = scenarioId
        lastStartScenarioIdentifier = scenarioIdentifier
        recordingState.value = DataRecordingState.RECORDING
        recordingMetadata.value = ScenarioMetadata(
            scenarioId = scenarioId,
            scenarioIdentifier = scenarioIdentifier,
            startTimestampMs = System.currentTimeMillis()
        )
    }

    override suspend fun stopRecording() {
        stopRecordingCallCount++
        recordingState.value = DataRecordingState.IDLE
        recordingMetadata.value = null
    }
}
