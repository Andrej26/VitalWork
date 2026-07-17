package com.vitalwork.app.data.export.upload

import com.vitalwork.app.data.db.ParticipantEntity
import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SensorSampleEntity
import com.vitalwork.app.data.db.SensorType
import com.vitalwork.app.data.db.SessionEntity
import com.vitalwork.app.data.repository.ScenarioRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps the local Room entities to the server's full-session upload shape ([SessionUploadRequest]).
 *
 * Unlike [com.vitalwork.app.data.export.SessionExportMapper] (ISO strings, gaps), this emits raw
 * epoch-millisecond timestamps straight off the entities and **enum-NAME** sensor types, which is
 * what `POST /api/sessions/upload` expects (doc §6/§7). No gap computation. The statistics block
 * (doc §4.2) is counted from the samples actually being uploaded — the server persists it rather
 * than recomputing, so it must always match the uploaded payload (the stored session counters can
 * lag it, e.g. for a scenario that ended abnormally).
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
        val scenarioSamples = scenarios.map { scenario ->
            scenario to scenarioRepository.getSamplesForScenario(scenario.id)
        }
        val scenarioUploads = scenarioSamples.map { (scenario, samples) ->
            buildScenarioUpload(scenario, samples)
        }

        val allSamples = scenarioSamples.flatMap { it.second }
        fun countOf(type: SensorType) = allSamples.count { it.sensorType == type }

        return SessionUploadRequest(
            participant = ParticipantUpload(
                participantCode = participant.participantCode,
                age = participant.age,
                gender = participant.gender
            ),
            session = SessionUpload(
                sessionCode = session.sessionCode,
                startedAt = session.startedAt,
                endedAt = session.endedAt,
                status = session.status.name,
                statistics = SessionStatisticsUpload(
                    scenarioCount = scenarios.size,
                    hrSampleCount = countOf(SensorType.ESENSE_HEART_RATE),
                    respirationSampleCount = countOf(SensorType.RESPIRATION),
                    rrIntervalSampleCount = countOf(SensorType.ESENSE_RR_INTERVAL),
                    edaSampleCount = countOf(SensorType.WATCH_EDA),
                    watchHrSampleCount = countOf(SensorType.WATCH_HR),
                    watchIbiSampleCount = countOf(SensorType.WATCH_IBI)
                )
            ),
            scenarios = scenarioUploads
        )
    }

    fun buildScenarioUpload(
        scenario: ScenarioEntity,
        samples: List<SensorSampleEntity>
    ): ScenarioUpload = ScenarioUpload(
        scenarioCode = scenario.scenarioCode.name,
        startedAt = scenario.startedAt,
        endedAt = scenario.endedAt,
        samples = samples.map { sample ->
            SampleUpload(
                // Enum NAME, not the lowercase label used by the local CSV export.
                sensorType = sample.sensorType.name,
                timestampMs = sample.timestampMs,
                elapsedMs = sample.elapsedMs,
                value = sample.value
            )
        }
    )
}
