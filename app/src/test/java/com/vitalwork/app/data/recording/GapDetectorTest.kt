package com.vitalwork.app.data.recording

import com.vitalwork.app.data.db.SensorSampleEntity
import com.vitalwork.app.data.db.SensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class GapDetectorTest {

    private fun sample(
        elapsedMs: Long,
        type: SensorType = SensorType.ESENSE_HEART_RATE,
        value: Float = 70f
    ) = SensorSampleEntity(
        scenarioId = 1L,
        timestampMs = elapsedMs,
        elapsedMs = elapsedMs,
        sensorType = type,
        value = value
    )

    @Test
    fun emptyInput_returnsNoGaps() {
        assertTrue(detectHeartRateGaps(emptyList()).isEmpty())
    }

    @Test
    fun singleSample_returnsNoGaps() {
        val samples = listOf(sample(20_000))
        assertTrue(detectHeartRateGaps(samples).isEmpty())
    }

    @Test
    fun twoSamplesCloserThanMinGap_returnsNoGaps() {
        // 4000ms apart, default minGapMs = 5000
        val samples = listOf(
            sample(20_000),
            sample(24_000)
        )
        assertTrue(detectHeartRateGaps(samples).isEmpty())
    }

    @Test
    fun twoSamplesWithGapAfterStartup_returnsOneGap() {
        // 6000ms apart, both after startup window (10_000)
        val samples = listOf(
            sample(20_000),
            sample(26_000)
        )
        val gaps = detectHeartRateGaps(samples)
        assertEquals(1, gaps.size)
        val gap = gaps.single()
        assertEquals(20_000L, gap.startElapsedMs)
        assertEquals(26_000L, gap.endElapsedMs)
        assertEquals(6_000L, gap.gapMs)
    }

    @Test
    fun gapStartingBeforeStartupThreshold_isIgnored() {
        // First sample at 5000ms is before startup threshold (10_000),
        // so the gap originating from it must be ignored.
        val samples = listOf(
            sample(5_000),
            sample(20_000)
        )
        assertTrue(detectHeartRateGaps(samples).isEmpty())
    }

    @Test
    fun gapStartingExactlyAtStartupThreshold_isIncluded() {
        // a.elapsedMs >= startupThresholdMs (>= 10_000) is the inclusive boundary
        val samples = listOf(
            sample(10_000),
            sample(20_000)
        )
        val gaps = detectHeartRateGaps(samples)
        assertEquals(1, gaps.size)
        assertEquals(10_000L, gaps.single().startElapsedMs)
    }

    @Test
    fun gapOfExactlyMinGapMs_isExcluded() {
        // Strict > minGapMs at the gap boundary -- exactly 5000ms is NOT a gap
        val samples = listOf(
            sample(20_000),
            sample(25_000)
        )
        assertTrue(detectHeartRateGaps(samples).isEmpty())
    }

    @Test
    fun gapOfMinGapMsPlusOne_isIncluded() {
        val samples = listOf(
            sample(20_000),
            sample(25_001)
        )
        val gaps = detectHeartRateGaps(samples)
        assertEquals(1, gaps.size)
        assertEquals(5_001L, gaps.single().gapMs)
    }

    @Test
    fun mixedSensorTypes_onlyRequestedTypeContributes() {
        // Respiration samples sit in the middle of the HR gap.
        // detectHeartRateGaps must ignore them and still see the HR gap.
        val samples = listOf(
            sample(20_000, SensorType.ESENSE_HEART_RATE),
            sample(22_000, SensorType.RESPIRATION),
            sample(24_000, SensorType.RESPIRATION),
            sample(28_000, SensorType.ESENSE_HEART_RATE)
        )
        val gaps = detectHeartRateGaps(samples)
        assertEquals(1, gaps.size)
        assertEquals(20_000L, gaps.single().startElapsedMs)
        assertEquals(28_000L, gaps.single().endElapsedMs)
    }

    @Test
    fun unsortedInput_stillDetectsGapsCorrectly() {
        // Same samples as the basic gap test, just shuffled.
        val samples = listOf(
            sample(26_000),
            sample(20_000)
        )
        val gaps = detectHeartRateGaps(samples)
        assertEquals(1, gaps.size)
        assertEquals(20_000L, gaps.single().startElapsedMs)
        assertEquals(26_000L, gaps.single().endElapsedMs)
    }

    @Test
    fun multipleGaps_allDetected() {
        val samples = listOf(
            sample(15_000),
            sample(22_000),  // gap 1: 7000ms
            sample(23_000),  // no gap: 1000ms
            sample(31_000),  // gap 2: 8000ms
            sample(32_000)   // no gap: 1000ms
        )
        val gaps = detectHeartRateGaps(samples)
        assertEquals(2, gaps.size)
        assertEquals(15_000L, gaps[0].startElapsedMs)
        assertEquals(22_000L, gaps[0].endElapsedMs)
        assertEquals(23_000L, gaps[1].startElapsedMs)
        assertEquals(31_000L, gaps[1].endElapsedMs)
    }

    @Test
    fun gapEvent_gapMs_computesDifference() {
        val event = GapEvent(startElapsedMs = 20_000, endElapsedMs = 26_500)
        assertEquals(6_500L, event.gapMs)
    }

    // ----- Each public function routes to its own sensor type -----
    //
    // Build a fixture containing one valid gap for every sensor type, then
    // confirm each public detector picks up exactly one gap (its own) and
    // ignores the others.

    private fun gapPair(type: SensorType) = listOf(
        sample(20_000, type),
        sample(28_000, type)
    )

    private val mixedFixture: List<SensorSampleEntity> =
        gapPair(SensorType.ESENSE_HEART_RATE) +
        gapPair(SensorType.RESPIRATION) +
        gapPair(SensorType.ESENSE_RR_INTERVAL)

    @Test
    fun detectHeartRateGaps_onlySeesHeartRate() {
        assertEquals(1, detectHeartRateGaps(mixedFixture).size)
    }

    @Test
    fun detectRespirationGaps_onlySeesRespiration() {
        assertEquals(1, detectRespirationGaps(mixedFixture).size)
    }

    @Test
    fun detectEsenseRrIntervalGaps_onlySeesEsenseRrInterval() {
        assertEquals(1, detectEsenseRrIntervalGaps(mixedFixture).size)
    }

    // ----- Respiration issues: stretches that are recorded but unusable -----
    //
    // These are invisible to the gap detectors above: a strap that slips while the cable stays
    // plugged in keeps delivering 5 Hz samples, so there is no gap at all.

    /** Builds a respiration waveform: [flatRanges] carry the strap-off level, the rest breathes. */
    private fun respirationWaveform(
        seconds: Int,
        flatRanges: List<IntRange> = emptyList(),
        flatValue: Float = 0.4f,
        breathsPerMin: Double = 13.0
    ): List<SensorSampleEntity> = (0 until seconds * 5).map { i ->
        val t = i / 5.0
        val value = if (flatRanges.any { t.toInt() in it }) {
            flatValue
        } else {
            (210.0 + 25.0 * sin(2.0 * Math.PI * breathsPerMin / 60.0 * t)).toFloat()
        }
        sample((t * 1000).toLong(), SensorType.RESPIRATION, value)
    }

    @Test
    fun respirationIssues_noSamples_reportsNothing() {
        // Absence of data means the sensor was never connected — that is not a lost signal.
        assertTrue(detectRespirationIssues(emptyList()).isEmpty())
        assertTrue(detectRespirationIssues(gapPair(SensorType.ESENSE_HEART_RATE)).isEmpty())
    }

    @Test
    fun respirationIssues_normalBreathing_reportsNothing() {
        assertTrue(detectRespirationIssues(respirationWaveform(seconds = 60)).isEmpty())
    }

    @Test
    fun respirationIssues_tenSecondStrapSlip_isDetectedWithItsBoundaries() {
        val samples = respirationWaveform(seconds = 60, flatRanges = listOf(20..29))

        val lost = detectRespirationIssues(samples).filter { it.reason == RespirationIssue.SIGNAL_LOST }

        assertEquals(1, lost.size)
        assertEquals(20_000L, lost[0].startElapsedMs)
        assertEquals(29_800L, lost[0].endElapsedMs)
        assertEquals(9_800L, lost[0].durationMs)
    }

    @Test
    fun respirationIssues_briefDipUnderTwoSeconds_isIgnored() {
        val samples = respirationWaveform(seconds = 60, flatRanges = listOf(20..20))

        assertTrue(detectRespirationIssues(samples).none { it.reason == RespirationIssue.SIGNAL_LOST })
    }

    @Test
    fun respirationIssues_signalLostThreshold_isEightTenths() {
        val below = respirationWaveform(seconds = 60, flatRanges = listOf(20..29), flatValue = 0.79f)
        val above = respirationWaveform(seconds = 60, flatRanges = listOf(20..29), flatValue = 0.81f)

        assertEquals(1, detectRespirationIssues(below).count { it.reason == RespirationIssue.SIGNAL_LOST })
        assertEquals(0, detectRespirationIssues(above).count { it.reason == RespirationIssue.SIGNAL_LOST })
    }

    @Test
    fun respirationIssues_runIsBrokenByAGapInSampling() {
        // Strap off, sensor drops out for 8 s, comes back still off. Two events, and neither
        // duration may swallow the stretch where nothing was measured at all.
        val first = respirationWaveform(seconds = 20, flatRanges = listOf(5..19))
        val second = respirationWaveform(seconds = 20, flatRanges = listOf(0..14))
            .map { it.copy(elapsedMs = it.elapsedMs + 28_000, timestampMs = it.timestampMs + 28_000) }

        val lost = detectRespirationIssues(first + second)
            .filter { it.reason == RespirationIssue.SIGNAL_LOST }

        assertEquals(2, lost.size)
        assertTrue("Runs must not span the 8 s outage", lost.all { it.durationMs < 20_000 })
    }

    @Test
    fun respirationIssues_flatButHealthyRa_reportsNoBreathing() {
        // RA sits at a normal level, so SIGNAL_LOST never fires — but nothing is tracking breathing.
        val samples = respirationWaveform(seconds = 90, flatRanges = listOf(20..59), flatValue = 212f)

        val flat = detectRespirationIssues(samples).filter { it.reason == RespirationIssue.NO_BREATHING }

        assertEquals(1, flat.size)
        assertTrue(
            "Expected the ~40 s flat stretch, got ${flat[0].durationMs} ms",
            flat[0].durationMs in 30_000..50_000
        )
    }

    @Test
    fun respirationIssues_shortFlatStretch_isBelowTheNoBreathingThreshold() {
        val samples = respirationWaveform(seconds = 90, flatRanges = listOf(20..34), flatValue = 212f)

        assertTrue(
            detectRespirationIssues(samples).none { it.reason == RespirationIssue.NO_BREATHING }
        )
    }

    @Test
    fun respirationIssues_bothKinds_areReturnedInTimeOrder() {
        val samples = respirationWaveform(seconds = 150, flatRanges = listOf(20..29))
            .map { s ->
                // Overlay a long healthy-but-flat stretch later in the scenario.
                if (s.elapsedMs in 60_000..109_800) s.copy(value = 212f) else s
            }

        val events = detectRespirationIssues(samples)

        assertEquals(listOf(RespirationIssue.SIGNAL_LOST, RespirationIssue.NO_BREATHING), events.map { it.reason })
        assertTrue(events[0].startElapsedMs < events[1].startElapsedMs)
    }

    @Test
    fun respirationIssueEvent_durationMs_computesDifference() {
        val event = RespirationIssueEvent(RespirationIssue.SIGNAL_LOST, 20_000, 26_500)
        assertEquals(6_500L, event.durationMs)
    }
}
