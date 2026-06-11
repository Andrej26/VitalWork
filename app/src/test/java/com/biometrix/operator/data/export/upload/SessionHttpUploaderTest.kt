package com.biometrix.operator.data.export.upload

import com.biometrix.operator.BuildConfig
import com.biometrix.operator.data.db.FakeParticipantDao
import com.biometrix.operator.data.db.FakeScenarioDao
import com.biometrix.operator.data.db.FakeSensorSampleDao
import com.biometrix.operator.data.db.FakeSessionDao
import com.biometrix.operator.data.db.ParticipantEntity
import com.biometrix.operator.data.db.ScenarioCode
import com.biometrix.operator.data.db.ScenarioEntity
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.prefs.FakeSettingsRepository
import com.biometrix.operator.data.repository.ParticipantRepository
import com.biometrix.operator.data.repository.ScenarioRepository
import com.biometrix.operator.data.repository.SessionRepository
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
        sessionRepo = SessionRepository(sessionDao, scenarioDao, sampleDao, FakeSettingsRepository("A"))
        participantRepo = ParticipantRepository(participantDao, FakeSettingsRepository("A"))
        scenarioRepo = ScenarioRepository(scenarioDao, sampleDao)
        mapper = SessionUploadMapper(scenarioRepo)

        participantDao.participants.add(ParticipantEntity(id = 1L, participantCode = "A-001"))
        sessionDao.sessions.add(
            SessionEntity(
                id = sessionId, participantId = 1L, sessionCode = "BMX-A-260101-120000",
                startedAt = 1_000L, status = SessionStatus.COMPLETED
            )
        )
        scenarioDao.scenarios.add(
            ScenarioEntity(
                id = 10L, sessionId = sessionId, scenarioCode = ScenarioCode.FALLING_PALLET,
                scenarioCategory = ScenarioCode.FALLING_PALLET.category, startedAt = 2_000L
            )
        )
    }

    private fun uploaderWith(engine: MockEngine) = SessionHttpUploader(
        sessionRepo, participantRepo, scenarioRepo, mapper, engine
    )

    @Test
    fun success201_returnsServerMessage_andSendsApiKeyHeaderToCorrectUrl() = runTest {
        // BuildConfig must be configured for the request to be attempted.
        assumeTrue(BuildConfig.BIOMETRIX_BASE_URL.isNotEmpty() && BuildConfig.BIOMETRIX_API_KEY.isNotEmpty())

        var captured: HttpRequestData? = null
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = """{"message":"Full BioMetrix session uploaded."}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val result = uploaderWith(engine).upload(sessionId)

        assertTrue(result.isSuccess)
        assertEquals("Full BioMetrix session uploaded.", result.getOrNull())
        assertEquals(BuildConfig.BIOMETRIX_API_KEY, captured?.headers?.get("X-Api-Key"))
        assertTrue(captured?.url.toString().endsWith("/api/uploads/session"))
    }

    @Test
    fun unauthorized401_returnsFailure() = runTest {
        assumeTrue(BuildConfig.BIOMETRIX_BASE_URL.isNotEmpty() && BuildConfig.BIOMETRIX_API_KEY.isNotEmpty())

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
        assumeTrue(BuildConfig.BIOMETRIX_BASE_URL.isNotEmpty() && BuildConfig.BIOMETRIX_API_KEY.isNotEmpty())

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
        assumeTrue(BuildConfig.BIOMETRIX_BASE_URL.isNotEmpty() && BuildConfig.BIOMETRIX_API_KEY.isNotEmpty())

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
