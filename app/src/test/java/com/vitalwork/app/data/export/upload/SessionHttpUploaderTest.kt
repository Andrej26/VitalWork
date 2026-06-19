package com.vitalwork.app.data.export.upload

import com.vitalwork.app.BuildConfig
import com.vitalwork.app.data.db.FakeParticipantDao
import com.vitalwork.app.data.db.FakeScenarioDao
import com.vitalwork.app.data.db.FakeSensorSampleDao
import com.vitalwork.app.data.db.FakeSessionDao
import com.vitalwork.app.data.db.ParticipantEntity
import com.vitalwork.app.data.db.ScenarioCode
import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SessionEntity
import com.vitalwork.app.data.db.SessionStatus
import com.vitalwork.app.data.prefs.FakeSettingsRepository
import com.vitalwork.app.data.repository.ParticipantRepository
import com.vitalwork.app.data.repository.ScenarioRepository
import com.vitalwork.app.data.repository.SessionRepository
import com.vitalwork.app.data.time.TimeProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class SessionHttpUploaderTest {

    private lateinit var sessionRepo: SessionRepository
    private lateinit var participantRepo: ParticipantRepository
    private lateinit var scenarioRepo: ScenarioRepository
    private lateinit var mapper: SessionUploadMapper
    private lateinit var participantDao: FakeParticipantDao
    private lateinit var sessionDao: FakeSessionDao
    private lateinit var scenarioDao: FakeScenarioDao
    private lateinit var sampleDao: FakeSensorSampleDao

    private val sessionId = 1L

    @Before
    fun setUp() {
        participantDao = FakeParticipantDao()
        sessionDao = FakeSessionDao()
        scenarioDao = FakeScenarioDao()
        sampleDao = FakeSensorSampleDao()
        sessionRepo = SessionRepository(sessionDao, scenarioDao, sampleDao, FakeSettingsRepository("A"), TimeProvider.system())
        participantRepo = ParticipantRepository(participantDao, FakeSettingsRepository("A"))
        scenarioRepo = ScenarioRepository(scenarioDao, sampleDao, TimeProvider.system())
        mapper = SessionUploadMapper(scenarioRepo)

        participantDao.participants.add(ParticipantEntity(id = 1L, participantCode = "A-001"))
        sessionDao.sessions.add(
            SessionEntity(
                id = sessionId, participantId = 1L, sessionCode = "VW-A-260101-120000",
                startedAt = 1_000L, status = SessionStatus.COMPLETED
            )
        )
        scenarioDao.scenarios.add(
            ScenarioEntity(
                id = 10L, sessionId = sessionId, scenarioCode = ScenarioCode.REFERENCE_STATE,
                startedAt = 2_000L
            )
        )
    }

    private fun uploaderWith(engine: MockEngine) = SessionHttpUploader(
        sessionRepo, participantRepo, scenarioRepo, mapper, engine
    )

    @Test
    fun success201_returnsServerMessage_andSendsApiKeyHeaderToCorrectUrl() = runTest {
        // BuildConfig must be configured for the request to be attempted.
        assumeTrue(BuildConfig.VITALWORK_BASE_URL.isNotEmpty() && BuildConfig.VITALWORK_API_KEY.isNotEmpty())

        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = """{"message":"Full VitalWork session uploaded."}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val result = uploaderWith(engine).upload(sessionId)

        assertTrue(result.isSuccess)
        assertEquals("Full VitalWork session uploaded.", result.getOrNull())
        assertEquals(BuildConfig.VITALWORK_API_KEY, captured?.headers?.get("X-Api-Key"))
        assertTrue(captured?.url.toString().endsWith("/api/uploads/session"))
    }

    @Test
    fun unauthorized401_returnsFailure() = runTest {
        assumeTrue(BuildConfig.VITALWORK_BASE_URL.isNotEmpty() && BuildConfig.VITALWORK_API_KEY.isNotEmpty())

        val engine = MockEngine {
            respond(
                content = """{"message":"Unauthorized."}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val result = uploaderWith(engine).upload(sessionId)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("401") == true)
    }

    @Test
    fun validationError422_returnsFailure() = runTest {
        assumeTrue(BuildConfig.VITALWORK_BASE_URL.isNotEmpty() && BuildConfig.VITALWORK_API_KEY.isNotEmpty())

        val engine = MockEngine {
            respond(
                content = """{"message":"The given data was invalid."}""",
                status = HttpStatusCode.UnprocessableEntity,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val result = uploaderWith(engine).upload(sessionId)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("422") == true)
    }

    @Test
    fun missingSession_returnsFailure_withoutNetworkCall() = runTest {
        assumeTrue(BuildConfig.VITALWORK_BASE_URL.isNotEmpty() && BuildConfig.VITALWORK_API_KEY.isNotEmpty())

        var called = false
        val engine = MockEngine {
            called = true
            respond("", HttpStatusCode.Created)
        }

        val result = uploaderWith(engine).upload(999L)

        assertTrue(result.isFailure)
        assertFalse(called)
    }
}
