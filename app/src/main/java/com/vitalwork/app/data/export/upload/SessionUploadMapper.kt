package com.vitalwork.app.data.export.upload

import com.vitalwork.app.data.db.ParticipantEntity
import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SensorSampleEntity
import com.vitalwork.app.data.db.SessionEntity
import com.vitalwork.app.data.export.ScenarioSampleCollector
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
    private val sampleCollector: ScenarioSampleCollector
) {

    suspend fun buildUploadRequest(
        participant: ParticipantEntity,
        session: SessionEntity,
        scenarios: List<ScenarioEntity>
    ): SessionUploadRequest {
        val scenarioSamples = sampleCollector.collect(scenarios)
        val scenarioUploads = scenarioSamples.map { (scenario, samples) ->
            buildScenarioUpload(scenario, samples)
        }

        val counts = sampleCollector.countSamples(scenarioSamples)

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
                    hrSampleCount = counts.hrSampleCount,
                    respirationSampleCount = counts.respirationSampleCount,
                    rrIntervalSampleCount = counts.rrIntervalSampleCount,
                    edaSampleCount = counts.edaSampleCount,
                    watchHrSampleCount = counts.watchHrSampleCount,
                    watchIbiSampleCount = counts.watchIbiSampleCount
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
