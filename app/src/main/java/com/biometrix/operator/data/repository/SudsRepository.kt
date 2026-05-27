package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.SudsEventDao
import com.biometrix.operator.data.db.SudsEventEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SudsRepository @Inject constructor(
    private val sudsEventDao: SudsEventDao
) {
    suspend fun saveEvent(sessionId: Long, value: Int) {
        sudsEventDao.insert(
            SudsEventEntity(
                sessionId = sessionId,
                timestampMs = System.currentTimeMillis(),
                value = value
            )
        )
    }

    suspend fun getEventsForTest(sessionId: Long): List<SudsEventEntity> =
        sudsEventDao.getByTestId(sessionId)
}
