package com.biometrix.operator.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SensorSampleDao {

    @Insert
    suspend fun insertAll(samples: List<SensorSampleEntity>)

    @Query("SELECT * FROM sensor_samples WHERE scenarioId = :scenarioId ORDER BY timestampMs ASC")
    suspend fun getSamplesForScenario(scenarioId: Long): List<SensorSampleEntity>

    @Query("SELECT COUNT(*) FROM sensor_samples WHERE scenarioId = :scenarioId AND sensorType = :sensorType")
    suspend fun getSampleCountBySensorType(scenarioId: Long, sensorType: SensorType): Int

    @Query("DELETE FROM sensor_samples WHERE scenarioId = :scenarioId")
    suspend fun deleteAllForScenario(scenarioId: Long)
}
