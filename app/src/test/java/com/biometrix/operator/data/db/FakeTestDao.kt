package com.biometrix.operator.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeTestDao : TestDao {

    val tests = mutableListOf<TestEntity>()
    private var nextId = 1L

    override fun getAllTests(): Flow<List<TestEntity>> = flowOf(tests.toList())

    override fun getTestsByStatus(status: TestStatus): Flow<List<TestEntity>> =
        flowOf(tests.filter { it.status == status })

    override suspend fun getTestById(id: Long): TestEntity? =
        tests.find { it.id == id }

    override fun getActiveTest(): Flow<TestEntity?> =
        flowOf(tests.firstOrNull { it.status == TestStatus.ACTIVE })

    override suspend fun insert(test: TestEntity): Long {
        val id = nextId++
        tests.add(test.copy(id = id))
        return id
    }

    override suspend fun update(test: TestEntity) {
        val index = tests.indexOfFirst { it.id == test.id }
        if (index >= 0) tests[index] = test
    }

    override suspend fun delete(test: TestEntity) {
        tests.removeAll { it.id == test.id }
    }
}
