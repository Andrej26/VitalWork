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
}
