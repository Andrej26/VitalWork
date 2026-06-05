package com.biometrix.operator.data.vr

import com.biometrix.operator.data.db.ScenarioCode
import com.biometrix.operator.data.vr.http.ErrorResponse
import com.biometrix.operator.data.vr.http.EventResponse
import com.biometrix.operator.data.vr.http.ReactionResponse
import com.biometrix.operator.data.vr.http.ScenarioRequest
import com.biometrix.operator.data.vr.http.StartResponse
import com.biometrix.operator.data.vr.http.StopResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
 * [VrEventReceiver]. Started/stopped by [com.biometrix.operator.service.SessionRecordingService]
 * for the lifetime of an ACTIVE session. The four routes mirror the wire contract; bodies are
 * parsed leniently so the VR side can add fields without breaking us.
 */
@Singleton
class VrHttpServer @Inject constructor(
    private val receiver: VrEventReceiver,
    private val beacon: VrUdpBeacon
) {
    private companion object {
        const val PORT = 8080
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Synchronized
    fun start(sessionIdProvider: () -> Long?) {
        if (engine != null) return
        engine = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(ContentNegotiation) { json(json) }
            routing {
                post("/vr/scenario/start") { handleStart() }
                post("/vr/scenario/event") { handleEvent() }
                post("/vr/scenario/reaction") { handleReaction() }
                post("/vr/scenario/stop") { handleStop() }
            }
        }.also { it.start(wait = false) }
        beacon.start(sessionIdProvider)
    }

    @Synchronized
    fun stop() {
        beacon.stop()
        engine?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        engine = null
    }

    // ── Routes ────────────────────────────────────────────────────────────────

    private suspend fun RoutingContext.handleStart() {
        val (sessionId, code) = parse() ?: return
        when (val result = receiver.submit(
            VrEvent.ScenarioStart(sessionId, code, System.currentTimeMillis())
        )) {
            is VrEventResult.Accepted ->
                call.respond(HttpStatusCode.Created, StartResponse())
            is VrEventResult.Rejected ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse(result.reason))
        }
    }

    private suspend fun RoutingContext.handleEvent() {
        val (sessionId, code) = parse() ?: return
        when (val result = receiver.submit(
            VrEvent.StimulusEvent(sessionId, code, System.currentTimeMillis())
        )) {
            is VrEventResult.Accepted ->
                call.respond(EventResponse(eventTimestampMs = result.timestampMs ?: 0L))
            is VrEventResult.Rejected ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse(result.reason))
        }
    }

    private suspend fun RoutingContext.handleReaction() {
        val (sessionId, code) = parse() ?: return
        when (val result = receiver.submit(
            VrEvent.Reaction(sessionId, code, System.currentTimeMillis())
        )) {
            is VrEventResult.Accepted ->
                call.respond(ReactionResponse(reactionTimestampMs = result.timestampMs ?: 0L))
            is VrEventResult.Rejected ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse(result.reason))
        }
    }

    private suspend fun RoutingContext.handleStop() {
        val (sessionId, code) = parse() ?: return
        when (val result = receiver.submit(
            VrEvent.ScenarioStop(sessionId, code, System.currentTimeMillis())
        )) {
            is VrEventResult.Accepted -> call.respond(StopResponse())
            is VrEventResult.Rejected ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse(result.reason))
        }
    }

    /**
     * Receives + validates the shared body. Returns null (and sends a 400) if the scenario name is
     * unknown, so the route handler can early-return.
     */
    private suspend fun RoutingContext.parse(): Pair<Long, ScenarioCode>? {
        val body = call.receive<ScenarioRequest>()
        val code = runCatching { ScenarioCode.valueOf(body.scenarioId) }.getOrNull()
        if (code == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(reason = "unknown_scenario", value = body.scenarioId)
            )
            return null
        }
        return body.sessionId to code
    }
}
