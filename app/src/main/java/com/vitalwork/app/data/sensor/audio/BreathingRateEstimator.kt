package com.vitalwork.app.data.sensor.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Outcome of a breathing-rate estimate over a window of raw RA samples.
 *
 * **Display only.** Nothing here is persisted — `SensorType.RESPIRATION` samples are the raw
 * Respiration Amplitude waveform, and analysis re-derives breathing rate from that waveform offline.
 * This exists so the operator can sanity-check the chest strap during a session.
 */
sealed interface BreathingRateEstimate {

    /** Too few samples since streaming (re)started to resolve two breaths yet. */
    data object Warmup : BreathingRateEstimate

    /**
     * Samples are arriving but no breathing can be resolved: the waveform is too flat, or no breath
     * has been seen for [BreathingRateEstimator.STALE_SECONDS]. Deliberately distinct from a numeric
     * `0` — "the sensor is not picking up breathing" is an instruction to the operator, not a
     * measurement.
     */
    data object NoBreathing : BreathingRateEstimate

    /**
     * Breaths resolved. [amplitudeRa] is the detected peak-to-peak excursion, logged so
     * [BreathingRateEstimator.MIN_AMPLITUDE_RA] can be calibrated against real hardware.
     */
    data class Rate(val brPerMin: Float, val amplitudeRa: Float) : BreathingRateEstimate
}

/**
 * Derives a breathing rate from a window of raw RA samples.
 *
 * Replaces counting how often the raw waveform crosses its own window mean. That approach has three
 * defects avoided here:
 *
 * 1. **Quantization.** Counting `n` breaths over a fixed window can only yield multiples of
 *    `60 / window_seconds` — 2 br/min on a 30 s window. Here the rate comes from *inter-breath
 *    intervals*, so it is continuous.
 * 2. **Slow response.** A 30 s window takes ~24 s to follow a real change; the 15 s interval horizon
 *    here takes ~8 s.
 * 3. **Baseline drift.** A window mean is dragged along by a posture change, hiding real breaths.
 *    Here a slow moving baseline is subtracted first, so only the breathing band is examined.
 *
 * It also reports "no breathing" as its own outcome rather than as a plausible-looking number — a
 * flat strap reads as ~72 br/min under the old method.
 *
 * All constants were tuned against a real 3 × 60 s recording (13 / 26 / 46 br/min, the last one a
 * deliberate hyperventilation) plus synthetic signals across the whole 4–60 br/min range. All
 * functions are pure; state lives in the caller's sample buffer.
 */
object BreathingRateEstimator {

    /**
     * Smoothing window. **Must stay short.** A moving average of length T nulls the frequency 1/T,
     * so a 1.0 s window annihilates 60 br/min breathing entirely and cuts 46 br/min to 28 % — fast
     * breathing simply disappears. At 0.6 s the same rates retain 50 % / 69 %, and since the
     * detection threshold is a *fraction of the measured amplitude*, uniform attenuation is harmless.
     */
    const val SMOOTHING_SECONDS = 0.6

    /**
     * Baseline window subtracted from the smoothed signal to remove posture drift. Insensitive
     * anywhere in 8–30 s; 15 s preserves breathing down to [MIN_PLAUSIBLE_BR_PER_MIN].
     */
    const val BASELINE_SECONDS = 15.0

    /** Below this much data no estimate is attempted at all. */
    const val WARMUP_SECONDS = 12.0

    /**
     * Until the buffer holds this much, "fewer than two breaths found" is [BreathingRateEstimate.Warmup]
     * rather than [BreathingRateEstimate.NoBreathing] — a genuinely slow breather needs a long window
     * before a second breath appears.
     */
    const val SLOW_BREATH_PATIENCE_SECONDS = 35.0

    /**
     * Amplitude is judged over only the most recent slice of the buffer, not all of it. A posture
     * change moves the RA baseline by ~100 RA — roughly nine times a calm breath — and over a full
     * 60 s buffer that single transient inflates the amplitude, widens the hysteresis band, and then
     * swallows real breaths for a whole minute afterwards. Measured on a 10-minute recording: with
     * the full buffer the rate collapsed to 5.4 br/min for several seconds after the strap was
     * refitted; over 25 s it recovers roughly twice as fast.
     */
    const val AMPLITUDE_WINDOW_SECONDS = 25.0

