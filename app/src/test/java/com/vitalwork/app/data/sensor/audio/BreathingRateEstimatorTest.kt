package com.vitalwork.app.data.sensor.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Expected values come from an offline analysis of a real 3 × 60 s eSense Respiration recording
 * (13.2 / 26.1 / 46.0 br/min by spectral analysis, the last a deliberate hyperventilation) plus
 * synthetic sweeps across the supported 4–60 br/min range.
 */
class BreathingRateEstimatorTest {

    private val freq = 5

    // -- Accuracy across the supported range --

    @Test
    fun sineWaves_from4To60BrPerMin_areResolvedWithinOneBreath() {
        for (bpm in listOf(4.0, 6.0, 8.0, 12.0, 20.0, 30.0, 46.0, 60.0)) {
            val rate = rateOf(sine(bpm, seconds = 60.0))
            assertEquals("at $bpm br/min", bpm.toFloat(), rate, 1.0f)
        }
    }

    /**
     * Guards the smoothing window. A 1 s moving average nulls exactly 60 br/min and cuts 46 br/min
     * to 28 %, which made an earlier version report a hyperventilation as ~23 br/min.
     */
    @Test
    fun hyperventilation_isNotSmoothedAway() {
        assertEquals(46.0f, rateOf(sine(46.0, seconds = 60.0)), 1.5f)
    }

    @Test
    fun realRecording_calmBreathing_matchesSpectralGroundTruth() {
        val samples = loadFixture("/respiration_rec1_60s.txt")

        assertEquals(300, samples.size)
        assertEquals(13.2f, rateOf(samples), 1.5f)
    }

    // -- Responsiveness --

    @Test
    fun stepFrom12To20_isFollowedWithinTenSeconds() {
        val signal = step(from = 12.0, to = 20.0, switchAtSec = 40.0, totalSec = 60.0)
        val window = signal.take((50 * freq)).takeLast(60 * freq) // buffer as it stands at t = 50 s

        val rate = rateOf(window)
        assertTrue("Expected the new rate to be visible by t=50s, got $rate", rate >= 19f)
    }

    // -- Robustness --

    @Test
    fun baselineDrift_doesNotDistortTheRate() {
        // 180 RA/min of drift on top of 12 br/min breathing — a posture change mid-scenario.
        val drifting = sine(12.0, seconds = 60.0, driftRaPerSec = 3.0)

        assertEquals(12.0f, rateOf(drifting), 1.0f)
    }

    @Test
    fun shallowBreathing_isStillResolved() {
        assertEquals(12.0f, rateOf(sine(12.0, seconds = 60.0, amplitude = 8.0)), 1.0f)
    }

    @Test
    fun doublePeakWithinRefractoryPeriod_countsAsOneBreath() {
        val halfBand = 1.0
        // Dip, then two rises 0.4 s apart (2 samples at 5 Hz) — under the 0.8 s refractory period.
        val detrended = doubleArrayOf(-5.0, 5.0, -0.5, 5.0, 5.0, 5.0)

        val onsets = BreathingRateEstimator.findBreathOnsets(detrended, halfBand, refractorySamples = 4)

        assertEquals(1, onsets.size)
    }

    // -- "No breathing" is its own answer, not a number --

    @Test
    fun flatSignal_reportsNoBreathing() {
        val flat = List(60 * freq) { 220.0 }

        assertEquals(BreathingRateEstimate.NoBreathing, estimate(flat))
    }

    @Test
    fun sensorNoiseOnAMotionlessStrap_reportsNoBreathing() {
        // Noise at the level measured on real recordings (sd ≈ 1.2 RA) with no breathing under it.
        val rng = Random(7)
        val noiseOnly = List(60 * freq) { 220.0 + rng.nextDouble(-2.0, 2.0) }

        assertEquals(BreathingRateEstimate.NoBreathing, estimate(noiseOnly))
    }

    @Test
    fun amplitudeBelowThreshold_reportsNoBreathing() {
        // Interquartile span of a 0.8 RA sine is ~1.1, under MIN_AMPLITUDE_RA.
        assertEquals(
            BreathingRateEstimate.NoBreathing,
            estimate(sine(12.0, seconds = 60.0, amplitude = 0.8, noise = 0.2))
        )
    }

