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

    @Insert
    suspend fun insert(scenario: ScenarioEntity): Long

    @Update
    suspend fun update(scenario: ScenarioEntity)

    @Delete
    suspend fun delete(scenario: ScenarioEntity)
}
