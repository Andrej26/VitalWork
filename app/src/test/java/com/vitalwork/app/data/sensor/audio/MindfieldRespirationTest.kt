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

    @Test
    fun calculateBreathingRate_emptyBuffer_returnsZero() {
        assertEquals(0f, MindfieldRespiration.calculateBreathingRate(), 0f)
    }

    @Test
    fun calculateBreathingRate_belowMinSamples_returnsZero() {
        // 14 samples (minimum is 15 = 3 seconds × 5 Hz)
        repeat(14) { MindfieldRespiration.raBuffer.addLast(50.0 + it) }

        assertEquals(0f, MindfieldRespiration.calculateBreathingRate(), 0f)
    }

    @Test
    fun calculateBreathingRate_exactlyMinSamples_returnsRate() {
        // 15 samples with crossings should produce a non-zero rate
        fillSineWave(breathsPerMin = 15.0, sampleCount = 15)

        val rate = MindfieldRespiration.calculateBreathingRate()
        assertTrue("Expected rate > 0 but got $rate", rate > 0f)
    }

    @Test
    fun calculateBreathingRate_constantSignal_returnsZero() {
        // All same value → no crossings → 0 breaths
        repeat(150) { MindfieldRespiration.raBuffer.addLast(100.0) }

        assertEquals(0f, MindfieldRespiration.calculateBreathingRate(), 0f)
    }

    // -- Breathing rate: accuracy --

    @Test
    fun calculateBreathingRate_singleBreathCycle_correctRate() {
        // 1 sine cycle over 150 samples (30s) = 1 upward crossing = 2.0 br/min
        fillSineWave(breathsPerMin = 2.0, sampleCount = 150)

        val rate = MindfieldRespiration.calculateBreathingRate()
        assertEquals(2.0f, rate, 0.5f)
    }

    @Test
    fun calculateBreathingRate_normalBreathing_correctRate() {
        // ~15 breaths/min is normal adult resting rate
        fillSineWave(breathsPerMin = 15.0, sampleCount = 150)

        val rate = MindfieldRespiration.calculateBreathingRate()
        assertEquals(15.0f, rate, 1.0f)
    }

    @Test
    fun calculateBreathingRate_rapidBreathing_higherRate() {
        fillSineWave(breathsPerMin = 30.0, sampleCount = 150)

        val rate = MindfieldRespiration.calculateBreathingRate()
        // Discrete sampling causes ±2 crossings at window boundaries
        assertEquals(30.0f, rate, 3.0f)
    }

    @Test
    fun calculateBreathingRate_bufferExceedsWindow_trimmedTo150() {
        // Add 200 samples — buffer should cap at 150 (RATE_WINDOW_SAMPLES)
        repeat(200) { i ->
            MindfieldRespiration.raBuffer.addLast(50.0 + sin(i.toDouble()) * 20.0)
            while (MindfieldRespiration.raBuffer.size > 150) {
                MindfieldRespiration.raBuffer.removeFirst()
            }
        }

        assertEquals(150, MindfieldRespiration.raBuffer.size)
        // Should still compute a rate from the remaining 150 samples
        val rate = MindfieldRespiration.calculateBreathingRate()
        assertTrue("Expected rate > 0 but got $rate", rate > 0f)
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
            // Sine wave centered at 100 RA with amplitude 50
            val ra = 100.0 + 50.0 * sin(2.0 * Math.PI * freqHz * t)
            MindfieldRespiration.raBuffer.addLast(ra)
        }
    }
}
