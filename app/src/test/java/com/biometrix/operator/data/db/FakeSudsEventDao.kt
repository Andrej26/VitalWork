package com.biometrix.operator.data.db

class FakeSudsEventDao : SudsEventDao {

    val events = mutableListOf<SudsEventEntity>()
    private var nextId = 1L

    override suspend fun insert(event: SudsEventEntity) {
        events.add(event.copy(id = nextId++))
    }

    override suspend fun getByTestId(testId: Long): List<SudsEventEntity> =
        events.filter { it.testId == testId }.sortedBy { it.timestampMs }

    override suspend fun deleteByTestId(testId: Long) {
        events.removeAll { it.testId == testId }
    }
}
