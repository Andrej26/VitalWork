package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.FakeRecordingDao
import com.biometrix.operator.data.db.FakeTestDao
import com.biometrix.operator.data.db.RecordingEntity
import com.biometrix.operator.data.db.RecordingStatus
import com.biometrix.operator.data.db.TestStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TestRepositoryTest {

    private lateinit var fakeTestDao: FakeTestDao
    private lateinit var fakeRecordingDao: FakeRecordingDao
    private lateinit var repository: TestRepository

    @Before
    fun setUp() {
        fakeTestDao = FakeTestDao()
        fakeRecordingDao = FakeRecordingDao()
        repository = TestRepository(fakeTestDao, fakeRecordingDao)
    }

    @Test
    fun createTest_formatsCorrectly() = runTest {
        val test = repository.createTest()

        assertTrue(test.testIdentifier.startsWith("BMX-"))
        assertTrue(test.testNumber.matches(Regex("\\d{6}-\\d{6}")))
        assertEquals(TestStatus.ACTIVE, test.status)
    }

    @Test
    fun endTest_aggregatesOnlyCompletedRecordings() = runTest {
        val test = repository.createTest()
        fakeRecordingDao.recordings.addAll(listOf(
            recording(test.id, status = RecordingStatus.COMPLETED, heartRate = 10),
            recording(test.id, status = RecordingStatus.COMPLETED, heartRate = 20),
            recording(test.id, status = RecordingStatus.DISCARDED, heartRate = 99)
        ))

        repository.endTest(test.id, 2)

        val updated = fakeTestDao.getTestById(test.id)!!
        assertEquals(TestStatus.COMPLETED, updated.status)
        assertEquals(30, updated.totalHeartRateSampleCount)
    }

    @Test
    fun endTest_sumsAllSampleCountFields() = runTest {
        val test = repository.createTest()
        fakeRecordingDao.recordings.addAll(listOf(
            recording(test.id, status = RecordingStatus.COMPLETED,
                heartRate = 5, respiration = 10, esenseRr = 30),
            recording(test.id, status = RecordingStatus.COMPLETED,
                heartRate = 1, respiration = 2, esenseRr = 6)
        ))

        repository.endTest(test.id, 2)

        val updated = fakeTestDao.getTestById(test.id)!!
        assertEquals(6, updated.totalHeartRateSampleCount)
        assertEquals(12, updated.totalRespirationSampleCount)
        assertEquals(36, updated.totalEsenseRrIntervalSampleCount)
    }

    @Test
    fun endTest_nonExistentId_doesNothing() = runTest {
        repository.endTest(999, 0)

        assertTrue(fakeTestDao.tests.isEmpty())
    }

    @Test
    fun updateNotes_persistsText() = runTest {
        val test = repository.createTest()

        repository.updateNotes(test.id, "Patient showed improvement")

        assertEquals("Patient showed improvement", fakeTestDao.getTestById(test.id)!!.notes)
    }

    @Test
    fun markExported_changesStatus() = runTest {
        val test = repository.createTest()

        repository.markExported(test.id)

        assertEquals(TestStatus.EXPORTED, fakeTestDao.getTestById(test.id)!!.status)
    }

    @Test
    fun deleteTest_removesFromStorage() = runTest {
        val test = repository.createTest()

        repository.deleteTest(test.id)

        assertNull(fakeTestDao.getTestById(test.id))
    }

    private fun recording(
        testId: Long,
        status: RecordingStatus = RecordingStatus.COMPLETED,
        heartRate: Int = 0,
        respiration: Int = 0,
        esenseRr: Int = 0
    ) = RecordingEntity(
        testId = testId,
        recordingIdentifier = "REC-${testId}-${System.nanoTime()}",
        sequenceNumber = 1,
        startedAt = System.currentTimeMillis(),
        status = status,
        heartRateSampleCount = heartRate,
        respirationSampleCount = respiration,
        esenseRrIntervalSampleCount = esenseRr
    )
}