    /**
     * Interquartile amplitude (RA) below which the waveform counts as flat. Measured on real
     * recordings: calm seated breathing gives 17–22, **breathing while leaning forward gives only
     * 3.5–4.8**, and smoothed sensor noise on a motionless strap gives 0.4–0.7. 1.8 sits between
     * with roughly 2× margin on each side.
     *
     * An earlier value of 5.0 landed exactly on the leaning-forward amplitude and produced a false
     * "no breathing" in a real session. Logged at every verdict change so it stays checkable.
     */
    const val MIN_AMPLITUDE_RA = 1.8

    /**
     * Half-width of the hysteresis band, as a fraction of the detected amplitude. A breath onset
     * needs the signal to first fall below `-band` and then rise above `+band`, so a wobble around
     * the baseline cannot register as breathing. Scaled for the interquartile amplitude, which for a
     * sine runs about 0.74× the p10–p90 span this was originally tuned against.
     */
    const val HYSTERESIS_FRACTION = 0.14

    /**
     * Minimum spacing between breath onsets. **Not 1.5 s** — that caps the estimate at 40 br/min,
     * and a voluntary hyperventilation in this study's own test data reached 46.
     */
    const val REFRACTORY_SECONDS = 0.8

    /**
     * Only onsets within this much of the newest sample feed the reported rate. Response to a
     * 12 → 20 br/min step: 10 s → 6 s, **15 s → 8 s**, 20 s → 10 s, 30 s → 26 s.
     */
    const val RATE_HORIZON_SECONDS = 15.0

    /**
     * If the horizon holds fewer onsets than this, reach further back rather than reporting a rate
     * from a single interval. One interval has no outlier protection at all, so a single missed
     * breath halves the reading — which is what produced the sub-8 br/min readings a real session
     * showed right after standing up. Raising this from 2 to 4 fixed that (6.8 → 12.5 br/min at the
     * moment of standing) without disturbing genuinely slow breathing, which still reads correctly
     * down to 4 br/min.
     */
    const val MIN_RATE_ONSETS = 4

    /**
     * Intervals outside this band around the median are dropped before averaging. At 5 Hz the
     * intervals themselves are quantized to 0.2 s — a 15 % step at 46 br/min — so a plain median
     * jumps by 7–9 %; averaging the survivors brings that to 0.7 % while still surviving a single
     * missed breath.
     */
    const val TRIM_LOW_FACTOR = 0.6
    const val TRIM_HIGH_FACTOR = 1.6

    /** Rates outside this range are treated as detection failures rather than measurements. */
    const val MIN_PLAUSIBLE_BR_PER_MIN = 4.0
    const val MAX_PLAUSIBLE_BR_PER_MIN = 60.0

    /** No breath onset for this long ⇒ [BreathingRateEstimate.NoBreathing], not a stale number. */
    const val STALE_SECONDS = 20.0

    /**
     * @param samples raw RA samples, oldest first, evenly spaced at [sampleFreq] Hz.
     * @param sampleFreq sampling rate of [samples] in Hz.
     */
    fun estimate(samples: List<Double>, sampleFreq: Int): BreathingRateEstimate {
        require(sampleFreq > 0) { "sampleFreq must be positive, was $sampleFreq" }

        if (samples.size < samplesIn(WARMUP_SECONDS, sampleFreq)) return BreathingRateEstimate.Warmup

        val detrended = detrend(samples, sampleFreq)

        // Only the recent slice: an old posture change must not keep widening the band (see
        // AMPLITUDE_WINDOW_SECONDS).
        val amplitudeFrom =
            max(0, detrended.size - samplesIn(AMPLITUDE_WINDOW_SECONDS, sampleFreq))
        val amplitude = amplitude(detrended, amplitudeFrom, detrended.size)
        if (amplitude < MIN_AMPLITUDE_RA) return BreathingRateEstimate.NoBreathing

        val onsets = findBreathOnsets(
            detrended = detrended,
            halfBand = amplitude * HYSTERESIS_FRACTION,
            refractorySamples = max(1, samplesIn(REFRACTORY_SECONDS, sampleFreq))
        )
        if (onsets.size < 2) {
            return if (samples.size < samplesIn(SLOW_BREATH_PATIENCE_SECONDS, sampleFreq)) {
                BreathingRateEstimate.Warmup
            } else {
                BreathingRateEstimate.NoBreathing
            }
        }

        // A rate built only from breaths that all happened a while ago is a stale reading, not a
        // current one — the strap may have slipped since.
        val newest = detrended.size - 1
        if ((newest - onsets.last()).toDouble() / sampleFreq > STALE_SECONDS) {
            return BreathingRateEstimate.NoBreathing
        }

        val horizon = samplesIn(RATE_HORIZON_SECONDS, sampleFreq)
        val recent = onsets.filter { newest - it <= horizon }
            .takeIf { it.size >= MIN_RATE_ONSETS }
            ?: onsets.takeLast(MIN_RATE_ONSETS)

        val intervals = recent.zipWithNext { a, b -> (b - a).toDouble() / sampleFreq }
        val median = median(intervals)
        val kept = intervals.filter {
            it >= median * TRIM_LOW_FACTOR && it <= median * TRIM_HIGH_FACTOR
        }.ifEmpty { intervals }

        val brPerMin = 60.0 / kept.average()
        return if (brPerMin < MIN_PLAUSIBLE_BR_PER_MIN || brPerMin > MAX_PLAUSIBLE_BR_PER_MIN) {
            BreathingRateEstimate.NoBreathing
        } else {
            BreathingRateEstimate.Rate(brPerMin.toFloat(), amplitude.toFloat())
        }
    }

