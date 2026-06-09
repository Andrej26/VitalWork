package com.biometrix.operator.data.recording

import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType

/**
 * Pure, Android-free logic that slices a session's buffered Galaxy Watch EDA stream into per-scenario
 * [SensorSampleEntity] rows at session end.
 *
 * The watch samples EDA continuously (and buffers in Doze), delivering bursts late. Each delivered
 * reading carries the moment it was *taken* (already corrected onto the phone clock by the caller),
 * so attribution is by **timestamp window**, not arrival order:
 *
 *  - A reading is filed into the scenario whose `[startedAt, endedAt]` window contains its timestamp.
 *    A scenario with `endedAt == null` has an undefined window and is never a target.
 *  - Readings in a between-scenario gap (or after the last scenario ended) are **dropped** — no
 *    scenario was active, so they are not study data.
 *  - Readings already written live are skipped via a per-scenario **high-water mark** (the max
 *    corrected EDA timestamp already persisted live for that scenario), so live + drained never
 *    double-up.
 *
 * `elapsedMs` is derived from `scenario.startedAt` (the durable DB value), never from transient
 * recording-session state.
 *
 * This is the unit-test target for the back-to-back / sleep-burst correctness rules.
 */
object WatchSessionDrainer {

    /** Minimal scenario window the drainer needs (decoupled from the Room entity for testability). */
    data class ScenarioWindow(
        val scenarioId: Long,
        val startedAt: Long,
        val endedAt: Long?
    )

    /**
     * @param readings    corrected (phone-clock) EDA readings buffered over the whole session,
     *                    each as `(correctedTimestampMs, value)`.
     * @param windows     scenario windows for the session.
     * @param highWaterMarks per-scenario max corrected EDA timestamp already written live.
     * @return EDA [SensorSampleEntity] rows to insert, attributed and de-duplicated.
     */
    fun drain(
        readings: List<Pair<Long, Float>>,
        windows: List<ScenarioWindow>,
        highWaterMarks: Map<Long, Long>
    ): List<SensorSampleEntity> {
        // Only scenarios with a defined end are valid targets.
        val closed = windows.filter { it.endedAt != null }
        if (closed.isEmpty()) return emptyList()

        val out = ArrayList<SensorSampleEntity>(readings.size)
        for ((tc, value) in readings) {
            val window = closed.firstOrNull { tc >= it.startedAt && tc <= it.endedAt!! } ?: continue
            val hwm = highWaterMarks[window.scenarioId]
            if (hwm != null && tc <= hwm) continue // already written live
            out += SensorSampleEntity(
                scenarioId = window.scenarioId,
                timestampMs = tc,
                elapsedMs = tc - window.startedAt,
                sensorType = SensorType.EDA,
                value = value
            )
        }
        return out
    }
}
