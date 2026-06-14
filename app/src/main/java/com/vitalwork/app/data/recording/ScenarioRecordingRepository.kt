package com.vitalwork.app.data.recording

import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.recording.model.DataRecordingState
import com.vitalwork.app.data.recording.model.ScenarioMetadata
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
     * @param scenarioIdentifier Display/log identifier (e.g. "VW-260528-143012-A1"); not stored.
     */
    suspend fun startRecording(scenarioId: Long, scenarioIdentifier: String)

    /**
     * Stops the current recording. Flushes any buffered samples, ends the scenario row,
     * and resets state.
     */
    suspend fun stopRecording()

    /**
     * Begins continuous Galaxy Watch EDA capture for the whole session. The watch streams EDA
     * independently of scenarios (and buffers in Doze, delivering late), so its readings are
     * accumulated session-wide here — written live into the active scenario when one is recording,
     * and retained in a session buffer so late/out-of-window readings can be back-filled at end.
     * Idempotent. Call when the active-session screen opens.
     */
    fun startWatchEdaSession()

    /**
     * At session end, persists the session's Galaxy Watch samples (HR + IBI + EDA) into the given
     * scenarios by timestamp window. When the durable store flush completed in full it is treated as
     * **authoritative** — the provisional live rows are replaced by an exact rebuild from the flush
     * (lossless, no de-dup hazard); otherwise the best-effort live + partial data is kept.
     *
     * Must be called AFTER the last scenario has its `endedAt` set (i.e. after [stopRecording]) and
     * BEFORE the session's sample counts are rolled up. Clears the session buffer.
     *
     * @param scenarios the session's scenarios (with durable startedAt/endedAt).
     * @return a [WatchReconciliationReport] when the flush was complete and verifiable; `null` when it
     *   could not be verified (no/partial flush, or no scenarios — e.g. the session-abort path).
     */
    suspend fun drainAndFinalizeWatchEda(scenarios: List<ScenarioEntity>): WatchReconciliationReport?
}