    /**
     * Smooths [samples], then subtracts a slow moving baseline. What remains is the breathing-band
     * component centred on zero, free of both sensor noise and posture drift.
     *
     * Shared with the offline export-time detection in
     * [com.vitalwork.app.data.recording.detectRespirationIssues], so screen and export judge the
     * waveform by the same rule.
     */
    internal fun detrend(samples: List<Double>, sampleFreq: Int): DoubleArray {
        val smoothed = centeredMovingAverage(samples, oddWindow(SMOOTHING_SECONDS, sampleFreq))
        val baseline = centeredMovingAverage(
            smoothed.asList(), oddWindow(BASELINE_SECONDS, sampleFreq)
        )
        return DoubleArray(smoothed.size) { smoothed[it] - baseline[it] }
    }

    /**
     * Excursion of `[from, to)` as the **interquartile** range.
     *
     * Deliberately not p10–p90: one deep breath among ten is about 9 % of the samples, which p10–p90
     * barely trims but the quartiles ignore outright. Using the wider span let a single deep breath
     * (or the tail of a posture change) set the hysteresis band for everything that followed, so the
     * ordinary breaths after it went undetected.
     */
    internal fun amplitude(detrended: DoubleArray, from: Int, to: Int): Double {
        if (to - from <= 0) return 0.0
        val window = detrended.copyOfRange(from, to)
        window.sort()
        return percentileOfSorted(window, 0.75) - percentileOfSorted(window, 0.25)
    }

    /**
     * Indices at which a breath begins: a rise above `+halfBand` that follows a dip below
     * `-halfBand`, at least [refractorySamples] after the previous onset.
     *
     * A rise always consumes the armed state even when the refractory period rejects it, so one long
     * rising flank can never register as several breaths.
     */
    internal fun findBreathOnsets(
        detrended: DoubleArray,
        halfBand: Double,
        refractorySamples: Int
    ): List<Int> {
        val onsets = mutableListOf<Int>()
        var armed = false
        var lastOnset = -refractorySamples
        for (i in detrended.indices) {
            val v = detrended[i]
            if (v < -halfBand) {
                armed = true
            } else if (armed && v > halfBand) {
                if (i - lastOnset >= refractorySamples) {
                    onsets += i
                    lastOnset = i
                }
                armed = false
            }
        }
        return onsets
    }

    /**
     * Moving average centred on each sample, with the window truncated at the edges. Centring keeps
     * the result in phase with the input, which matters because it is subtracted from it.
     */
    internal fun centeredMovingAverage(values: List<Double>, window: Int): DoubleArray {
        val n = values.size
        val out = DoubleArray(n)
        if (n == 0) return out

        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + values[i]

        val half = window / 2
        for (i in 0 until n) {
            val lo = max(0, i - half)
            val hi = min(n - 1, i + half)
            out[i] = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
        }
        return out
    }

    internal fun samplesIn(seconds: Double, sampleFreq: Int): Int = (seconds * sampleFreq).toInt()

    /** Window length in samples, forced odd so the average can be centred on a sample. */
    private fun oddWindow(seconds: Double, sampleFreq: Int): Int {
        val w = max(1, (seconds * sampleFreq).roundToInt())
        return if (w % 2 == 0) w + 1 else w
    }

    private fun percentileOfSorted(sorted: DoubleArray, p: Double): Double {
        val idx = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
