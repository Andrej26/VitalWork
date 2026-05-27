package com.biometrix.operator.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeSessionDao : SessionDao {

    val tests = mutableListOf<SessionEntity>()
    private var nextId = 1L

    override fun getAllSessions(): Flow<List<SessionEntity>> = flowOf(tests.toList())

    override fun getSessionsByStatus(status: SessionStatus): Flow<List<SessionEntity>> =
        flowOf(tests.filter { it.status == status })

    override suspend fun getSessionById(id: Long): SessionEntity? =
        tests.find { it.id == id }

    override fun getActiveSession(): Flow<SessionEntity?> =
        flowOf(tests.firstOrNull { it.status == SessionStatus.ACTIVE })

    override suspend fun getActiveSessionOnce(): SessionEntity? =
        tests.firstOrNull { it.status == SessionStatus.ACTIVE }

    override suspend fun insert(test: SessionEntity): Long {
        val id = nextId++
        tests.add(test.copy(id = id))
        return id
    }

    override suspend fun insertIfNoneActive(test: SessionEntity): Long? =
        if (getActiveSessionOnce() != null) null else insert(test)

    override suspend fun update(test: SessionEntity) {
        val index = tests.indexOfFirst { it.id == test.id }
        if (index >= 0) tests[index] = test
    }

    override suspend fun delete(test: SessionEntity) {
        tests.removeAll { it.id == test.id }
    }
}
