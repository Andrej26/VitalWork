package com.biometrix.operator.data.db

class FakeBloodPressureEventDao : BloodPressureEventDao {

    val events = mutableListOf<BloodPressureEventEntity>()
    private var nextId = 1L

    override suspend fun insert(event: BloodPressureEventEntity) {
        events.add(event.copy(id = nextId++))
    }

    override suspend fun getByTestId(testId: Long): List<BloodPressureEventEntity> =
        events.filter { it.testId == testId }.sortedBy { it.timestampMs }

    override suspend fun countByTestId(testId: Long): Int =
        events.count { it.testId == testId }

    override suspend fun deleteByTestId(testId: Long) {
        events.removeAll { it.testId == testId }
    }
}
