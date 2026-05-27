package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.RecordingDao
import com.biometrix.operator.data.db.RecordingStatus
import com.biometrix.operator.data.db.SessionDao
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val recordingDao: RecordingDao
) {
    val allSessions: Flow<List<SessionEntity>> = sessionDao.getAllSessions()
    val activeSession: Flow<SessionEntity?> = sessionDao.getActiveSession()

    fun getSessionsByStatus(status: SessionStatus): Flow<List<SessionEntity>> =
        sessionDao.getSessionsByStatus(status)

    suspend fun getSessionById(id: Long): SessionEntity? =
        sessionDao.getSessionById(id)

    suspend fun createSession(): SessionEntity {
        val now = LocalDateTime.now()
        val sessionNumber = now.format(DateTimeFormatter.ofPattern("yyMMdd-HHmmss", Locale.US))
        val sessionIdentifier = "BMX-$sessionNumber"

        val session = SessionEntity(
            sessionNumber = sessionNumber,
            sessionIdentifier = sessionIdentifier,
            createdAt = System.currentTimeMillis(),
            status = SessionStatus.ACTIVE
        )
        val id = sessionDao.insert(session)
        return session.copy(id = id)
    }

    suspend fun createSessionIfNoneActive(): SessionEntity? {
        val now = LocalDateTime.now()
        val sessionNumber = now.format(DateTimeFormatter.ofPattern("yyMMdd-HHmmss", Locale.US))
        val sessionIdentifier = "BMX-$sessionNumber"

        val session = SessionEntity(
            sessionNumber = sessionNumber,
            sessionIdentifier = sessionIdentifier,
            createdAt = System.currentTimeMillis(),
            status = SessionStatus.ACTIVE
        )
        val id = sessionDao.insertIfNoneActive(session) ?: return null
        return session.copy(id = id)
    }

    suspend fun endSession(id: Long, recordingCount: Int) {
        val session = sessionDao.getSessionById(id) ?: return
        val durationMs = System.currentTimeMillis() - session.createdAt

        val completedRecordings = recordingDao.getRecordingsForTestOnce(id)
            .filter { it.status == RecordingStatus.COMPLETED }

        sessionDao.update(
            session.copy(
                endedAt = System.currentTimeMillis(),
                durationMs = durationMs,
                status = SessionStatus.COMPLETED,
                recordingCount = recordingCount,
                totalHeartRateSampleCount = completedRecordings.sumOf { it.heartRateSampleCount },
                totalRespirationSampleCount = completedRecordings.sumOf { it.respirationSampleCount },
                totalEsenseRrIntervalSampleCount = completedRecordings.sumOf { it.esenseRrIntervalSampleCount }
            )
        )
    }

    suspend fun updateNotes(id: Long, notes: String) {
        val session = sessionDao.getSessionById(id) ?: return
        sessionDao.update(session.copy(notes = notes))
    }

    suspend fun markExported(id: Long) {
        val session = sessionDao.getSessionById(id) ?: return
        sessionDao.update(session.copy(status = SessionStatus.EXPORTED))
    }

    suspend fun deleteSession(id: Long) {
        val session = sessionDao.getSessionById(id) ?: return
        sessionDao.delete(session)
    }

    suspend fun getCompletedRecordingCount(sessionId: Long): Int =
        recordingDao.getCompletedRecordingCount(sessionId)
}
