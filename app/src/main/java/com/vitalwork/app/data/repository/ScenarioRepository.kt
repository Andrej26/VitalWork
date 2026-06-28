package com.vitalwork.app.data.repository

import com.vitalwork.app.data.db.ScenarioCode
import com.vitalwork.app.data.db.ScenarioDao
import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SensorSampleDao
import com.vitalwork.app.data.db.SensorSampleEntity
import com.vitalwork.app.data.db.SensorType
import com.vitalwork.app.data.time.TimeProvider
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScenarioRepository @Inject constructor(
    private val scenarioDao: ScenarioDao,
    private val sensorSampleDao: SensorSampleDao,
    private val timeProvider: TimeProvider
) {

    fun getScenariosForSession(sessionId: Long): Flow<List<ScenarioEntity>> =
        scenarioDao.getScenariosForSession(sessionId)

    suspend fun getScenariosForSessionOnce(sessionId: Long): List<ScenarioEntity> =
        scenarioDao.getScenariosForSessionOnce(sessionId)

    fun getActiveScenario(sessionId: Long): Flow<ScenarioEntity?> =
        scenarioDao.getActiveScenario(sessionId)

    suspend fun getScenarioById(id: Long): ScenarioEntity? =
        scenarioDao.getScenarioById(id)

    suspend fun getCompletedScenarioCount(sessionId: Long): Int =
        scenarioDao.getCompletedScenarioCount(sessionId)

    /** Creates a new scenario for the given session. */
    suspend fun createScenario(
        sessionId: Long,
        scenarioCode: ScenarioCode
    ): ScenarioEntity {
        val scenario = ScenarioEntity(
            sessionId = sessionId,
            scenarioCode = scenarioCode,
            startedAt = timeProvider.nowMs()
        )
        val id = scenarioDao.insert(scenario)
        return scenario.copy(id = id)
    }

    /** Marks the scenario as ended (sets endedAt). Sample counts roll up at session end. */
    suspend fun endScenario(scenarioId: Long) {
        val scenario = scenarioDao.getScenarioById(scenarioId) ?: return
        if (scenario.endedAt != null) return
        scenarioDao.update(scenario.copy(endedAt = timeProvider.nowMs()))
    }

    /**
     * Closes any scenario in the session left open (`endedAt == null`) — e.g. after the app process
     * was killed mid-recording (background / OEM battery management), so [endScenario] never ran.
     *
     * Each open scenario is closed to its **last persisted sample's `timestampMs`** (the sample list
     * is already sorted ascending), falling back to `startedAt` when it has no samples. Last-sample —
     * not `nowMs()` — is deliberate: `now()` would stretch the window across the gap and overlap the
     * next scenario, mis-attributing the watch drain. The last-sample bound is tight and provably
     * non-overlapping (recording is single-active, so the next scenario only starts after this one's
     * samples stop), which lets [com.vitalwork.app.data.recording.WatchSessionDrainer] file the
     * session's stored EDA/HR/IBI into the recovered windows instead of dropping them.
     *
     * Idempotent (already-closed scenarios are skipped). Returns the number of scenarios closed.
     */
    suspend fun closeDanglingScenarios(sessionId: Long): Int {
        val open = scenarioDao.getScenariosForSessionOnce(sessionId).filter { it.endedAt == null }
        for (scenario in open) {
            val lastSampleMs = sensorSampleDao.getSamplesForScenario(scenario.id)
                .lastOrNull()?.timestampMs
            scenarioDao.update(scenario.copy(endedAt = lastSampleMs ?: scenario.startedAt))
        }
        return open.size
    }

    /** Inserts a batch of sensor samples. Called by the recording layer. */
    suspend fun addSamples(samples: List<SensorSampleEntity>) {
        sensorSampleDao.insertAll(samples)
    }

    /** Total Galaxy Watch samples (HR + IBI + EDA) across a session's scenarios. */
    suspend fun countWatchSamplesForScenarios(scenarioIds: List<Long>): Int =
        sensorSampleDao.countWatchSamplesForScenarios(scenarioIds)

    /**
     * Authoritative rebuild of watch samples from a complete flush: atomically delete the provisional
     * live-written watch rows for [scenarioIds] and insert [samples]. eSense rows untouched.
     */
    suspend fun replaceWatchSamples(scenarioIds: List<Long>, samples: List<SensorSampleEntity>) =
        sensorSampleDao.replaceWatchSamples(scenarioIds, samples)

    suspend fun getSamplesForScenario(scenarioId: Long): List<SensorSampleEntity> =
        sensorSampleDao.getSamplesForScenario(scenarioId)

    suspend fun getSampleCountBySensorType(scenarioId: Long, sensorType: SensorType): Int =
        sensorSampleDao.getSampleCountBySensorType(scenarioId, sensorType)
}
