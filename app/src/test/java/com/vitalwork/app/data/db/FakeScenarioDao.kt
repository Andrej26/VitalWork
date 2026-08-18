package com.vitalwork.app.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeScenarioDao : ScenarioDao {

    val scenarios = mutableListOf<ScenarioEntity>()
    private var nextId = 1L

    override fun getScenariosForSession(sessionId: Long): Flow<List<ScenarioEntity>> =
        flowOf(scenarios.filter { it.sessionId == sessionId }.sortedBy { it.startedAt })

    override suspend fun getScenariosForSessionOnce(sessionId: Long): List<ScenarioEntity> =
        scenarios.filter { it.sessionId == sessionId }.sortedBy { it.startedAt }

    override suspend fun getScenarioById(id: Long): ScenarioEntity? =
        scenarios.find { it.id == id }

    override suspend fun insert(scenario: ScenarioEntity): Long {
        val id = nextId++
        scenarios.add(scenario.copy(id = id))
        return id
    }

    override suspend fun update(scenario: ScenarioEntity) {
        val index = scenarios.indexOfFirst { it.id == scenario.id }
        if (index >= 0) scenarios[index] = scenario
    }

    override suspend fun delete(scenario: ScenarioEntity) {
        scenarios.removeAll { it.id == scenario.id }
    }
}
