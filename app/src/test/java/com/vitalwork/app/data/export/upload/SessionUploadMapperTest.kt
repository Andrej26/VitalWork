package com.vitalwork.app.data.export.upload

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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SessionUploadMapperTest {

    private lateinit var scenarioRepository: ScenarioRepository
    private lateinit var sampleDao: FakeSensorSampleDao
    private lateinit var mapper: SessionUploadMapper

    @Before
    fun setUp() {
        val scenarioDao = FakeScenarioDao()
        sampleDao = FakeSensorSampleDao()
        scenarioRepository = ScenarioRepository(scenarioDao, sampleDao, TimeProvider.system())
        mapper = SessionUploadMapper(scenarioRepository)
    }

    private fun participant() = ParticipantEntity(
        id = 1L, participantCode = "A-001", age = 31, gender = "male"
    )

    private fun session() = SessionEntity(
        id = 1L,
        participantId = 1L,
        sessionCode = "VW-A-260101-120000",
        startedAt = 1_790_509_812_000L,
        endedAt = 1_790_510_412_000L,
        status = SessionStatus.COMPLETED,
        scenarioCount = 3,
        hrSampleCount = 120,
        respirationSampleCount = 60,
        rrIntervalSampleCount = 118,
        edaSampleCount = 40,
        watchHrSampleCount = 110,
        watchIbiSampleCount = 105
    )

    @Test
    fun participantAndSession_mapToEpochMsAndEnumNames() = runTest {
        val request = mapper.buildUploadRequest(participant(), session(), emptyList())

        assertEquals("A-001", request.participant.participantCode)
        assertEquals(31, request.participant.age)
        assertEquals("male", request.participant.gender)

        assertEquals("VW-A-260101-120000", request.session.sessionCode)
        assertEquals(1_790_509_812_000L, request.session.startedAt)
        assertEquals(1_790_510_412_000L, request.session.endedAt)
        assertEquals("COMPLETED", request.session.status)
    }

    @Test
    fun session_statisticsCountedFromUploadedSamples_notStoredCounters() = runTest {
        // The session's stored counters (120/60/118/…) are deliberately wrong — the statistics
        // block must be counted from the samples actually uploaded so it always matches the payload.
        val scenario = ScenarioEntity(
            id = 5L,
            sessionId = 1L,
            scenarioCode = ScenarioCode.REFERENCE_STATE,
            startedAt = 1_790_509_820_000L,
            endedAt = null // abnormally ended scenarios count too
        )
        sampleDao.samples.addAll(
            listOf(
                SensorSampleEntity(
                    scenarioId = 5L, timestampMs = 1L, elapsedMs = 1L,
                    sensorType = SensorType.ESENSE_HEART_RATE, value = 82f
                ),
                SensorSampleEntity(
                    scenarioId = 5L, timestampMs = 2L, elapsedMs = 2L,
                    sensorType = SensorType.ESENSE_HEART_RATE, value = 83f
                ),
                SensorSampleEntity(
                    scenarioId = 5L, timestampMs = 3L, elapsedMs = 3L,
                    sensorType = SensorType.RESPIRATION, value = 14f
                ),
                SensorSampleEntity(
                    scenarioId = 5L, timestampMs = 4L, elapsedMs = 4L,
                    sensorType = SensorType.WATCH_EDA, value = 0.4f
                )
            )
        )

        val request = mapper.buildUploadRequest(participant(), session(), listOf(scenario))
        val stats = request.session.statistics

        assertEquals(1, stats.scenarioCount)
        assertEquals(2, stats.hrSampleCount)
        assertEquals(1, stats.respirationSampleCount)
        assertEquals(0, stats.rrIntervalSampleCount)
        assertEquals(1, stats.edaSampleCount)
        assertEquals(0, stats.watchHrSampleCount)
        assertEquals(0, stats.watchIbiSampleCount)
    }

    @Test
    fun scenario_keepsRawTimestamps_andSendsEnumNameScenarioCode() = runTest {
        val scenario = ScenarioEntity(
            id = 5L,
            sessionId = 1L,
            scenarioCode = ScenarioCode.REFERENCE_STATE,
            startedAt = 1_790_509_820_000L,
            endedAt = 1_790_509_880_000L
        )

        val request = mapper.buildUploadRequest(participant(), session(), listOf(scenario))
        val s = request.scenarios.single()

        // Enum NAME, not officialCode "A1".
        assertEquals("REFERENCE_STATE", s.scenarioCode)
        assertEquals(1_790_509_820_000L, s.startedAt)
        assertEquals(1_790_509_880_000L, s.endedAt)
    }

    @Test
    fun samples_useEnumNameSensorType_notLowercaseLabel() = runTest {
        val scenario = ScenarioEntity(
            id = 5L,
            sessionId = 1L,
            scenarioCode = ScenarioCode.COGNITIVE_LOAD,
            startedAt = 1_790_509_900_000L
        )
        sampleDao.samples.addAll(
            listOf(
                SensorSampleEntity(
                    scenarioId = 5L, timestampMs = 1_790_509_929_000L, elapsedMs = 29_000L,
                    sensorType = SensorType.ESENSE_RR_INTERVAL, value = 698.2f
                ),
                SensorSampleEntity(
                    scenarioId = 5L, timestampMs = 1_790_509_929_100L, elapsedMs = 29_100L,
                    sensorType = SensorType.ESENSE_HEART_RATE, value = 82f
                )
            )
        )

        val request = mapper.buildUploadRequest(participant(), session(), listOf(scenario))
        val samples = request.scenarios.single().samples

        assertEquals(2, samples.size)
        // Must be enum NAME (ESENSE_RR_INTERVAL), not the CSV's lowercase "rr_interval".
        assertEquals("ESENSE_RR_INTERVAL", samples[0].sensorType)
        assertEquals(1_790_509_929_000L, samples[0].timestampMs)
        assertEquals(29_000L, samples[0].elapsedMs)
        assertEquals(698.2f, samples[0].value, 0.001f)
        assertEquals("ESENSE_HEART_RATE", samples[1].sensorType)
        assertEquals(29_100L, samples[1].elapsedMs)
    }
}
