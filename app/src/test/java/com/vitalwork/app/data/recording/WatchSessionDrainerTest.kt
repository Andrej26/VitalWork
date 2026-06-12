package com.vitalwork.app.data.recording

import com.vitalwork.app.data.db.SensorType
import com.vitalwork.app.data.recording.WatchSessionDrainer.HwmKey
import com.vitalwork.app.data.recording.WatchSessionDrainer.ScenarioWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the back-to-back / sleep-burst correctness rules of [WatchSessionDrainer].
 * All timestamps are already phone-clock-corrected (the receiver applies the offset upstream).
 */
class WatchSessionDrainerTest {

    private fun reading(t: Long, v: Float = 1.0f, type: SensorType = SensorType.WATCH_EDA) =
        WatchSessionDrainer.Reading(t, type, v)

    @Test
    fun filesSampleIntoTheScenarioWhoseWindowContainsIt() {
        val windows = listOf(ScenarioWindow(scenarioId = 10L, startedAt = 1_000L, endedAt = 2_000L))
        val rows = WatchSessionDrainer.drain(
            readings = listOf(reading(1_500L, 3.3f)),
            windows = windows,
            highWaterMarks = emptyMap()
        )
        assertEquals(1, rows.size)
        assertEquals(10L, rows[0].scenarioId)
        assertEquals(1_500L, rows[0].timestampMs)
        assertEquals(SensorType.WATCH_EDA, rows[0].sensorType)
        assertEquals(3.3f, rows[0].value)
    }

    @Test
    fun derivesElapsedFromScenarioStart_notFromArrival() {
        val windows = listOf(ScenarioWindow(10L, startedAt = 1_000L, endedAt = 5_000L))
        val rows = WatchSessionDrainer.drain(
            readings = listOf(reading(1_250L), reading(4_000L)),
            windows = windows,
            highWaterMarks = emptyMap()
        )
        assertEquals(listOf(250L, 3_000L), rows.map { it.elapsedMs })
    }

    @Test
    fun dropsSamplesInTheGapBetweenScenarios() {
        val windows = listOf(
            ScenarioWindow(10L, startedAt = 1_000L, endedAt = 2_000L),
            ScenarioWindow(11L, startedAt = 3_000L, endedAt = 4_000L)
        )
        // 2_500 is after scenario 10 ended and before scenario 11 started → no active scenario.
        val rows = WatchSessionDrainer.drain(
            readings = listOf(reading(2_500L)),
            windows = windows,
            highWaterMarks = emptyMap()
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun dropsSamplesAfterTheLastScenarioEnded() {
        val windows = listOf(ScenarioWindow(10L, startedAt = 1_000L, endedAt = 2_000L))
        val rows = WatchSessionDrainer.drain(
            readings = listOf(reading(2_500L)),
            windows = windows,
            highWaterMarks = emptyMap()
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun ignoresScenariosWithNullEndedAt() {
        val windows = listOf(ScenarioWindow(10L, startedAt = 1_000L, endedAt = null))
        val rows = WatchSessionDrainer.drain(
            readings = listOf(reading(1_500L)),
            windows = windows,
            highWaterMarks = emptyMap()
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun deDuplicatesAgainstSamplesAlreadyWrittenLive() {
        val windows = listOf(ScenarioWindow(10L, startedAt = 1_000L, endedAt = 5_000L))
        // High-water mark says everything up to 3_000 was already persisted live (for EDA).
        val rows = WatchSessionDrainer.drain(
            readings = listOf(reading(2_000L), reading(3_000L), reading(4_000L)),
            windows = windows,
            highWaterMarks = mapOf(HwmKey(10L, SensorType.WATCH_EDA) to 3_000L)
        )
        // Only the 4_000 sample (after the high-water mark) survives.
        assertEquals(listOf(4_000L), rows.map { it.timestampMs })
    }

    @Test
    fun backToBackScenarios_attributeByTimestampNotArrivalOrder() {
        // Two scenarios <6s apart; a late burst delivers samples spanning both.
        val windows = listOf(
            ScenarioWindow(10L, startedAt = 1_000L, endedAt = 2_000L),
            ScenarioWindow(11L, startedAt = 2_500L, endedAt = 3_500L)
        )
        val rows = WatchSessionDrainer.drain(
            readings = listOf(
                reading(1_500L),  // → scenario 10
                reading(2_200L),  // gap → dropped
                reading(3_000L)   // → scenario 11
            ),
            windows = windows,
            highWaterMarks = emptyMap()
        )
        assertEquals(2, rows.size)
        assertEquals(10L, rows.first { it.timestampMs == 1_500L }.scenarioId)
        assertEquals(11L, rows.first { it.timestampMs == 3_000L }.scenarioId)
    }

    @Test
    fun boundaryTimestampsAreInclusive() {
        val windows = listOf(ScenarioWindow(10L, startedAt = 1_000L, endedAt = 2_000L))
        val rows = WatchSessionDrainer.drain(
            readings = listOf(reading(1_000L), reading(2_000L)),
            windows = windows,
            highWaterMarks = emptyMap()
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun noScenarios_returnsEmpty() {
        val rows = WatchSessionDrainer.drain(
            readings = listOf(reading(1_500L)),
            windows = emptyList(),
            highWaterMarks = emptyMap()
        )
        assertTrue(rows.isEmpty())
    }

    // --- per-type behavior (WATCH_EDA + WATCH_HR + WATCH_IBI now share the pipeline) ---

    @Test
    fun attributesMixedTypesIntoTheSameScenarioPreservingType() {
        val windows = listOf(ScenarioWindow(10L, startedAt = 1_000L, endedAt = 5_000L))
        val rows = WatchSessionDrainer.drain(
            readings = listOf(
                reading(1_500L, 72f, SensorType.WATCH_HR),
                reading(1_500L, 830f, SensorType.WATCH_IBI),
                reading(1_500L, 3.3f, SensorType.WATCH_EDA)
            ),
            windows = windows,
            highWaterMarks = emptyMap()
        )
        assertEquals(3, rows.size)
        assertEquals(
            setOf(SensorType.WATCH_HR, SensorType.WATCH_IBI, SensorType.WATCH_EDA),
            rows.map { it.sensorType }.toSet()
        )
    }

    @Test
    fun highWaterMarkIsPerType_doesNotCrossSuppress() {
        val windows = listOf(ScenarioWindow(10L, startedAt = 1_000L, endedAt = 5_000L))
        // HR is acked through 3_000; IBI/EDA are NOT — an HR HWM must not suppress other types.
        val rows = WatchSessionDrainer.drain(
            readings = listOf(
                reading(2_000L, 70f, SensorType.WATCH_HR), // ≤ HR hwm → dropped
                reading(2_000L, 800f, SensorType.WATCH_IBI), // different type → kept
                reading(2_000L, 2.1f, SensorType.WATCH_EDA)        // different type → kept
            ),
            windows = windows,
            highWaterMarks = mapOf(HwmKey(10L, SensorType.WATCH_HR) to 3_000L)
        )
        assertEquals(
            setOf(SensorType.WATCH_IBI, SensorType.WATCH_EDA),
            rows.map { it.sensorType }.toSet()
        )
    }
}
