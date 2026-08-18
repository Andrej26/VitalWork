package com.vitalwork.app.data.recording

import com.vitalwork.app.data.db.SensorSampleEntity
import com.vitalwork.app.data.db.SensorType
import com.vitalwork.app.data.sensor.audio.BreathingRateEstimator
import com.vitalwork.app.data.sensor.audio.MindfieldRespiration

data class GapEvent(val startElapsedMs: Long, val endElapsedMs: Long) {
    val gapMs: Long get() = endElapsedMs - startElapsedMs
}

fun detectHeartRateGaps(
    samples: List<SensorSampleEntity>,
    minGapMs: Long = 5_000L,
    startupThresholdMs: Long = 10_000L
): List<GapEvent> = detectGaps(SensorType.ESENSE_HEART_RATE, samples, minGapMs, startupThresholdMs)

fun detectRespirationGaps(
    samples: List<SensorSampleEntity>,
    minGapMs: Long = 5_000L,
    startupThresholdMs: Long = 10_000L
): List<GapEvent> = detectGaps(SensorType.RESPIRATION, samples, minGapMs, startupThresholdMs)

fun detectEsenseRrIntervalGaps(
    samples: List<SensorSampleEntity>,
    minGapMs: Long = 5_000L,
    startupThresholdMs: Long = 10_000L
): List<GapEvent> = detectGaps(SensorType.ESENSE_RR_INTERVAL, samples, minGapMs, startupThresholdMs)

/** Why a stretch of respiration recording is unusable. */
enum class RespirationIssue {
    /**
     * RA collapsed below [MindfieldRespiration.SIGNAL_LOST_THRESHOLD_RA] — the strap lost chest
     * contact. Unambiguous: a worn strap reads 150–300 RA, a fallen one under 1.
     */
    SIGNAL_LOST,

    /**
     * RA stayed in a normal range but the waveform did not track breathing — a strap that is on the
     * chest but not coupled, or a genuine breath hold. **The two cannot be told apart from the
     * waveform**, so the name is deliberately neutral and the analyst judges from context.
     */
    NO_BREATHING
}

data class RespirationIssueEvent(
    val reason: RespirationIssue,
    val startElapsedMs: Long,
    val endElapsedMs: Long
) {
    val durationMs: Long get() = endElapsedMs - startElapsedMs
}

/** Gaps in respiration sampling wider than this end a run — the sensor was not delivering data. */
private const val ISSUE_RUN_BREAK_MS = 1_000L

/** A signal-lost stretch must last this long to be reported (shorter is a transient, not a slip). */
private const val SIGNAL_LOST_MIN_MS = 2_000L

/** A no-breathing stretch must last this long — matches the on-screen banner's debounce. */
private const val NO_BREATHING_MIN_MS = 20_000L

/** Window over which the waveform's amplitude is judged when looking for flat stretches. */
private const val NO_BREATHING_WINDOW_MS = 10_000L

/**
 * Derives, from the recorded RA waveform, the stretches of a scenario where respiration was not
 * being captured — the thing `detectRespirationGaps` cannot see, because a strap that slips while
 * the cable stays plugged in keeps delivering 5 samples a second and leaves no gap at all.
 *
 * Computed at export time from samples that are already stored, so nothing changes in the database
 * and this can be re-run over sessions recorded earlier.
 *
 * Returns an empty list when there are no respiration samples: **absence of data is not a lost
 * signal**, it means the sensor was never connected for this scenario.
 */
fun detectRespirationIssues(
    samples: List<SensorSampleEntity>,
    sampleFreq: Int = RESPIRATION_SAMPLE_FREQ
): List<RespirationIssueEvent> {
    val resp = samples
        .filter { it.sensorType == SensorType.RESPIRATION }
        .sortedBy { it.elapsedMs }
    if (resp.isEmpty()) return emptyList()

    return (detectSignalLost(resp) + detectNoBreathing(resp, sampleFreq))
        .sortedBy { it.startElapsedMs }
}

/** Sampling rate the respiration sensor is configured for; the waveform analysis assumes it. */
const val RESPIRATION_SAMPLE_FREQ = 5

