package com.biometrix.operator.data.recording

import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.recording.model.ScenarioMetadata
import kotlinx.coroutines.flow.StateFlow

interface ScenarioRecordingRepository {

    val recordingState: StateFlow<DataRecordingState>

    val recordingDurationMs: StateFlow<Long>

    val recordingMetadata: StateFlow<ScenarioMetadata?>

    /**
     * Begins sensor sample capture for the given scenario. The scenario row must already
     * exist (created by the caller via [ScenarioRepository.createScenario]). Samples are
     * buffered and persisted with `scenarioId = [scenarioId]`.
     *
     * @param scenarioId The database ID of the scenario.
     * @param scenarioIdentifier Display/log identifier (e.g. "BMX-260528-143012-A1"); not stored.
     */
    suspend fun startRecording(scenarioId: Long, scenarioIdentifier: String)

    /**
     * Stops the current recording. Flushes any buffered samples, ends the scenario row,
     * and resets state.
     */
    suspend fun stopRecording()
}
