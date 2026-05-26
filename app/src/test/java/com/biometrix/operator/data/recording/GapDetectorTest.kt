package com.biometrix.operator.data.recording

import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GapDetectorTest {

    private fun sample(
        elapsedMs: Long,
        type: SensorType = SensorType.HEART_RATE,
        value: Float = 70f
    ) = SensorSampleEntity(
        recordingId = 1L,
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
            sample(20_000, SensorType.HEART_RATE),
            sample(22_000, SensorType.RESPIRATION),
            sample(24_000, SensorType.RESPIRATION),
            sample(28_000, SensorType.HEART_RATE)
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
        gapPair(SensorType.HEART_RATE) +
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
}