/**
 * Contiguous runs of samples below the strap-off threshold. Exact to the sample — no windowing
 * needed, because breathing and a fallen strap differ by roughly two orders of magnitude.
 */
private fun detectSignalLost(resp: List<SensorSampleEntity>): List<RespirationIssueEvent> {
    val events = mutableListOf<RespirationIssueEvent>()
    var runStart: Long? = null
    var runEnd = 0L
    var previousElapsed = 0L

    fun closeRun() {
        val start = runStart ?: return
        if (runEnd - start >= SIGNAL_LOST_MIN_MS) {
            events += RespirationIssueEvent(RespirationIssue.SIGNAL_LOST, start, runEnd)
        }
        runStart = null
    }

    for (sample in resp) {
        val low = sample.value < MindfieldRespiration.SIGNAL_LOST_THRESHOLD_RA
        // A hole in the sampling ends the run: the sensor dropped out, and the time in between is a
        // gap (already reported as one), not a measured stretch of lost signal.
        if (runStart != null && sample.elapsedMs - previousElapsed > ISSUE_RUN_BREAK_MS) closeRun()

        if (low) {
            if (runStart == null) runStart = sample.elapsedMs
            runEnd = sample.elapsedMs
        } else {
            closeRun()
        }
        previousElapsed = sample.elapsedMs
    }
    closeRun()
    return events
}

/**
 * Stretches where RA was healthy but its excursion was too small to be breathing. Uses the same
 * detrending and amplitude rule as the live estimator, so the screen and the export judge the
 * waveform identically.
 *
 * The window is evaluated every [NO_BREATHING_WINDOW_MS] / 10 of a second rather than at every
 * sample: against a 20-second minimum, ±0.2 s of edge precision is irrelevant and the cheaper scan
 * keeps a 30-minute scenario trivial to process.
 */
private fun detectNoBreathing(
    resp: List<SensorSampleEntity>,
    sampleFreq: Int
): List<RespirationIssueEvent> {
    val values = resp.map { it.value.toDouble() }
    val windowSamples = (NO_BREATHING_WINDOW_MS / 1000.0 * sampleFreq).toInt()
    if (values.size < windowSamples) return emptyList()

    val detrended = BreathingRateEstimator.detrend(values, sampleFreq)
    val half = windowSamples / 2
    val step = maxOf(1, sampleFreq) // one evaluation per second

    // Flat-window flags on the sampled grid, then expanded back to full sample resolution.
    val flat = BooleanArray(detrended.size)
    var i = 0
    while (i < detrended.size) {
        val lo = maxOf(0, i - half)
        val hi = minOf(detrended.size, i + half + 1)
        val isFlat = BreathingRateEstimator.amplitude(detrended, lo, hi) <
            BreathingRateEstimator.MIN_AMPLITUDE_RA
        for (j in i until minOf(i + step, detrended.size)) flat[j] = isFlat
        i += step
    }

    val events = mutableListOf<RespirationIssueEvent>()
    var runStart = -1
    for (index in flat.indices) {
        if (flat[index]) {
            if (runStart < 0) runStart = index
        } else if (runStart >= 0) {
            addNoBreathingRun(events, resp, runStart, index - 1, half)
            runStart = -1
        }
    }
    if (runStart >= 0) addNoBreathingRun(events, resp, runStart, flat.lastIndex, half)
    return events
}

/**
 * Records a flat run, widening it by half the analysis window on each side: a centred window only
 * reports "flat" once it is fully inside the flat stretch, so the raw run is eroded by that much.
 */
private fun addNoBreathingRun(
    events: MutableList<RespirationIssueEvent>,
    resp: List<SensorSampleEntity>,
    firstIndex: Int,
    lastIndex: Int,
    half: Int
) {
    val start = resp[maxOf(0, firstIndex - half)].elapsedMs
    val end = resp[minOf(resp.lastIndex, lastIndex + half)].elapsedMs
    if (end - start >= NO_BREATHING_MIN_MS) {
        events += RespirationIssueEvent(RespirationIssue.NO_BREATHING, start, end)
    }
}

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
