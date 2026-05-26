package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.RecordingDao
import com.biometrix.operator.data.db.RecordingStatus
import com.biometrix.operator.data.db.TestDao
import com.biometrix.operator.data.db.TestEntity
import com.biometrix.operator.data.db.TestStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TestRepository @Inject constructor(
    private val testDao: TestDao,
    private val recordingDao: RecordingDao
) {
    val allTests: Flow<List<TestEntity>> = testDao.getAllTests()
    val activeTest: Flow<TestEntity?> = testDao.getActiveTest()

    fun getTestsByStatus(status: TestStatus): Flow<List<TestEntity>> =
        testDao.getTestsByStatus(status)

    suspend fun getTestById(id: Long): TestEntity? =
        testDao.getTestById(id)

    /**
     * Creates a new test with timestamp-based identifiers in format:
     * - testNumber: yyMMdd-HHmmss
     * - testIdentifier: BMX-yyMMdd-HHmmss (for external matching)
     */
    suspend fun createTest(): TestEntity {
        val now = LocalDateTime.now()
        val testNumber = now.format(DateTimeFormatter.ofPattern("yyMMdd-HHmmss", Locale.US))
        val testIdentifier = "BMX-$testNumber"

        val test = TestEntity(
            testNumber = testNumber,
            testIdentifier = testIdentifier,
            createdAt = System.currentTimeMillis(),
            status = TestStatus.ACTIVE
        )
        val id = testDao.insert(test)
        return test.copy(id = id)
    }

    /**
     * Ends a test and stores metadata.
     * Statistics are NOT stored — compute them on demand via RecordingRepository.
     */
    suspend fun endTest(id: Long, recordingCount: Int) {
        val test = testDao.getTestById(id) ?: return
        val durationMs = System.currentTimeMillis() - test.createdAt

        val completedRecordings = recordingDao.getRecordingsForTestOnce(id)
            .filter { it.status == RecordingStatus.COMPLETED }

        testDao.update(
            test.copy(
                endedAt = System.currentTimeMillis(),
                durationMs = durationMs,
                status = TestStatus.COMPLETED,
                recordingCount = recordingCount,
                totalHeartRateSampleCount = completedRecordings.sumOf { it.heartRateSampleCount },
                totalRespirationSampleCount = completedRecordings.sumOf { it.respirationSampleCount },
                totalFibionHeartRateSampleCount = completedRecordings.sumOf { it.fibionHeartRateSampleCount },
                totalFibionEcgSampleCount = completedRecordings.sumOf { it.fibionEcgSampleCount },
                totalFibionRrIntervalSampleCount = completedRecordings.sumOf { it.fibionRrIntervalSampleCount },
                totalEsenseRrIntervalSampleCount = completedRecordings.sumOf { it.esenseRrIntervalSampleCount }
            )
        )
    }

    suspend fun updateNotes(id: Long, notes: String) {
        val test = testDao.getTestById(id) ?: return
        testDao.update(test.copy(notes = notes))
    }

    suspend fun markExported(id: Long) {
        val test = testDao.getTestById(id) ?: return
        testDao.update(test.copy(status = TestStatus.EXPORTED))
    }

    /**
     * Deletes a test. Associated recordings and samples are deleted via CASCADE.
     */
    suspend fun deleteTest(id: Long) {
        val test = testDao.getTestById(id) ?: return
        testDao.delete(test)
    }

    /**
     * Gets the count of completed recordings for a test.
     */
    suspend fun getCompletedRecordingCount(testId: Long): Int =
        recordingDao.getCompletedRecordingCount(testId)
}
