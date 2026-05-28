package com.biometrix.operator.data.repository

import com.biometrix.operator.data.db.FakeScenarioDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.ScenarioCategory
import com.biometrix.operator.data.db.ScenarioCode
import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ScenarioRepositoryTest {

    private lateinit var fakeScenarioDao: FakeScenarioDao
    private lateinit var fakeSensorSampleDao: FakeSensorSampleDao
    private lateinit var repository: ScenarioRepository

    @Before
    fun setUp() {
        fakeScenarioDao = FakeScenarioDao()
        fakeSensorSampleDao = FakeSensorSampleDao()
        repository = ScenarioRepository(fakeScenarioDao, fakeSensorSampleDao)
    }

    @Test
    fun createScenario_derivesCategoryFromCode() = runTest {
        val s = repository.createScenario(sessionId = 1L, scenarioCode = ScenarioCode.MACHINE_JAM)

        assertEquals(ScenarioCode.MACHINE_JAM, s.scenarioCode)
        assertEquals(ScenarioCategory.B, s.scenarioCategory)
        assertEquals(1L, s.sessionId)
        assertNull(s.endedAt)
        assertNull(s.eventTimestampMs)
        assertNull(s.reactionTimestampMs)
    }

    @Test
    fun setEventTimestamp_persists() = runTest {
        val s = repository.createScenario(sessionId = 1L, scenarioCode = ScenarioCode.FALLING_PALLET)

        repository.setEventTimestamp(s.id, eventTimestampMs = 12_345L)

        val updated = fakeScenarioDao.getScenarioById(s.id)!!
        assertEquals(12_345L, updated.eventTimestampMs)
    }

    @Test
    fun setReactionTimestamp_persists() = runTest {
        val s = repository.createScenario(sessionId = 1L, scenarioCode = ScenarioCode.FALLING_PALLET)

        repository.setReactionTimestamp(s.id, reactionTimestampMs = 67_890L)

        val updated = fakeScenarioDao.getScenarioById(s.id)!!
        assertEquals(67_890L, updated.reactionTimestampMs)
    }

    @Test
    fun endScenario_setsEndedAt() = runTest {
        val s = repository.createScenario(sessionId = 1L, scenarioCode = ScenarioCode.SLING_FAILURE)

        repository.endScenario(s.id)

        val updated = fakeScenarioDao.getScenarioById(s.id)!!
        assertNotNull(updated.endedAt)
    }

    @Test
    fun endScenario_idempotent() = runTest {
        val s = repository.createScenario(sessionId = 1L, scenarioCode = ScenarioCode.SLING_FAILURE)
        repository.endScenario(s.id)
        val firstEnd = fakeScenarioDao.getScenarioById(s.id)!!.endedAt

        repository.endScenario(s.id)

        val secondEnd = fakeScenarioDao.getScenarioById(s.id)!!.endedAt
        assertEquals(firstEnd, secondEnd)
    }

    @Test
    fun addSamples_persists() = runTest {
        val samples = listOf(
            SensorSampleEntity(
                scenarioId = 1L, timestampMs = 1000L, elapsedMs = 0L,
                sensorType = SensorType.HEART_RATE, value = 72f
            ),
            SensorSampleEntity(
                scenarioId = 1L, timestampMs = 2000L, elapsedMs = 1000L,
                sensorType = SensorType.GSR, value = 1.2f
            )
        )

        repository.addSamples(samples)

        val retrieved = repository.getSamplesForScenario(1L)
        assertEquals(2, retrieved.size)
        assertEquals(1, repository.getSampleCountBySensorType(1L, SensorType.HEART_RATE))
        assertEquals(1, repository.getSampleCountBySensorType(1L, SensorType.GSR))
    }
}
