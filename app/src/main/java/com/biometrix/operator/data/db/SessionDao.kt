package com.biometrix.operator.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE status = :status ORDER BY startedAt DESC")
    fun getSessionsByStatus(status: SessionStatus): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE participantId = :participantId ORDER BY startedAt DESC")
    fun getSessionsForParticipant(participantId: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE status = 'ACTIVE' LIMIT 1")
    fun getActiveSession(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveSessionOnce(): SessionEntity?

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Transaction
    suspend fun insertIfNoneActive(session: SessionEntity): Long? {
        return if (getActiveSessionOnce() != null) null else insert(session)
    }

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)
}
