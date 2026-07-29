package com.vitalwork.app.data.export

import com.vitalwork.app.data.db.ParticipantEntity
import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SensorSampleEntity
import com.vitalwork.app.data.db.SensorType
import com.vitalwork.app.data.db.SessionEntity
import com.vitalwork.app.data.export.model.GapExport
import com.vitalwork.app.data.export.model.ParticipantExport
import com.vitalwork.app.data.export.model.RespirationIssueExport
import com.vitalwork.app.data.export.model.RespirationIssues
import com.vitalwork.app.data.export.model.ScenarioExport
import com.vitalwork.app.data.export.model.ScenarioGaps
import com.vitalwork.app.data.export.model.SensorGapInfo
import com.vitalwork.app.data.export.model.SensorSampleExport
import com.vitalwork.app.data.export.model.SessionExport
import com.vitalwork.app.data.export.model.SessionInfo
import com.vitalwork.app.data.export.model.SessionStatistics
import com.vitalwork.app.data.recording.GapEvent
import com.vitalwork.app.data.recording.RespirationIssue
import com.vitalwork.app.data.recording.RespirationIssueEvent
import com.vitalwork.app.data.recording.detectEsenseRrIntervalGaps
import com.vitalwork.app.data.recording.detectHeartRateGaps
import com.vitalwork.app.data.recording.detectRespirationGaps
import com.vitalwork.app.data.recording.detectRespirationIssues
import com.vitalwork.app.data.time.TimeProvider
import com.vitalwork.app.util.TimeFormats
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionExportMapper @Inject constructor(
    private val sampleCollector: ScenarioSampleCollector,
    private val timeProvider: TimeProvider
) {
    fun buildExportData(
        participant: ParticipantEntity,
        session: SessionEntity,
        scenarioSamples: List<Pair<ScenarioEntity, List<SensorSampleEntity>>>
    ): SessionExport {
        // Each scenario's samples are collected once by the caller (shared with the CSV writer),
        // then this derives both the per-scenario export and the session statistics from the SAME
        // data. Computing the header counts here (rather than reading SessionEntity's stored
        // counters) keeps the JSON summary in lockstep with the file's contents — including
        // scenarios that ended abnormally with a null `endedAt`, which the stored counters exclude
        // (they only sum scenarios with endedAt != null).
        val scenarioExports = scenarioSamples.map { (scenario, samples) ->
            buildScenarioExport(scenario, samples)
        }

        val counts = sampleCollector.countSamples(scenarioSamples)

        return SessionExport(
            exportedAt = TimeFormats.iso(timeProvider.nowMs()),
            participant = ParticipantExport(
                participantCode = participant.participantCode,
                age = participant.age,
                gender = participant.gender
            ),
            session = SessionInfo(
                sessionCode = session.sessionCode,
                startedAt = TimeFormats.iso(session.startedAt),
                endedAt = session.endedAt?.let { TimeFormats.iso(it) },
                status = session.status.name,
                statistics = SessionStatistics(
                    scenarioCount = scenarioSamples.size,
                    hrSampleCount = counts.hrSampleCount,
                    respirationSampleCount = counts.respirationSampleCount,
                    rrIntervalSampleCount = counts.rrIntervalSampleCount,
                    edaSampleCount = counts.edaSampleCount,
                    watchHrSampleCount = counts.watchHrSampleCount,
                    watchIbiSampleCount = counts.watchIbiSampleCount
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

        return ScenarioExport(
            scenarioCode = scenario.scenarioCode.name,
            startedAt = TimeFormats.iso(scenario.startedAt),
            endedAt = scenario.endedAt?.let { TimeFormats.iso(it) },
            gaps = gaps,
            respirationIssues = respirationIssuesOrNull(detectRespirationIssues(samples)),
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

    private fun respirationIssuesOrNull(events: List<RespirationIssueEvent>): RespirationIssues? {
        if (events.isEmpty()) return null
        val (signalLost, noBreathing) = events.partition {
            it.reason == RespirationIssue.SIGNAL_LOST
        }
        return RespirationIssues(
            signalLostCount = signalLost.size,
            signalLostTotalMs = signalLost.sumOf { it.durationMs },
            noBreathingCount = noBreathing.size,
            noBreathingTotalMs = noBreathing.sumOf { it.durationMs },
            events = events.map {
                RespirationIssueExport(
                    reason = it.reason.name,
                    startElapsedMs = it.startElapsedMs,
                    endElapsedMs = it.endElapsedMs,
                    durationMs = it.durationMs
                )
            }
        )
    }
}
