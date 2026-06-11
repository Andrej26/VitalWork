package com.biometrix.operator.data.export

import com.biometrix.operator.data.db.ParticipantEntity
import com.biometrix.operator.data.db.ScenarioEntity
import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.export.model.GapExport
import com.biometrix.operator.data.export.model.ParticipantExport
import com.biometrix.operator.data.export.model.ScenarioExport
import com.biometrix.operator.data.export.model.ScenarioGaps
import com.biometrix.operator.data.export.model.SensorGapInfo
import com.biometrix.operator.data.export.model.SensorSampleExport
import com.biometrix.operator.data.export.model.SessionExport
import com.biometrix.operator.data.export.model.SessionInfo
import com.biometrix.operator.data.export.model.SessionStatistics
import com.biometrix.operator.data.recording.GapEvent
import com.biometrix.operator.data.recording.detectEsenseRrIntervalGaps
import com.biometrix.operator.data.recording.detectHeartRateGaps
import com.biometrix.operator.data.recording.detectRespirationGaps
import com.biometrix.operator.data.repository.ScenarioRepository
import com.biometrix.operator.data.time.TimeProvider
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
        val scenarioExports = scenarios.map { scenario ->
            val samples = scenarioRepository.getSamplesForScenario(scenario.id)
            buildScenarioExport(scenario, samples)
        }

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
                    edaSampleCount = session.edaSampleCount
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
                    SensorType.HEART_RATE -> "heart_rate"
                    SensorType.ESENSE_RR_INTERVAL -> "rr_interval"
                    SensorType.RESPIRATION -> "respiration"
                    SensorType.EDA -> "eda"
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
