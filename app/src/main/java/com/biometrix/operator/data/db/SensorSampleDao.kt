package com.biometrix.operator.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SensorSampleDao {

    @Insert
    suspend fun insertAll(samples: List<SensorSampleEntity>)

    @Query("SELECT * FROM sensor_samples WHERE recordingId = :recordingId ORDER BY timestampMs ASC")
    suspend fun getSamplesForRecording(recordingId: Long): List<SensorSampleEntity>

    @Query("SELECT COUNT(*) FROM sensor_samples WHERE recordingId = :recordingId AND sensorType = :sensorType")
    suspend fun getSampleCountBySensorType(recordingId: Long, sensorType: SensorType): Int

    @Query("DELETE FROM sensor_samples WHERE recordingId = :recordingId")
    suspend fun deleteAllForRecording(recordingId: Long)
}
