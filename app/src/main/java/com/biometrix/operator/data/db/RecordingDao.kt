package com.biometrix.operator.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings WHERE testId = :testId ORDER BY sequenceNumber ASC")
    fun getRecordingsForTest(testId: Long): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE testId = :testId ORDER BY sequenceNumber ASC")
    suspend fun getRecordingsForTestOnce(testId: Long): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE testId = :testId AND status = 'RECORDING' LIMIT 1")
    fun getActiveRecording(testId: Long): Flow<RecordingEntity?>

    @Query("SELECT MAX(sequenceNumber) FROM recordings WHERE testId = :testId")
    suspend fun getMaxSequenceNumber(testId: Long): Int?

    @Query("SELECT COUNT(*) FROM recordings WHERE testId = :testId AND status = 'COMPLETED'")
    suspend fun getCompletedRecordingCount(testId: Long): Int

    @Insert
    suspend fun insert(recording: RecordingEntity): Long

    @Update
    suspend fun update(recording: RecordingEntity)

    @Delete
    suspend fun delete(recording: RecordingEntity)
}