    /**
     * Real recordings show breathing while leaning forward moves RA by only 3.5–4.8 — an earlier
     * threshold of 5.0 sat exactly there and produced a false "no breathing" in a live session.
     */
    @Test
    fun breathingWhileLeaningForward_isStillReportedAsARate() {
        assertEquals(12.0f, rateOf(sine(12.0, seconds = 60.0, amplitude = 3.0, noise = 0.5)), 1.0f)
    }

    /**
     * One deep breath must not set the detection band for everything after it. With a p10–p90
     * amplitude over the whole buffer it did, and the ordinary breaths that followed went missing.
     */
    @Test
    fun oneDeepBreath_doesNotSwallowTheBreathsAfterIt() {
        val shallow = sine(12.0, seconds = 20.0, amplitude = 10.0)
        val deep = sine(12.0, seconds = 6.0, amplitude = 60.0)
        val after = sine(12.0, seconds = 34.0, amplitude = 10.0)

        assertEquals(12.0f, rateOf(shallow + deep + after), 1.5f)
    }

    @Test
    fun breathsAllOlderThanStaleWindow_reportsNoBreathing() {
        // 20 s of breathing, then 25 s of flat line — the last onset is well past STALE_SECONDS.
        val breathing = sine(12.0, seconds = 20.0)
        val stopped = List(25 * freq) { breathing.last() }

        assertEquals(BreathingRateEstimate.NoBreathing, estimate(breathing + stopped))
    }

    // -- Warmup --

    @Test
    fun emptyBuffer_reportsWarmup() {
        assertEquals(BreathingRateEstimate.Warmup, estimate(emptyList()))
    }

    @Test
    fun belowWarmupWindow_reportsWarmup() {
        // 11 s of perfectly good breathing is still not enough to commit to a number.
        assertEquals(BreathingRateEstimate.Warmup, estimate(sine(12.0, seconds = 11.0)))
    }

    @Test
    fun slowBreatherWithOnlyOneBreathSoFar_reportsWarmupNotNoBreathing() {
        // 4 br/min: at 14 s only one breath has happened, which must not read as "no breathing".
        assertEquals(BreathingRateEstimate.Warmup, estimate(sine(4.0, seconds = 14.0)))
    }

    // -- Reported amplitude (the field-calibration signal) --

    @Test
    fun rate_carriesTheMeasuredAmplitude() {
        val result = estimate(sine(12.0, seconds = 60.0, amplitude = 25.0)) as BreathingRateEstimate.Rate

        // The interquartile span of a sine is ≈ 1.41 × its amplitude.
        assertEquals(35.4f, result.amplitudeRa, 5.0f)
    }

    // -- Helpers --

    private fun estimate(samples: List<Double>) = BreathingRateEstimator.estimate(samples, freq)

    private fun rateOf(samples: List<Double>): Float {
        val result = estimate(samples)
        assertTrue("Expected a rate but got $result", result is BreathingRateEstimate.Rate)
        return (result as BreathingRateEstimate.Rate).brPerMin
    }

    private fun sine(
        brPerMin: Double,
        seconds: Double,
        amplitude: Double = 25.0,
        level: Double = 210.0,
        driftRaPerSec: Double = 0.0,
        noise: Double = 1.2,
        seed: Int = 3
    ): List<Double> {
        val rng = Random(seed)
        val count = (seconds * freq).toInt()
        return List(count) { i ->
            val t = i.toDouble() / freq
            level + driftRaPerSec * t +
                amplitude * sin(2 * PI * brPerMin / 60.0 * t) +
                rng.nextDouble(-noise, noise)
        }
    }

    /** Continuous-phase rate change, so the switch introduces no artificial discontinuity. */
    private fun step(from: Double, to: Double, switchAtSec: Double, totalSec: Double): List<Double> {
        val rng = Random(3)
        var phase = 0.0
        return List((totalSec * freq).toInt()) { i ->
            val t = i.toDouble() / freq
            phase += 2 * PI * (if (t < switchAtSec) from else to) / 60.0 / freq
            210.0 + 25.0 * sin(phase) + rng.nextDouble(-1.2, 1.2)
        }
    }

    private fun loadFixture(path: String): List<Double> =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing test fixture $path" }
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.trim().toDouble() }
}
