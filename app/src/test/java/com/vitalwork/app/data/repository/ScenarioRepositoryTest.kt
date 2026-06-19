package com.vitalwork.app.data.repository

import com.vitalwork.app.data.db.FakeScenarioDao
import com.vitalwork.app.data.db.FakeSensorSampleDao
import com.vitalwork.app.data.db.ScenarioCode
import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SensorSampleEntity
import com.vitalwork.app.data.db.SensorType
import com.vitalwork.app.data.time.TimeProvider
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
        repository = ScenarioRepository(fakeScenarioDao, fakeSensorSampleDao, TimeProvider.system())
    }

    @Test
    fun createScenario_setsCodeAndSession() = runTest {
        val s = repository.createScenario(sessionId = 1L, scenarioCode = ScenarioCode.MACHINE_JAM)

        assertEquals(ScenarioCode.MACHINE_JAM, s.scenarioCode)
        assertEquals(1L, s.sessionId)
        assertNull(s.endedAt)
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
                sensorType = SensorType.ESENSE_HEART_RATE, value = 72f
            ),
            SensorSampleEntity(
                scenarioId = 1L, timestampMs = 2000L, elapsedMs = 1000L,
                sensorType = SensorType.WATCH_EDA, value = 1.2f
            )
        )

        repository.addSamples(samples)

        val retrieved = repository.getSamplesForScenario(1L)
        assertEquals(2, retrieved.size)
        assertEquals(1, repository.getSampleCountBySensorType(1L, SensorType.ESENSE_HEART_RATE))
        assertEquals(1, repository.getSampleCountBySensorType(1L, SensorType.WATCH_EDA))
    }

    // --- closeDanglingScenarios (process-kill recovery) ---

    @Test
    fun closeDanglingScenarios_closesOpenScenarioToLastSampleTimestamp() = runTest {
        val id = insertScenario(sessionId = 1L, startedAt = 1_000L, endedAt = null)
        repository.addSamples(listOf(
            sample(id, timestampMs = 1_200L),
            sample(id, timestampMs = 1_900L), // latest
            sample(id, timestampMs = 1_500L)
        ))

        val closed = repository.closeDanglingScenarios(sessionId = 1L)

        assertEquals(1, closed)
        assertEquals(1_900L, fakeScenarioDao.getScenarioById(id)!!.endedAt)
    }

    @Test
    fun closeDanglingScenarios_fallsBackToStartedAtWhenNoSamples() = runTest {
        val id = insertScenario(sessionId = 1L, startedAt = 5_000L, endedAt = null)

        val closed = repository.closeDanglingScenarios(sessionId = 1L)

        assertEquals(1, closed)
        assertEquals(5_000L, fakeScenarioDao.getScenarioById(id)!!.endedAt)
    }

    @Test
    fun closeDanglingScenarios_leavesAlreadyClosedScenarioUntouched() = runTest {
        val id = insertScenario(sessionId = 1L, startedAt = 1_000L, endedAt = 2_000L)
        repository.addSamples(listOf(sample(id, timestampMs = 9_999L)))

        val closed = repository.closeDanglingScenarios(sessionId = 1L)

        assertEquals(0, closed)
        assertEquals(2_000L, fakeScenarioDao.getScenarioById(id)!!.endedAt)
    }

    @Test
    fun closeDanglingScenarios_closesMultipleOpenToNonOverlappingWindows() = runTest {
        // Two scenarios left open across separate kill cycles (mirrors the testo session).
        val first = insertScenario(sessionId = 1L, startedAt = 1_000L, endedAt = null)
        val second = insertScenario(sessionId = 1L, startedAt = 4_000L, endedAt = null)
        repository.addSamples(listOf(
            sample(first, timestampMs = 1_500L),
            sample(first, timestampMs = 3_000L),  // first ends at 3_000
            sample(second, timestampMs = 4_500L),
            sample(second, timestampMs = 5_200L)  // second ends at 5_200
        ))

        val closed = repository.closeDanglingScenarios(sessionId = 1L)

        assertEquals(2, closed)
        val firstEnd = fakeScenarioDao.getScenarioById(first)!!.endedAt!!
        val secondStart = fakeScenarioDao.getScenarioById(second)!!.startedAt
        assertEquals(3_000L, firstEnd)
        assertEquals(5_200L, fakeScenarioDao.getScenarioById(second)!!.endedAt)
        // The windows do not overlap: first closes before the second begins.
        assert(firstEnd < secondStart)
    }

    @Test
    fun closeDanglingScenarios_onlyTouchesGivenSession() = runTest {
        insertScenario(sessionId = 1L, startedAt = 1_000L, endedAt = null)
        val otherId = insertScenario(sessionId = 2L, startedAt = 1_000L, endedAt = null)

        val closed = repository.closeDanglingScenarios(sessionId = 1L)

        assertEquals(1, closed)
        assertNull(fakeScenarioDao.getScenarioById(otherId)!!.endedAt)
    }

    private suspend fun insertScenario(sessionId: Long, startedAt: Long, endedAt: Long?): Long =
        fakeScenarioDao.insert(
            ScenarioEntity(
                sessionId = sessionId,
                scenarioCode = ScenarioCode.FALLING_PALLET,
                startedAt = startedAt,
                endedAt = endedAt
            )
        )

    private fun sample(scenarioId: Long, timestampMs: Long) = SensorSampleEntity(
        scenarioId = scenarioId,
        timestampMs = timestampMs,
        elapsedMs = 0L,
        sensorType = SensorType.ESENSE_HEART_RATE,
        value = 72f
    )
}
