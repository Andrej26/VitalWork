package com.vitalwork.app.data.export

import com.vitalwork.app.data.db.FakeScenarioDao
import com.vitalwork.app.data.db.FakeSensorSampleDao
import com.vitalwork.app.data.db.ParticipantEntity
import com.vitalwork.app.data.db.ScenarioCode
import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SensorSampleEntity
import com.vitalwork.app.data.db.SensorType
import com.vitalwork.app.data.db.SessionEntity
import com.vitalwork.app.data.db.SessionStatus
import com.vitalwork.app.data.repository.ScenarioRepository
import com.vitalwork.app.data.time.TimeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionExportMapperTest {

    private lateinit var fakeScenarioDao: FakeScenarioDao
    private lateinit var fakeSensorSampleDao: FakeSensorSampleDao
    private lateinit var scenarioRepository: ScenarioRepository
    private lateinit var mapper: SessionExportMapper

    @Before
    fun setUp() {
        fakeScenarioDao = FakeScenarioDao()
        fakeSensorSampleDao = FakeSensorSampleDao()
        scenarioRepository = ScenarioRepository(fakeScenarioDao, fakeSensorSampleDao, TimeProvider.system())
        mapper = SessionExportMapper(scenarioRepository, TimeProvider.system())
    }

    @Test
    fun buildScenarioExport_sensorTypesMappedToLowercase() {
        val scenario = scenario()
        val samples = listOf(
            sample(scenario.id, SensorType.ESENSE_HEART_RATE),
            sample(scenario.id, SensorType.ESENSE_RR_INTERVAL),
            sample(scenario.id, SensorType.RESPIRATION),
            sample(scenario.id, SensorType.WATCH_HR),
            sample(scenario.id, SensorType.WATCH_IBI),
            sample(scenario.id, SensorType.WATCH_EDA)
        )

        val result = mapper.buildScenarioExport(scenario, samples)

        assertEquals(
            listOf("esense_heart_rate", "rr_interval", "respiration", "watch_hr", "watch_ibi", "watch_eda"),
            result.samples.map { it.sensorType }
        )
    }

    @Test
    fun buildScenarioExport_scenarioCodeEmittedAsString() {
        val scenario = scenario(code = ScenarioCode.MACHINE_JAM)

        val result = mapper.buildScenarioExport(scenario, emptyList())

        assertEquals("MACHINE_JAM", result.scenarioCode)
    }

    @Test
    fun buildScenarioExport_gapsNullWhenNoSignificantGaps() {
        val scenario = scenario()
        val samples = listOf(
            sample(scenario.id, SensorType.ESENSE_HEART_RATE, elapsedMs = 0L),
            sample(scenario.id, SensorType.ESENSE_HEART_RATE, elapsedMs = 1_000L)
        )

        val result = mapper.buildScenarioExport(scenario, samples)

        assertNull(result.gaps)
    }

    @Test
    fun buildScenarioExport_heartRateGapsDetected() {
        val scenario = scenario()
        val samples = listOf(
            sample(scenario.id, SensorType.ESENSE_HEART_RATE, elapsedMs = 11_000L),
            sample(scenario.id, SensorType.ESENSE_HEART_RATE, elapsedMs = 12_000L),
            sample(scenario.id, SensorType.ESENSE_HEART_RATE, elapsedMs = 25_000L)
        )

        val result = mapper.buildScenarioExport(scenario, samples)

        assertNotNull(result.gaps)
        assertNotNull(result.gaps!!.heartRate)
        assertTrue(result.gaps!!.heartRate!!.gapCount > 0)
    }

    @Test
        fun buildExportData_statisticsDerivedFromExportedSamples() = runTest {
        val participant = participant()
        // Stored counters are deliberately wrong/stale — statistics must ignore them and count the
        // samples actually being exported instead.
        val session = session(
            scenarioCount = 99,
            hrSampleCount = 0,
            respirationSampleCount = 0,
            rrIntervalSampleCount = 0,
            edaSampleCount = 0
        )
        val scenario = scenario(id = 7L)
        fakeSensorSampleDao.samples.addAll(listOf(
            sample(scenario.id, SensorType.ESENSE_HEART_RATE),
            sample(scenario.id, SensorType.ESENSE_HEART_RATE),
            sample(scenario.id, SensorType.RESPIRATION),
            sample(scenario.id, SensorType.ESENSE_RR_INTERVAL),
            sample(scenario.id, SensorType.WATCH_EDA),
            sample(scenario.id, SensorType.WATCH_HR),
            sample(scenario.id, SensorType.WATCH_HR),
            sample(scenario.id, SensorType.WATCH_HR),
            sample(scenario.id, SensorType.WATCH_IBI)
        ))

        val result = mapper.buildExportData(participant, session, listOf(scenario))

        assertEquals(1, result.session.statistics.scenarioCount)
        assertEquals(2, result.session.statistics.hrSampleCount)
        assertEquals(1, result.session.statistics.respirationSampleCount)
        assertEquals(1, result.session.statistics.rrIntervalSampleCount)
        assertEquals(1, result.session.statistics.edaSampleCount)
        assertEquals(3, result.session.statistics.watchHrSampleCount)
        assertEquals(1, result.session.statistics.watchIbiSampleCount)
    }

    @Test
    fun buildExportData_statisticsCountUnfinishedScenarios() = runTest {
        val participant = participant()
        val session = session()
        // A scenario that ended abnormally (endedAt == null) — its samples must still be counted.
        val unfinished = scenario(id = 8L, endedAt = null)
        fakeSensorSampleDao.samples.addAll(listOf(
            sample(unfinished.id, SensorType.ESENSE_HEART_RATE),
            sample(unfinished.id, SensorType.WATCH_HR)
        ))

        val result = mapper.buildExportData(participant, session, listOf(unfinished))

        assertEquals(1, result.session.statistics.scenarioCount)
        assertEquals(1, result.session.statistics.hrSampleCount)
        assertEquals(1, result.session.statistics.watchHrSampleCount)
    }

    @Test
    fun buildExportData_scenariosIncludedWithSamples() = runTest {
        val participant = participant()
        val session = session()
        val scenario = scenario(id = 42L)
        fakeSensorSampleDao.samples.addAll(listOf(
            sample(scenario.id, SensorType.ESENSE_HEART_RATE, value = 72f),
            sample(scenario.id, SensorType.ESENSE_HEART_RATE, value = 75f)
        ))

        val result = mapper.buildExportData(participant, session, listOf(scenario))

        assertEquals(1, result.scenarios.size)
        assertEquals(2, result.scenarios[0].samples.size)
        assertEquals(72f, result.scenarios[0].samples[0].value, 0f)
    }

    @Test
    fun buildExportData_participantFieldsMapped() = runTest {
        val participant = participant(code = "P-042", age = 31, gender = "F")
        val session = session()

        val result = mapper.buildExportData(participant, session, emptyList())

        assertEquals("P-042", result.participant.participantCode)
        assertEquals(31, result.participant.age)
        assertEquals("F", result.participant.gender)
    }

    @Test
    fun buildExportData_sessionFieldsMapped() = runTest {
        val participant = participant()
        val session = session(
            sessionCode = "VW-260528-143012",
            status = SessionStatus.UPLOADED
        )

        val result = mapper.buildExportData(participant, session, emptyList())

        assertEquals("VW-260528-143012", result.session.sessionCode)
        assertEquals("UPLOADED", result.session.status)
        assertEquals("2.1.0", result.version)
    }

    // -- Helpers --

    private fun participant(
        code: String = "P-001",
        age: Int? = 30,
        gender: String? = "M"
    ) = ParticipantEntity(id = 1L, participantCode = code, age = age, gender = gender)

    private fun session(
        sessionCode: String = "VW-260528-143012",
        status: SessionStatus = SessionStatus.COMPLETED,
        scenarioCount: Int = 0,
        hrSampleCount: Int = 0,
        respirationSampleCount: Int = 0,
        rrIntervalSampleCount: Int = 0,
        edaSampleCount: Int = 0
    ) = SessionEntity(
        id = 1L,
        participantId = 1L,
        sessionCode = sessionCode,
        startedAt = 1_000_000L,
        endedAt = 1_060_000L,
        status = status,
        hrSampleCount = hrSampleCount,
        respirationSampleCount = respirationSampleCount,
        rrIntervalSampleCount = rrIntervalSampleCount,
        edaSampleCount = edaSampleCount,
        scenarioCount = scenarioCount
    )

    private var nextScenarioId = 1L
    private fun scenario(
        id: Long? = null,
        sessionId: Long = 1L,
        code: ScenarioCode = ScenarioCode.FALLING_PALLET,
        endedAt: Long? = 1_060_000L
    ): ScenarioEntity {
        val actualId = id ?: nextScenarioId++
        return ScenarioEntity(
            id = actualId,
            sessionId = sessionId,
            scenarioCode = code,
            startedAt = 1_000_000L,
            endedAt = endedAt
        )
    }

    private fun sample(
        scenarioId: Long,
        sensorType: SensorType,
        elapsedMs: Long = 0L,
        value: Float = 72f
    ) = SensorSampleEntity(
        scenarioId = scenarioId,
        timestampMs = 1_000_000L + elapsedMs,
        elapsedMs = elapsedMs,
        sensorType = sensorType,
        value = value
    )
}
