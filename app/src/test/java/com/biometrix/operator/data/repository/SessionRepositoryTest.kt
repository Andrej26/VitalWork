package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.FakeRecordingDao
import com.biometrix.operator.data.db.FakeSessionDao
import com.biometrix.operator.data.db.RecordingEntity
import com.biometrix.operator.data.db.RecordingStatus
import com.biometrix.operator.data.db.SessionStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionRepositoryTest {

    private lateinit var fakeSessionDao: FakeSessionDao
    private lateinit var fakeRecordingDao: FakeRecordingDao
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        fakeSessionDao = FakeSessionDao()
        fakeRecordingDao = FakeRecordingDao()
        repository = SessionRepository(fakeSessionDao, fakeRecordingDao)
    }

    @Test
    fun createTest_formatsCorrectly() = runTest {
        val test = repository.createSession()

        assertTrue(test.sessionIdentifier.startsWith("BMX-"))
        assertTrue(test.sessionNumber.matches(Regex("\\d{6}-\\d{6}")))
        assertEquals(SessionStatus.ACTIVE, test.status)
    }

    @Test
    fun endTest_aggregatesOnlyCompletedRecordings() = runTest {
        val test = repository.createSession()
        fakeRecordingDao.recordings.addAll(listOf(
            recording(test.id, status = RecordingStatus.COMPLETED, heartRate = 10),
            recording(test.id, status = RecordingStatus.COMPLETED, heartRate = 20),
            recording(test.id, status = RecordingStatus.DISCARDED, heartRate = 99)
        ))

        repository.endSession(test.id, 2)

        val updated = fakeSessionDao.getSessionById(test.id)!!
        assertEquals(SessionStatus.COMPLETED, updated.status)
        assertEquals(30, updated.totalHeartRateSampleCount)
    }

    @Test
    fun endTest_sumsAllSampleCountFields() = runTest {
        val test = repository.createSession()
        fakeRecordingDao.recordings.addAll(listOf(
            recording(test.id, status = RecordingStatus.COMPLETED,
                heartRate = 5, respiration = 10, esenseRr = 30),
            recording(test.id, status = RecordingStatus.COMPLETED,
                heartRate = 1, respiration = 2, esenseRr = 6)
        ))

        repository.endSession(test.id, 2)

        val updated = fakeSessionDao.getSessionById(test.id)!!
        assertEquals(6, updated.totalHeartRateSampleCount)
        assertEquals(12, updated.totalRespirationSampleCount)
        assertEquals(36, updated.totalEsenseRrIntervalSampleCount)
    }

    @Test
    fun endTest_nonExistentId_doesNothing() = runTest {
        repository.endSession(999, 0)

        assertTrue(fakeSessionDao.tests.isEmpty())
    }

    @Test
    fun updateNotes_persistsText() = runTest {
        val test = repository.createSession()

        repository.updateNotes(test.id, "Patient showed improvement")

        assertEquals("Patient showed improvement", fakeSessionDao.getSessionById(test.id)!!.notes)
    }

    @Test
    fun markExported_changesStatus() = runTest {
        val test = repository.createSession()

        repository.markExported(test.id)

        assertEquals(SessionStatus.EXPORTED, fakeSessionDao.getSessionById(test.id)!!.status)
    }

    @Test
    fun deleteTest_removesFromStorage() = runTest {
        val test = repository.createSession()

        repository.deleteSession(test.id)

        assertNull(fakeSessionDao.getSessionById(test.id))
    }

    private fun recording(
        sessionId: Long,
        status: RecordingStatus = RecordingStatus.COMPLETED,
        heartRate: Int = 0,
        respiration: Int = 0,
        esenseRr: Int = 0
    ) = RecordingEntity(
        sessionId = sessionId,
        recordingIdentifier = "REC-${sessionId}-${System.nanoTime()}",
        sequenceNumber = 1,
        startedAt = System.currentTimeMillis(),
        status = status,
        heartRateSampleCount = heartRate,
        respirationSampleCount = respiration,
        esenseRrIntervalSampleCount = esenseRr
    )
}
