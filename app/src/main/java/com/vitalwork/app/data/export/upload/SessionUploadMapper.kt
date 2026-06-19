package com.vitalwork.app.data.export.upload

import com.vitalwork.app.data.db.ParticipantEntity
import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SensorSampleEntity
import com.vitalwork.app.data.db.SessionEntity
import com.vitalwork.app.data.repository.ScenarioRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps the local Room entities to the server's full-session upload shape ([SessionUploadRequest]).
 *
 * Unlike [com.vitalwork.app.data.export.SessionExportMapper] (ISO strings, gaps, statistics), this
 * emits raw epoch-millisecond timestamps straight off the entities and **enum-NAME** sensor types, which is
 * what `POST /api/uploads/session` expects (doc §4/§9). No gap or statistics computation — the server
 * recomputes counts from the samples.
 */
@Singleton
class SessionUploadMapper @Inject constructor(
    private val scenarioRepository: ScenarioRepository
) {

    suspend fun buildUploadRequest(
        participant: ParticipantEntity,
        session: SessionEntity,
        scenarios: List<ScenarioEntity>
    ): SessionUploadRequest {
        val scenarioUploads = scenarios.map { scenario ->
            val samples = scenarioRepository.getSamplesForScenario(scenario.id)
            buildScenarioUpload(scenario, samples)
        }

        return SessionUploadRequest(
            participant = ParticipantUpload(
                participantCode = participant.participantCode,
                age = participant.age,
                gender = participant.gender
            ),
            session = SessionUpload(
                sessionCode = session.sessionCode,
                startedAtMs = session.startedAt,
                endedAtMs = session.endedAt,
                status = session.status.name
            ),
            scenarios = scenarioUploads
        )
    }

    fun buildScenarioUpload(
        scenario: ScenarioEntity,
        samples: List<SensorSampleEntity>
    ): ScenarioUpload = ScenarioUpload(
        scenarioCode = scenario.scenarioCode.name,
        startedAtMs = scenario.startedAt,
        endedAtMs = scenario.endedAt,
        samples = samples.map { sample ->
            SampleUpload(
                // Enum NAME, not the lowercase label used by the local CSV export.
                sensorType = sample.sensorType.name,
                timestampMs = sample.timestampMs,
                value = sample.value
            )
        }
    )
}
