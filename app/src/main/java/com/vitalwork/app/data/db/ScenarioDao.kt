package com.vitalwork.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScenarioDao {

    @Query("SELECT * FROM scenarios WHERE sessionId = :sessionId ORDER BY startedAt ASC")
    fun getScenariosForSession(sessionId: Long): Flow<List<ScenarioEntity>>

    @Query("SELECT * FROM scenarios WHERE sessionId = :sessionId ORDER BY startedAt ASC")
    suspend fun getScenariosForSessionOnce(sessionId: Long): List<ScenarioEntity>

    @Query("SELECT * FROM scenarios WHERE id = :id")
    suspend fun getScenarioById(id: Long): ScenarioEntity?

    @Query("SELECT * FROM scenarios WHERE sessionId = :sessionId AND endedAt IS NULL LIMIT 1")
    fun getActiveScenario(sessionId: Long): Flow<ScenarioEntity?>

    @Query("SELECT * FROM scenarios WHERE sessionId = :sessionId AND endedAt IS NULL LIMIT 1")
    suspend fun getActiveScenarioOnce(sessionId: Long): ScenarioEntity?

    @Query("SELECT COUNT(*) FROM scenarios WHERE sessionId = :sessionId AND endedAt IS NOT NULL")
    suspend fun getCompletedScenarioCount(sessionId: Long): Int

    @Insert
    suspend fun insert(scenario: ScenarioEntity): Long

    @Update
    suspend fun update(scenario: ScenarioEntity)

    @Delete
    suspend fun delete(scenario: ScenarioEntity)
}
