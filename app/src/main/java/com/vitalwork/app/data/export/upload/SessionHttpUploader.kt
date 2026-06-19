package com.vitalwork.app.data.export.upload

import com.vitalwork.app.BuildConfig
import com.vitalwork.app.data.export.SessionUploader
import com.vitalwork.app.data.repository.ParticipantRepository
import com.vitalwork.app.data.repository.ScenarioRepository
import com.vitalwork.app.data.repository.SessionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads a completed session to the VitalWork Laravel server as one nested JSON bundle
 * (`POST /api/sessions/upload`, see `test/VitalWork_API_Service_Documentation.docx`).
 *
 * The endpoint is idempotent on `sessionCode` (re-uploading replaces that session's scenarios/samples),
 * so [upload] is always safe to retry. Never throws to the caller — every outcome is a [Result].
 */
@Singleton
class SessionHttpUploader(
    private val sessionRepository: SessionRepository,
    private val participantRepository: ParticipantRepository,
    private val scenarioRepository: ScenarioRepository,
    private val mapper: SessionUploadMapper,
    engine: HttpClientEngine,
) : SessionUploader {

    /** Production constructor used by Hilt; uses the CIO engine. */
    @Inject
    constructor(
        sessionRepository: SessionRepository,
        participantRepository: ParticipantRepository,
        scenarioRepository: ScenarioRepository,
        mapper: SessionUploadMapper,
    ) : this(sessionRepository, participantRepository, scenarioRepository, mapper, CIO.create())

    private val client = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
        }
    }

    override suspend fun upload(sessionId: Long): Result<String> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = BuildConfig.VITALWORK_BASE_URL.trimEnd('/')
            val apiKey = BuildConfig.VITALWORK_API_KEY
            if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Server upload is not configured (missing VITALWORK_BASE_URL / VITALWORK_API_KEY)."
                    )
                )
            }

            val session = sessionRepository.getSessionById(sessionId)
                ?: return@withContext Result.failure(IllegalArgumentException("Session not found"))
            val participant = participantRepository.getParticipantById(session.participantId)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Participant not found for session")
                )
            val scenarios = scenarioRepository.getScenariosForSessionOnce(sessionId)

            val request = mapper.buildUploadRequest(participant, session, scenarios)

            val response: HttpResponse = client.post("$baseUrl/api/sessions/upload") {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            when (response.status) {
                HttpStatusCode.OK, HttpStatusCode.Created -> {
                    val message = runCatching {
                        Json { ignoreUnknownKeys = true }
                            .decodeFromString<SessionUploadResponse>(response.bodyAsText())
                            .message
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Session uploaded."
                    Result.success(message)
                }
                HttpStatusCode.Unauthorized -> Result.failure(
                    UploadException("Server rejected the API key (401 Unauthorized).")
                )
                else -> Result.failure(
                    UploadException(
                        "Upload failed (${response.status.value}): " +
                            response.bodyAsText().take(ERROR_BODY_LIMIT)
                    )
                )
            }
        } catch (e: Exception) {
            // Network errors, timeouts, serialization issues — queued for retry.
            Result.failure(e)
        }
    }

    class UploadException(message: String) : Exception(message)

    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val ERROR_BODY_LIMIT = 500
    }
}
