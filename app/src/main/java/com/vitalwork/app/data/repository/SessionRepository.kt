package com.vitalwork.app.data.repository

import com.vitalwork.app.data.db.ScenarioDao
import com.vitalwork.app.data.db.SensorSampleDao
import com.vitalwork.app.data.db.SensorType
import com.vitalwork.app.data.db.SessionDao
import com.vitalwork.app.data.db.SessionEntity
import com.vitalwork.app.data.db.SessionStatus
import com.vitalwork.app.data.prefs.SettingsRepository
import com.vitalwork.app.data.time.TimeProvider
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val scenarioDao: ScenarioDao,
    private val sensorSampleDao: SensorSampleDao,
    private val settingsRepository: SettingsRepository,
    private val timeProvider: TimeProvider
) {
    val allSessions: Flow<List<SessionEntity>> = sessionDao.getAllSessions()
    val activeSession: Flow<SessionEntity?> = sessionDao.getActiveSession()

    fun getSessionsByStatus(status: SessionStatus): Flow<List<SessionEntity>> =
        sessionDao.getSessionsByStatus(status)

    fun getSessionsForParticipant(participantId: Long): Flow<List<SessionEntity>> =
        sessionDao.getSessionsForParticipant(participantId)

    suspend fun getSessionById(id: Long): SessionEntity? =
        sessionDao.getSessionById(id)

    suspend fun getActiveSessionOnce(): SessionEntity? =
        sessionDao.getActiveSessionOnce()

    suspend fun createSession(participantId: Long): SessionEntity {
        val session = buildNewSession(participantId)
        val id = sessionDao.insert(session)
        return session.copy(id = id)
    }

    suspend fun createSessionIfNoneActive(participantId: Long): SessionEntity? {
        val session = buildNewSession(participantId)
        val id = sessionDao.insertIfNoneActive(session) ?: return null
        return session.copy(id = id)
    }

    private fun buildNewSession(participantId: Long): SessionEntity {
        val now = LocalDateTime.now()
        val timestampToken = now.format(DateTimeFormatter.ofPattern("yyMMdd-HHmmss", Locale.US))
        val prefix = settingsRepository.getDevicePrefix()
        val sessionCode = "VW-$prefix-$timestampToken"
        return SessionEntity(
            participantId = participantId,
            sessionCode = sessionCode,
            startedAt = timeProvider.nowMs(),
            status = SessionStatus.ACTIVE
        )
    }

    suspend fun endSession(id: Long) {
        val session = sessionDao.getSessionById(id) ?: return
        val scenarios = scenarioDao.getScenariosForSessionOnce(id)
        val completedScenarios = scenarios.filter { it.endedAt != null }

        var hrCount = 0
        var respCount = 0
        var rrCount = 0
        var edaCount = 0
        var watchHrCount = 0
        var watchIbiCount = 0
        for (scenario in completedScenarios) {
            hrCount += sensorSampleDao.getSampleCountBySensorType(scenario.id, SensorType.ESENSE_HEART_RATE)
            respCount += sensorSampleDao.getSampleCountBySensorType(scenario.id, SensorType.RESPIRATION)
            rrCount += sensorSampleDao.getSampleCountBySensorType(scenario.id, SensorType.ESENSE_RR_INTERVAL)
            edaCount += sensorSampleDao.getSampleCountBySensorType(scenario.id, SensorType.WATCH_EDA)
            watchHrCount += sensorSampleDao.getSampleCountBySensorType(scenario.id, SensorType.WATCH_HR)
            watchIbiCount += sensorSampleDao.getSampleCountBySensorType(scenario.id, SensorType.WATCH_IBI)
        }

        sessionDao.update(
            session.copy(
                endedAt = timeProvider.nowMs(),
                status = SessionStatus.COMPLETED,
                scenarioCount = completedScenarios.size,
                hrSampleCount = hrCount,
                respirationSampleCount = respCount,
                rrIntervalSampleCount = rrCount,
                edaSampleCount = edaCount,
                watchHrSampleCount = watchHrCount,
                watchIbiSampleCount = watchIbiCount
            )
        )
    }

    suspend fun markUploaded(id: Long) {
        val session = sessionDao.getSessionById(id) ?: return
        sessionDao.update(session.copy(status = SessionStatus.UPLOADED))
    }

    suspend fun deleteSession(id: Long) {
        val session = sessionDao.getSessionById(id) ?: return
        sessionDao.delete(session)
    }
}
