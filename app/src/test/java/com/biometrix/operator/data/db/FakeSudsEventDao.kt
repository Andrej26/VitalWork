package com.biometrix.operator.data.db

class FakeSudsEventDao : SudsEventDao {

    val events = mutableListOf<SudsEventEntity>()
    private var nextId = 1L

    override suspend fun insert(event: SudsEventEntity) {
        events.add(event.copy(id = nextId++))
    }

    override suspend fun getByTestId(sessionId: Long): List<SudsEventEntity> =
        events.filter { it.sessionId == sessionId }.sortedBy { it.timestampMs }

    override suspend fun deleteByTestId(sessionId: Long) {
        events.removeAll { it.sessionId == sessionId }
    }
}
