package com.vitalwork.app.data.export

import com.vitalwork.app.data.db.ParticipantEntity
import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SensorSampleEntity
import com.vitalwork.app.data.db.SensorType
import com.vitalwork.app.data.db.SessionEntity
import com.vitalwork.app.data.export.model.GapExport
import com.vitalwork.app.data.export.model.ParticipantExport
import com.vitalwork.app.data.export.model.ScenarioExport
import com.vitalwork.app.data.export.model.ScenarioGaps
import com.vitalwork.app.data.export.model.SensorGapInfo
import com.vitalwork.app.data.export.model.SensorSampleExport
import com.vitalwork.app.data.export.model.SessionExport
import com.vitalwork.app.data.export.model.SessionInfo
import com.vitalwork.app.data.export.model.SessionStatistics
import com.vitalwork.app.data.recording.GapEvent
import com.vitalwork.app.data.recording.detectEsenseRrIntervalGaps
import com.vitalwork.app.data.recording.detectHeartRateGaps
import com.vitalwork.app.data.recording.detectRespirationGaps
import com.vitalwork.app.data.repository.ScenarioRepository
import com.vitalwork.app.data.time.TimeProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionExportMapper @Inject constructor(
    private val scenarioRepository: ScenarioRepository,
    private val timeProvider: TimeProvider
) {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    suspend fun buildExportData(
        participant: ParticipantEntity,
        session: SessionEntity,
        scenarios: List<ScenarioEntity>
    ): SessionExport {
        // Pull each scenario's samples once, then derive both the per-scenario export and the
        // session statistics from the SAME data. Computing the header counts here (rather than
        // reading SessionEntity's stored counters) keeps the JSON summary in lockstep with the
        // file's contents — including scenarios that ended abnormally with a null `endedAt`, which
        // the stored counters exclude (they only sum scenarios with endedAt != null).
        val scenarioSamples = scenarios.map { scenario ->
            scenario to scenarioRepository.getSamplesForScenario(scenario.id)
        }
        val scenarioExports = scenarioSamples.map { (scenario, samples) ->
            buildScenarioExport(scenario, samples)
        }

        val allSamples = scenarioSamples.flatMap { it.second }
        fun countOf(type: SensorType) = allSamples.count { it.sensorType == type }

        return SessionExport(
            exportedAt = isoFormat.format(Date(timeProvider.nowMs())),
            participant = ParticipantExport(
                participantCode = participant.participantCode,
                age = participant.age,
                gender = participant.gender
            ),
            session = SessionInfo(
                sessionCode = session.sessionCode,
                startedAt = isoFormat.format(Date(session.startedAt)),
                endedAt = session.endedAt?.let { isoFormat.format(Date(it)) },
                status = session.status.name,
                notes = session.notes,
                statistics = SessionStatistics(
                    scenarioCount = session.scenarioCount,
                    hrSampleCount = session.hrSampleCount,
                    respirationSampleCount = session.respirationSampleCount,
                    rrIntervalSampleCount = session.rrIntervalSampleCount,
                    edaSampleCount = session.edaSampleCount,
                    watchHrSampleCount = session.watchHrSampleCount,
                    watchIbiSampleCount = session.watchIbiSampleCount
                )
            ),
            scenarios = scenarioExports
        )
    }

    fun buildScenarioExport(
        scenario: ScenarioEntity,
        samples: List<SensorSampleEntity>
    ): ScenarioExport {
        val sampleExports = samples.map { sample ->
            SensorSampleExport(
                timestampMs = sample.timestampMs,
                elapsedMs = sample.elapsedMs,
                sensorType = when (sample.sensorType) {
                    SensorType.ESENSE_HEART_RATE -> "esense_heart_rate"
                    SensorType.ESENSE_RR_INTERVAL -> "rr_interval"
                    SensorType.RESPIRATION -> "respiration"
                    SensorType.WATCH_HR -> "watch_hr"
                    SensorType.WATCH_IBI -> "watch_ibi"
                    SensorType.WATCH_EDA -> "watch_eda"
                },
                value = sample.value
            )
        }

        val hrGaps = detectHeartRateGaps(samples)
        val rrGaps = detectEsenseRrIntervalGaps(samples)
        val respGaps = detectRespirationGaps(samples)

        val gaps = if (hrGaps.isEmpty() && rrGaps.isEmpty() && respGaps.isEmpty()) {
            null
        } else {
            ScenarioGaps(
                heartRate = gapInfoOrNull(hrGaps),
                rrInterval = gapInfoOrNull(rrGaps),
                respiration = gapInfoOrNull(respGaps)
            )
        }

        val reactionTimeMs = if (
            scenario.eventTimestampMs != null && scenario.reactionTimestampMs != null
        ) {
            scenario.reactionTimestampMs - scenario.eventTimestampMs
        } else {
            null
        }

        return ScenarioExport(
            scenarioCode = scenario.scenarioCode.name,
            scenarioCategory = scenario.scenarioCategory.name,
            startedAt = isoFormat.format(Date(scenario.startedAt)),
            endedAt = scenario.endedAt?.let { isoFormat.format(Date(it)) },
            eventTimestampMs = scenario.eventTimestampMs,
            reactionTimestampMs = scenario.reactionTimestampMs,
            reactionTimeMs = reactionTimeMs,
            gaps = gaps,
            samples = sampleExports
        )
    }

    private fun gapInfoOrNull(gaps: List<GapEvent>): SensorGapInfo? =
        if (gaps.isEmpty()) null
        else SensorGapInfo(
            gapCount = gaps.size,
            gapTotalMs = gaps.sumOf { it.gapMs },
            gaps = gaps.map { GapExport(it.startElapsedMs, it.endElapsedMs, it.gapMs) }
        )
}
