package com.biometrix.operator.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BloodPressureEventDao {

    @Insert
    suspend fun insert(event: BloodPressureEventEntity)

    @Query("SELECT * FROM blood_pressure_events WHERE testId = :testId ORDER BY timestampMs ASC")
    suspend fun getByTestId(testId: Long): List<BloodPressureEventEntity>

    @Query("SELECT COUNT(*) FROM blood_pressure_events WHERE testId = :testId")
    suspend fun countByTestId(testId: Long): Int

    @Query("DELETE FROM blood_pressure_events WHERE testId = :testId")
    suspend fun deleteByTestId(testId: Long)
}
