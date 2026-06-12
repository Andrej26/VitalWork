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

    /**
     * Creates a new scenario for the given session. `scenarioCategory` is derived from
     * `scenarioCode` and stored alongside it for fast category-level queries.
     */
    suspend fun createScenario(
        sessionId: Long,
        scenarioCode: ScenarioCode
    ): ScenarioEntity {
        val scenario = ScenarioEntity(
            sessionId = sessionId,
            scenarioCode = scenarioCode,
            scenarioCategory = scenarioCode.category,
            startedAt = timeProvider.nowMs()
        )
        val id = scenarioDao.insert(scenario)
        return scenario.copy(id = id)
    }

    /** Records the moment the critical event fired inside a scenario. */
    suspend fun setEventTimestamp(scenarioId: Long, eventTimestampMs: Long) {
        val scenario = scenarioDao.getScenarioById(scenarioId) ?: return
        scenarioDao.update(scenario.copy(eventTimestampMs = eventTimestampMs))
    }

    /** Records the moment the user reacted via the VR controller. */
    suspend fun setReactionTimestamp(scenarioId: Long, reactionTimestampMs: Long) {
        val scenario = scenarioDao.getScenarioById(scenarioId) ?: return
        scenarioDao.update(scenario.copy(reactionTimestampMs = reactionTimestampMs))
    }

    /** Marks the scenario as ended (sets endedAt). Sample counts roll up at session end. */
    suspend fun endScenario(scenarioId: Long) {
        val scenario = scenarioDao.getScenarioById(scenarioId) ?: return
        if (scenario.endedAt != null) return
        scenarioDao.update(scenario.copy(endedAt = timeProvider.nowMs()))
    }

    /** Inserts a batch of sensor samples. Called by the recording layer. */
    suspend fun addSamples(samples: List<SensorSampleEntity>) {
        sensorSampleDao.insertAll(samples)
    }

    suspend fun getSamplesForScenario(scenarioId: Long): List<SensorSampleEntity> =
        sensorSampleDao.getSamplesForScenario(scenarioId)

    suspend fun getSampleCountBySensorType(scenarioId: Long, sensorType: SensorType): Int =
        sensorSampleDao.getSampleCountBySensorType(scenarioId, sensorType)
}
