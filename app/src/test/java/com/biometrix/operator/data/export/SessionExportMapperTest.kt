package com.biometrix.operator.data.export

import com.biometrix.operator.data.db.FakeScenarioDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.ParticipantEntity
import com.biometrix.operator.data.db.ScenarioCategory
import com.biometrix.operator.data.db.ScenarioCode
import com.biometrix.operator.data.db.ScenarioEntity
import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.repository.ScenarioRepository
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
        scenarioRepository = ScenarioRepository(fakeScenarioDao, fakeSensorSampleDao)
        mapper = SessionExportMapper(scenarioRepository)
    }

    @Test
    fun buildScenarioExport_sensorTypesMappedToLowercase() {
        val scenario = scenario()
        val samples = listOf(
            sample(scenario.id, SensorType.HEART_RATE),
            sample(scenario.id, SensorType.ESENSE_RR_INTERVAL),
            sample(scenario.id, SensorType.RESPIRATION),
            sample(scenario.id, SensorType.EDA)
        )

        val result = mapper.buildScenarioExport(scenario, samples)

        assertEquals(
            listOf("heart_rate", "rr_interval", "respiration", "eda"),
            result.samples.map { it.sensorType }
        )
    }

    @Test
    fun buildScenarioExport_categoryEmittedAsString() {
        val scenario = scenario(code = ScenarioCode.MACHINE_JAM)

        val result = mapper.buildScenarioExport(scenario, emptyList())

        assertEquals("MACHINE_JAM", result.scenarioCode)
        assertEquals("B", result.scenarioCategory)
    }

    @Test
    fun buildScenarioExport_reactionTimeDerived() {
        val scenario = scenario(
            eventTimestampMs = 10_000L,
            reactionTimestampMs = 10_612L
        )

        val result = mapper.buildScenarioExport(scenario, emptyList())

        assertEquals(10_000L, result.eventTimestampMs)
        assertEquals(10_612L, result.reactionTimestampMs)
        assertEquals(612L, result.reactionTimeMs)
    }

    @Test
    fun buildScenarioExport_reactionTimeNullWhenEventMissing() {
        val scenario = scenario(eventTimestampMs = null, reactionTimestampMs = null)

        val result = mapper.buildScenarioExport(scenario, emptyList())

        assertNull(result.reactionTimeMs)
    }

    @Test
    fun buildScenarioExport_gapsNullWhenNoSignificantGaps() {
        val scenario = scenario()
        val samples = listOf(
            sample(scenario.id, SensorType.HEART_RATE, elapsedMs = 0L),
            sample(scenario.id, SensorType.HEART_RATE, elapsedMs = 1_000L)
        )

        val result = mapper.buildScenarioExport(scenario, samples)

        assertNull(result.gaps)
    }

    @Test
    fun buildScenarioExport_heartRateGapsDetected() {
        val scenario = scenario()
        val samples = listOf(
            sample(scenario.id, SensorType.HEART_RATE, elapsedMs = 11_000L),
            sample(scenario.id, SensorType.HEART_RATE, elapsedMs = 12_000L),
            sample(scenario.id, SensorType.HEART_RATE, elapsedMs = 25_000L)
        )

        val result = mapper.buildScenarioExport(scenario, samples)

        assertNotNull(result.gaps)
        assertNotNull(result.gaps!!.heartRate)
        assertTrue(result.gaps!!.heartRate!!.gapCount > 0)
    }

    @Test
    fun buildExportData_statisticsReflectSession() = runTest {
        val participant = participant()
        val session = session(
            scenarioCount = 3,
            hrSampleCount = 100,
            respirationSampleCount = 50,
            rrIntervalSampleCount = 80,
            edaSampleCount = 12
        )

        val result = mapper.buildExportData(participant, session, emptyList())

        assertEquals(3, result.session.statistics.scenarioCount)
        assertEquals(100, result.session.statistics.hrSampleCount)
        assertEquals(50, result.session.statistics.respirationSampleCount)
        assertEquals(80, result.session.statistics.rrIntervalSampleCount)
        assertEquals(12, result.session.statistics.edaSampleCount)
    }

    @Test
    fun buildExportData_scenariosIncludedWithSamples() = runTest {
        val participant = participant()
        val session = session()
        val scenario = scenario(id = 42L)
        fakeSensorSampleDao.samples.addAll(listOf(
            sample(scenario.id, SensorType.HEART_RATE, value = 72f),
            sample(scenario.id, SensorType.HEART_RATE, value = 75f)
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
            sessionCode = "BMX-260528-143012",
            status = SessionStatus.UPLOADED,
            notes = "WiFi dropped at min 7"
        )

        val result = mapper.buildExportData(participant, session, emptyList())

        assertEquals("BMX-260528-143012", result.session.sessionCode)
        assertEquals("UPLOADED", result.session.status)
        assertEquals("WiFi dropped at min 7", result.session.notes)
        assertEquals("2.0.0", result.version)
    }

    // -- Helpers --

    private fun participant(
        code: String = "P-001",
        age: Int? = 30,
        gender: String? = "M"
    ) = ParticipantEntity(id = 1L, participantCode = code, age = age, gender = gender)

    private fun session(
        sessionCode: String = "BMX-260528-143012",
        status: SessionStatus = SessionStatus.COMPLETED,
        notes: String = "",
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
        notes = notes,
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
        eventTimestampMs: Long? = null,
        reactionTimestampMs: Long? = null
    ): ScenarioEntity {
        val actualId = id ?: nextScenarioId++
        return ScenarioEntity(
            id = actualId,
            sessionId = sessionId,
            scenarioCode = code,
            scenarioCategory = code.category,
            startedAt = 1_000_000L,
            endedAt = 1_060_000L,
            eventTimestampMs = eventTimestampMs,
            reactionTimestampMs = reactionTimestampMs
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun ScenarioCategory.unused() = Unit

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
