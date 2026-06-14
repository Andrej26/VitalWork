package com.vitalwork.app.data.db

class FakeSensorSampleDao : SensorSampleDao {

    val samples = mutableListOf<SensorSampleEntity>()

    override suspend fun insertAll(samples: List<SensorSampleEntity>) {
        this.samples.addAll(samples)
    }

    override suspend fun getSamplesForScenario(scenarioId: Long): List<SensorSampleEntity> =
        samples.filter { it.scenarioId == scenarioId }.sortedBy { it.timestampMs }

    override suspend fun getSampleCountBySensorType(scenarioId: Long, sensorType: SensorType): Int =
        samples.count { it.scenarioId == scenarioId && it.sensorType == sensorType }

    override suspend fun deleteAllForScenario(scenarioId: Long) {
        samples.removeAll { it.scenarioId == scenarioId }
    }

    private val watchTypes = setOf(SensorType.WATCH_HR, SensorType.WATCH_IBI, SensorType.WATCH_EDA)

    override suspend fun countWatchSamplesForScenarios(scenarioIds: List<Long>): Int =
        samples.count { it.scenarioId in scenarioIds && it.sensorType in watchTypes }

    override suspend fun deleteWatchSamplesForScenarios(scenarioIds: List<Long>) {
        samples.removeAll { it.scenarioId in scenarioIds && it.sensorType in watchTypes }
    }
}
