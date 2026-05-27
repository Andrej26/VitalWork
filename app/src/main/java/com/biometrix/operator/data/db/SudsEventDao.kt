package com.biometrix.operator.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SudsEventDao {
    @Insert
    suspend fun insert(event: SudsEventEntity)

    @Query("SELECT * FROM suds_events WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getByTestId(sessionId: Long): List<SudsEventEntity>

    @Query("DELETE FROM suds_events WHERE sessionId = :sessionId")
    suspend fun deleteByTestId(sessionId: Long)
}
