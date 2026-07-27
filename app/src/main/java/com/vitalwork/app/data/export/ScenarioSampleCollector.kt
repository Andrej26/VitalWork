package com.vitalwork.app.data.export

import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SensorSampleEntity
import com.vitalwork.app.data.db.SensorType
import com.vitalwork.app.data.repository.ScenarioRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared first step for [SessionExportMapper] and
 * [com.vitalwork.app.data.export.upload.SessionUploadMapper]: pull each scenario's samples once and
 * count them per [SensorType].
 *
 * Both mappers derive their `statistics` block from the samples they actually emit (never from
 * [com.vitalwork.app.data.db.SessionEntity]'s stored counters, which can lag — e.g. a scenario that
 * ended abnormally with a null `endedAt`). Centralising the load + count here guarantees the export
 * and upload counts can never drift apart. Output *shaping* (ISO strings + gaps vs. epoch-ms upload
 * DTOs) stays in each mapper.
 */
@Singleton
class ScenarioSampleCollector @Inject constructor(
    private val scenarioRepository: ScenarioRepository
) {
    /** Pulls each scenario's samples once, preserving scenario order and the DAO's sample order. */
    suspend fun collect(
        scenarios: List<ScenarioEntity>
    ): List<Pair<ScenarioEntity, List<SensorSampleEntity>>> =
        scenarios.map { scenario ->
            scenario to scenarioRepository.getSamplesForScenario(scenario.id)
        }

    /** Counts every sample across all scenarios, grouped by sensor type. */
    fun countSamples(
        scenarioSamples: List<Pair<ScenarioEntity, List<SensorSampleEntity>>>
    ): SampleCounts {
        val allSamples = scenarioSamples.flatMap { it.second }
        fun countOf(type: SensorType) = allSamples.count { it.sensorType == type }
        return SampleCounts(
            hrSampleCount = countOf(SensorType.ESENSE_HEART_RATE),
            respirationSampleCount = countOf(SensorType.RESPIRATION),
            rrIntervalSampleCount = countOf(SensorType.ESENSE_RR_INTERVAL),
            edaSampleCount = countOf(SensorType.WATCH_EDA),
            watchHrSampleCount = countOf(SensorType.WATCH_HR),
            watchIbiSampleCount = countOf(SensorType.WATCH_IBI)
        )
    }
}

/**
 * Per-sensor-type sample counts shared by both mappers' statistics blocks. Deliberately omits
 * `scenarioCount` (each mapper fills that from `scenarios.size`).
 */
data class SampleCounts(
    val hrSampleCount: Int,
    val respirationSampleCount: Int,
    val rrIntervalSampleCount: Int,
    val edaSampleCount: Int,
    val watchHrSampleCount: Int,
    val watchIbiSampleCount: Int
)
