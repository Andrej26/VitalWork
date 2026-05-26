package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.SudsEventDao
import com.biometrix.operator.data.db.SudsEventEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SudsRepository @Inject constructor(
    private val sudsEventDao: SudsEventDao
) {
    suspend fun saveEvent(testId: Long, value: Int) {
        sudsEventDao.insert(
            SudsEventEntity(
                testId = testId,
                timestampMs = System.currentTimeMillis(),
                value = value
            )
        )
    }

    suspend fun getEventsForTest(testId: Long): List<SudsEventEntity> =
        sudsEventDao.getByTestId(testId)
}
