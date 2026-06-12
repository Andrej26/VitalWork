package com.vitalwork.app.data.vr

import com.vitalwork.app.data.db.ScenarioCode
import com.vitalwork.app.data.vr.http.ErrorResponse
import com.vitalwork.app.data.vr.http.HeartbeatResponse
import com.vitalwork.app.data.vr.http.ScenarioRequest
import com.vitalwork.app.data.vr.http.StartResponse
import com.vitalwork.app.data.vr.http.StopResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embedded HTTP server (Ktor CIO) that receives the Quest's VR-event POSTs and delegates to
 * [VrEventReceiver]. Started/stopped by [com.vitalwork.app.service.SessionRecordingService]
 * for the lifetime of an ACTIVE session. The routes mirror the wire contract; bodies are
 * parsed leniently so the VR side can add fields without breaking us.
 */
@Singleton
class VrHttpServer @Inject constructor(
    private val receiver: VrEventReceiver,
    private val pairingManager: VrPairingManager,
    private val linkLog: VrLinkLog
) {
    private companion object {
        const val PORT = 8080
        const val QUEST_ID_HEADER = "X-Vr-Quest-Id"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /**
     * How many holders currently need the server running. Both [com.vitalwork.app.service.SessionRecordingService]
     * and the VR Control screen independently [acquire]/[release]; the server only actually starts on
     * the first acquire and stops on the last release — so the Quest can POST events while the
     * operator debugs from VR Control with no session, and a session's server isn't torn down when the
     * operator leaves that screen. Guarded by `this`.
     */
    private var holderCount = 0

    /** Register a holder; starts the server on the first one. Balanced by [release]. */
    @Synchronized
    fun acquire() {
        holderCount++
        if (holderCount == 1) start()
    }

    /** Drop a holder; stops the server on the last one. Extra releases are a no-op (never < 0). */
    @Synchronized
    fun release() {
        if (holderCount == 0) return
        holderCount--
        if (holderCount == 0) stop()
    }

    private fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(ContentNegotiation) { json(json) }
            routing {
                post("/vr/scenario/start") { handleStart() }
                post("/vr/scenario/stop") { handleStop() }
                post("/vr/heartbeat") { handleHeartbeat() }
            }
        }.also { it.start(wait = false) }
    }

    private fun stop() {
        engine?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        engine = null
    }

    // ── Routes ────────────────────────────────────────────────────────────────

    private suspend fun RoutingContext.handleStart() {
        val (body, code) = parse() ?: return
        when (val result = receiver.submit(
            VrEvent.ScenarioStart(code, System.currentTimeMillis())
        )) {
            is VrEventResult.Accepted ->
                call.respond(HttpStatusCode.Created, StartResponse())
            is VrEventResult.Rejected ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse(result.reason))
        }
    }

    private suspend fun RoutingContext.handleStop() {
        val (body, code) = parse() ?: return
        when (val result = receiver.submit(
            VrEvent.ScenarioStop(
                code,
                System.currentTimeMillis(),
                body.eventTimestampMs,
                body.reactionTimestampMs
            )
        )) {
            is VrEventResult.Accepted -> call.respond(StopResponse())
            is VrEventResult.Rejected ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse(result.reason))
        }
    }

    /** Liveness ping from the bonded Quest (independent of sparse scenario events). */
    private suspend fun RoutingContext.handleHeartbeat() {
        if (!authorized()) return
        receiver.markHeartbeat()
        call.respond(HeartbeatResponse())
    }

    /**
     * The pairing gate every route checks first. Reads the caller's source IP + `X-Vr-Quest-Id`
     * header and rejects (403, no detail) anything that isn't the bonded Quest — so a second Quest
     * in the room (or a stray request before the operator taps Connect) is silently ignored.
     * Returns false (and sends 403) when unauthorized, so the caller can early-return.
     */
    private suspend fun RoutingContext.authorized(): Boolean {
        val questId = call.request.headers[QUEST_ID_HEADER]
        val sourceIp = call.request.origin.remoteAddress
        // DIAGNOSTIC (2026-06-11): surface the header the Quest actually sends vs the bonded id, so we
        // can confirm whether a questId mismatch (real HTTP header vs IP-fallback from the blank UDP
        // broadcast) is what was rejecting a correctly-bonded headset. Remove once verified.
        val bondedId = pairingManager.bondedQuestId()
        if (!questId.isNullOrBlank() && questId != bondedId) {
            linkLog.add(
                VrLinkLog.Level.WARNING,
                "Quest-Id mismatch on ${call.request.path()}: header='$questId' vs bonded='$bondedId' " +
                    "(allowed by IP-only test gate)"
            )
        }
        if (!pairingManager.isAuthorized(questId, sourceIp)) {
            linkLog.add(
                VrLinkLog.Level.WARNING,
                "Rejected unpaired request from $sourceIp ${call.request.path()} " +
                    "(header questId='$questId', bonded='$bondedId')"
            )
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(reason = "not_paired"))
            return false
        }
        return true
    }

    /**
     * Gate + receive + validate the shared body. Returns null when the request is unauthorized (403)
     * or the scenario name is unknown (400), so the route handler can early-return. The gate runs for
     * **both** scenario routes (start and stop) — it's the only place a rogue Quest's `start` is
     * stopped before it reaches the ViewModel.
     */
    private suspend fun RoutingContext.parse(): Pair<ScenarioRequest, ScenarioCode>? {
        if (!authorized()) return null
        val body = call.receive<ScenarioRequest>()
        val code = runCatching { ScenarioCode.valueOf(body.scenarioId) }.getOrNull()
        if (code == null) {
            linkLog.add(VrLinkLog.Level.WARNING, "Unknown scenario from headset: '${body.scenarioId}'")
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(reason = "unknown_scenario", value = body.scenarioId)
            )
            return null
        }
        return body to code
    }
}
