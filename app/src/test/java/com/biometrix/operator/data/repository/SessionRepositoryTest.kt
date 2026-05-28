package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.FakeScenarioDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.FakeSessionDao
import com.biometrix.operator.data.db.ScenarioCategory
import com.biometrix.operator.data.db.ScenarioCode
import com.biometrix.operator.data.db.ScenarioEntity
import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType
import com.biometrix.operator.data.db.SessionStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionRepositoryTest {

    private lateinit var fakeSessionDao: FakeSessionDao
    private lateinit var fakeScenarioDao: FakeScenarioDao
    private lateinit var fakeSensorSampleDao: FakeSensorSampleDao
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        fakeSessionDao = FakeSessionDao()
        fakeScenarioDao = FakeScenarioDao()
        fakeSensorSampleDao = FakeSensorSampleDao()
        repository = SessionRepository(fakeSessionDao, fakeScenarioDao, fakeSensorSampleDao)
    }

    @Test
    fun createSession_formatsCodeCorrectly() = runTest {
        val session = repository.createSession(participantId = 1L)

        assertTrue(session.sessionCode.startsWith("BMX-"))
        assertTrue(session.sessionCode.substring(4).matches(Regex("\\d{6}-\\d{6}")))
        assertEquals(SessionStatus.ACTIVE, session.status)
        assertEquals(1L, session.participantId)
    }

    @Test
    fun createSessionIfNoneActive_returnsNullWhenActiveExists() = runTest {
        repository.createSession(participantId = 1L)

        val second = repository.createSessionIfNoneActive(participantId = 1L)

        assertNull(second)
    }

    @Test
    fun endSession_aggregatesSampleCountsFromCompletedScenarios() = runTest {
        val session = repository.createSession(participantId = 1L)
        val s1 = insertScenario(session.id, endedAt = System.currentTimeMillis())
        val s2 = insertScenario(session.id, endedAt = System.currentTimeMillis())
        val unfinished = insertScenario(session.id, endedAt = null)

        fakeSensorSampleDao.samples.addAll(samples(s1.id, SensorType.HEART_RATE, count = 10))
        fakeSensorSampleDao.samples.addAll(samples(s2.id, SensorType.HEART_RATE, count = 5))
        fakeSensorSampleDao.samples.addAll(samples(s1.id, SensorType.RESPIRATION, count = 3))
        fakeSensorSampleDao.samples.addAll(samples(s2.id, SensorType.GSR, count = 7))
        // Samples on the unfinished scenario should not be counted.
        fakeSensorSampleDao.samples.addAll(samples(unfinished.id, SensorType.HEART_RATE, count = 999))

        repository.endSession(session.id)

        val updated = fakeSessionDao.getSessionById(session.id)!!
        assertEquals(SessionStatus.COMPLETED, updated.status)
        assertEquals(2, updated.scenarioCount)
        assertEquals(15, updated.hrSampleCount)
        assertEquals(3, updated.respirationSampleCount)
        assertEquals(7, updated.gsrSampleCount)
        assertNotNull(updated.endedAt)
    }

    @Test
    fun endSession_nonExistentId_doesNothing() = runTest {
        repository.endSession(999L)

        assertTrue(fakeSessionDao.sessions.isEmpty())
    }

    @Test
    fun updateNotes_persistsText() = runTest {
        val session = repository.createSession(participantId = 1L)

        repository.updateNotes(session.id, "WiFi dropped at minute 7")

        assertEquals(
            "WiFi dropped at minute 7",
            fakeSessionDao.getSessionById(session.id)!!.notes
        )
    }

    @Test
    fun markUploaded_changesStatus() = runTest {
        val session = repository.createSession(participantId = 1L)

        repository.markUploaded(session.id)

        assertEquals(SessionStatus.UPLOADED, fakeSessionDao.getSessionById(session.id)!!.status)
    }

    @Test
    fun deleteSession_removesFromStorage() = runTest {
        val session = repository.createSession(participantId = 1L)

        repository.deleteSession(session.id)

        assertNull(fakeSessionDao.getSessionById(session.id))
    }

    private fun insertScenario(
        sessionId: Long,
        endedAt: Long?
    ): ScenarioEntity {
        val nextId = (fakeScenarioDao.scenarios.maxOfOrNull { it.id } ?: 0L) + 1
        val scenario = ScenarioEntity(
            id = nextId,
            sessionId = sessionId,
            scenarioCode = ScenarioCode.FALLING_PALLET,
            scenarioCategory = ScenarioCategory.A,
            startedAt = System.currentTimeMillis(),
            endedAt = endedAt
        )
        fakeScenarioDao.scenarios.add(scenario)
        return scenario
    }

    private fun samples(scenarioId: Long, sensorType: SensorType, count: Int): List<SensorSampleEntity> =
        (1..count).map { i ->
            SensorSampleEntity(
                scenarioId = scenarioId,
                timestampMs = System.currentTimeMillis() + i,
                elapsedMs = i * 1000L,
                sensorType = sensorType,
                value = i.toFloat()
            )
        }
}
