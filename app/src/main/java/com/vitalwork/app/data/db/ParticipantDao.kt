package com.vitalwork.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ParticipantDao {

    @Query("SELECT * FROM participants ORDER BY id DESC")
    fun getAllParticipants(): Flow<List<ParticipantEntity>>

    @Query("SELECT * FROM participants WHERE id = :id")
    suspend fun getParticipantById(id: Long): ParticipantEntity?

    @Query("SELECT * FROM participants WHERE participantCode = :code LIMIT 1")
    suspend fun getParticipantByCode(code: String): ParticipantEntity?

    @Query("SELECT COUNT(*) FROM participants")
    suspend fun getParticipantCount(): Int

    @Query("SELECT COUNT(*) FROM participants WHERE participantCode LIKE :prefix || '-%'")
    suspend fun getParticipantCountByPrefix(prefix: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(participant: ParticipantEntity): Long

    @Update
    suspend fun update(participant: ParticipantEntity)

    @Delete
    suspend fun delete(participant: ParticipantEntity)
}
