package com.vitalwork.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

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

    // --- Galaxy Watch authoritative-rebuild support (session-end, complete flush) ---
    // Watch types are stored as their enum names (Converters.fromSensorType); listing the literals
    // keeps these queries to watch rows only so eSense data is never touched.

    @Query(
        "SELECT COUNT(*) FROM sensor_samples WHERE scenarioId IN (:scenarioIds) " +
            "AND sensorType IN ('WATCH_HR', 'WATCH_IBI', 'WATCH_EDA')"
    )
    suspend fun countWatchSamplesForScenarios(scenarioIds: List<Long>): Int

    @Query(
        "DELETE FROM sensor_samples WHERE scenarioId IN (:scenarioIds) " +
            "AND sensorType IN ('WATCH_HR', 'WATCH_IBI', 'WATCH_EDA')"
    )
    suspend fun deleteWatchSamplesForScenarios(scenarioIds: List<Long>)

    /**
     * Authoritative rebuild of a session's Galaxy Watch samples from the (complete) durable flush:
     * drop the provisional live-written watch rows for [scenarioIds] and insert [samples], atomically.
     * eSense rows are untouched (watch types only). Caller must pass a non-empty [scenarioIds].
     */
    @Transaction
    suspend fun replaceWatchSamples(scenarioIds: List<Long>, samples: List<SensorSampleEntity>) {
        deleteWatchSamplesForScenarios(scenarioIds)
        insertAll(samples)
    }
}
