package com.biometrix.operator.data.db

class FakeSensorSampleDao : SensorSampleDao {

    val samples = mutableListOf<SensorSampleEntity>()

    override suspend fun insertAll(samples: List<SensorSampleEntity>) {
        this.samples.addAll(samples)
    }

    override suspend fun getSamplesForRecording(recordingId: Long): List<SensorSampleEntity> =
        samples.filter { it.recordingId == recordingId }.sortedBy { it.timestampMs }

    override suspend fun getSampleCountBySensorType(recordingId: Long, sensorType: SensorType): Int =
        samples.count { it.recordingId == recordingId && it.sensorType == sensorType }

    override suspend fun deleteAllForRecording(recordingId: Long) {
        samples.removeAll { it.recordingId == recordingId }
    }
}
