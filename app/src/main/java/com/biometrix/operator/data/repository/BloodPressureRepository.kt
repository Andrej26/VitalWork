package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.BloodPressureEventDao
import com.biometrix.operator.data.db.BloodPressureEventEntity
import com.biometrix.operator.data.model.BloodPressureReading
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BloodPressureRepository @Inject constructor(
    private val dao: BloodPressureEventDao
) {
    suspend fun saveReading(testId: Long, reading: BloodPressureReading, testCreatedAt: Long) {
        val now = System.currentTimeMillis()
        dao.insert(
            BloodPressureEventEntity(
                testId = testId,
                timestampMs = now,
                elapsedTestMs = now - testCreatedAt,
                systolicMmHg = reading.systolicMmHg,
                diastolicMmHg = reading.diastolicMmHg,
                meanArterialMmHg = reading.meanArterialMmHg,
                pulseRateBpm = reading.pulseRateBpm
            )
        )
    }

    suspend fun getEventsForTest(testId: Long): List<BloodPressureEventEntity> =
        dao.getByTestId(testId)

    suspend fun countForTest(testId: Long): Int =
        dao.countByTestId(testId)
}
