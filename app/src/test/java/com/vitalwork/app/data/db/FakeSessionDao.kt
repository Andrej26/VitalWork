package com.vitalwork.app.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeSessionDao : SessionDao {

    val sessions = mutableListOf<SessionEntity>()
    private var nextId = 1L

    override fun getAllSessions(): Flow<List<SessionEntity>> = flowOf(sessions.toList())

    override fun getSessionsByStatus(status: SessionStatus): Flow<List<SessionEntity>> =
        flowOf(sessions.filter { it.status == status })

    override fun getSessionsForParticipant(participantId: Long): Flow<List<SessionEntity>> =
        flowOf(sessions.filter { it.participantId == participantId })

    override suspend fun getSessionById(id: Long): SessionEntity? =
        sessions.find { it.id == id }

    override fun getActiveSession(): Flow<SessionEntity?> =
        flowOf(sessions.firstOrNull { it.status == SessionStatus.ACTIVE })

    override suspend fun getActiveSessionOnce(): SessionEntity? =
        sessions.firstOrNull { it.status == SessionStatus.ACTIVE }

    override suspend fun insert(session: SessionEntity): Long {
        val id = nextId++
        sessions.add(session.copy(id = id))
        return id
    }

    override suspend fun insertIfNoneActive(session: SessionEntity): Long? =
        if (getActiveSessionOnce() != null) null else insert(session)

    override suspend fun update(session: SessionEntity) {
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session
    }

    override suspend fun delete(session: SessionEntity) {
        sessions.removeAll { it.id == session.id }
    }
}
