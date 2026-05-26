package com.biometrix.operator.data.recording

import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType

data class GapEvent(val startElapsedMs: Long, val endElapsedMs: Long) {
    val gapMs: Long get() = endElapsedMs - startElapsedMs
}

fun detectHeartRateGaps(
    samples: List<SensorSampleEntity>,
    minGapMs: Long = 5_000L,
    startupThresholdMs: Long = 10_000L
): List<GapEvent> = detectGaps(SensorType.HEART_RATE, samples, minGapMs, startupThresholdMs)

fun detectRespirationGaps(
    samples: List<SensorSampleEntity>,
    minGapMs: Long = 5_000L,
    startupThresholdMs: Long = 10_000L
): List<GapEvent> = detectGaps(SensorType.RESPIRATION, samples, minGapMs, startupThresholdMs)

fun detectFibionHeartRateGaps(
    samples: List<SensorSampleEntity>,
    minGapMs: Long = 5_000L,
    startupThresholdMs: Long = 10_000L
): List<GapEvent> = detectGaps(SensorType.FIBION_HEART_RATE, samples, minGapMs, startupThresholdMs)

fun detectFibionEcgGaps(
    samples: List<SensorSampleEntity>,
    minGapMs: Long = 5_000L,
    startupThresholdMs: Long = 10_000L
): List<GapEvent> = detectGaps(SensorType.FIBION_ECG, samples, minGapMs, startupThresholdMs)

fun detectFibionRrIntervalGaps(
    samples: List<SensorSampleEntity>,
    minGapMs: Long = 5_000L,
    startupThresholdMs: Long = 10_000L
): List<GapEvent> = detectGaps(SensorType.FIBION_RR_INTERVAL, samples, minGapMs, startupThresholdMs)

fun detectEsenseRrIntervalGaps(
    samples: List<SensorSampleEntity>,
    minGapMs: Long = 5_000L,
    startupThresholdMs: Long = 10_000L
): List<GapEvent> = detectGaps(SensorType.ESENSE_RR_INTERVAL, samples, minGapMs, startupThresholdMs)

private fun detectGaps(
    sensorType: SensorType,
    samples: List<SensorSampleEntity>,
    minGapMs: Long,
    startupThresholdMs: Long
): List<GapEvent> {
    val filtered = samples
        .filter { it.sensorType == sensorType }
        .sortedBy { it.elapsedMs }

    return filtered
        .zipWithNext()
        .filter { (a, b) -> b.elapsedMs - a.elapsedMs > minGapMs }
        .filter { (a, _) -> a.elapsedMs >= startupThresholdMs }
        .map { (a, b) -> GapEvent(a.elapsedMs, b.elapsedMs) }
}
