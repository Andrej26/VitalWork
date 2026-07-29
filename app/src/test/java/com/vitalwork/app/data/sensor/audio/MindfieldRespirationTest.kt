package com.vitalwork.app.data.sensor.audio

import com.vitalwork.app.data.sensor.DeviceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class MindfieldRespirationTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        MindfieldRespiration.raBuffer.clear()
        MindfieldRespiration.isVerifying = false
        MindfieldRespiration.verifyCount = 0
    }

    @After
    fun tearDown() {
        MindfieldRespiration.disconnect()
        Dispatchers.resetMain()
    }

    // -- Breathing rate: edge cases --

    // The estimator itself is covered by BreathingRateEstimatorTest; these check the delegation
    // and the buffer lifecycle that MindfieldRespiration owns.

    @Test
    fun calculateBreathingRate_emptyBuffer_reportsWarmup() {
        assertEquals(BreathingRateEstimate.Warmup, MindfieldRespiration.calculateBreathingRate())
    }

    @Test
    fun calculateBreathingRate_belowWarmupWindow_reportsWarmup() {
        // 59 samples — under the estimator's 12 s (60 sample) warmup.
        repeat(59) { MindfieldRespiration.raBuffer.addLast(210.0 + it) }

        assertEquals(BreathingRateEstimate.Warmup, MindfieldRespiration.calculateBreathingRate())
    }

    @Test
    fun calculateBreathingRate_normalBreathing_delegatesToEstimator() {
        fillSineWave(breathsPerMin = 15.0, sampleCount = 300)

        val result = MindfieldRespiration.calculateBreathingRate()
        assertTrue("Expected a rate but got $result", result is BreathingRateEstimate.Rate)
        assertEquals(15.0f, (result as BreathingRateEstimate.Rate).brPerMin, 1.0f)
    }

    @Test
    fun calculateBreathingRate_constantSignal_reportsNoBreathing() {
        repeat(300) { MindfieldRespiration.raBuffer.addLast(210.0) }

        assertEquals(
            BreathingRateEstimate.NoBreathing,
            MindfieldRespiration.calculateBreathingRate()
        )
    }

    @Test
    fun calculateBreathingRate_bufferExceedsWindow_trimmedTo300() {
        // Add 400 samples — the live buffer caps at 300 (RATE_WINDOW_SAMPLES = 60 s × 5 Hz)
        repeat(400) { i ->
            MindfieldRespiration.raBuffer.addLast(210.0 + sin(i.toDouble()) * 20.0)
            while (MindfieldRespiration.raBuffer.size > 300) {
                MindfieldRespiration.raBuffer.removeFirst()
            }
        }

        assertEquals(300, MindfieldRespiration.raBuffer.size)
        assertNotNull(MindfieldRespiration.calculateBreathingRate())
    }

    // -- Verification: failure cases --

    @Test
    fun finishVerification_tooFewSamples_disconnects() {
        MindfieldRespiration.isVerifying = true
        MindfieldRespiration.verifyCount = 3

        MindfieldRespiration.finishVerification()

        assertEquals(DeviceState.Disconnected, MindfieldRespiration.state.value)
        val reason = MindfieldRespiration.lastDisconnectReason.value
        assertNotNull(reason)
        assertTrue("Expected 'No Signal' in reason but got: $reason", reason!!.contains("No Signal"))
    }

    @Test
    fun finishVerification_outOfRange_disconnects() {
        MindfieldRespiration.isVerifying = true
        MindfieldRespiration.verifyCount = 5
        // Samples with max > 460 (RANGE_MAX)
        for (i in 0 until 5) {
            MindfieldRespiration.verifyBuffer[i] = if (i == 0) 500.0 else 100.0
        }

        MindfieldRespiration.finishVerification()

        assertEquals(DeviceState.Disconnected, MindfieldRespiration.state.value)
        val reason = MindfieldRespiration.lastDisconnectReason.value
        assertNotNull(reason)
        assertTrue("Expected 'Out of Range' in reason but got: $reason", reason!!.contains("Out of Range"))
    }

    @Test
    fun finishVerification_noMovement_disconnects() {
        MindfieldRespiration.isVerifying = true
        MindfieldRespiration.verifyCount = 5
        // All same value → delta < 0.02
        for (i in 0 until 5) {
            MindfieldRespiration.verifyBuffer[i] = 100.0
        }

        MindfieldRespiration.finishVerification()

        assertEquals(DeviceState.Disconnected, MindfieldRespiration.state.value)
        val reason = MindfieldRespiration.lastDisconnectReason.value
        assertNotNull(reason)
        assertTrue("Expected 'Out of Range' in reason but got: $reason", reason!!.contains("Out of Range"))
    }

    @Test
    fun finishVerification_validSignal_transitionsToStreaming() {
        MindfieldRespiration.isVerifying = true
        MindfieldRespiration.verifyCount = 5
        // In range [0, 460] with delta ≥ 0.02
        for (i in 0 until 5) {
            MindfieldRespiration.verifyBuffer[i] = 50.0 + i * 10.0  // 50, 60, 70, 80, 90
        }

        MindfieldRespiration.finishVerification()

        assertEquals(DeviceState.Streaming, MindfieldRespiration.state.value)
    }

    // -- Pause --

    @Test
    fun stopStreaming_clearsRateWindow() {
        // Reach Streaming, then fill the rate window and pause.
        MindfieldRespiration.isVerifying = true
        MindfieldRespiration.verifyCount = 5
        for (i in 0 until 5) {
            MindfieldRespiration.verifyBuffer[i] = 50.0 + i * 10.0
        }
        MindfieldRespiration.finishVerification()
        fillSineWave(breathsPerMin = 15.0, sampleCount = 300)

        MindfieldRespiration.stopStreaming()

        // Samples from before the pause must not survive into the post-resume estimate.
        assertEquals(0, MindfieldRespiration.raBuffer.size)
        assertEquals(BreathingRateEstimate.Warmup, MindfieldRespiration.calculateBreathingRate())
        assertEquals(RespirationWarning.NONE, MindfieldRespiration.warning.value)
    }

    // -- Helper --

    /**
     * Fills raBuffer with a sine wave simulating a given breathing rate.
     * Each sample represents 1/5 second (5 Hz sample rate).
     */
    private fun fillSineWave(breathsPerMin: Double, sampleCount: Int) {
        val sampleFreq = 5.0
        val freqHz = breathsPerMin / 60.0
        for (i in 0 until sampleCount) {
            val t = i / sampleFreq
            // Sine wave centered at 210 RA with amplitude 50 — the range a worn strap produces.
            val ra = 210.0 + 50.0 * sin(2.0 * Math.PI * freqHz * t)
            MindfieldRespiration.raBuffer.addLast(ra)
        }
    }
}
