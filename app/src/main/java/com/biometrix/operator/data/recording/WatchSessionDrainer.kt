package com.biometrix.operator.data.recording

import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType

/**
 * Pure, Android-free logic that slices a session's buffered Galaxy Watch sensor stream (EDA, HR, IBI)
 * into per-scenario [SensorSampleEntity] rows at session end.
 *
 * The watch samples continuously (and buffers in Doze / persists to its own store), delivering bursts
 * late. Each delivered reading carries the moment it was *taken* (already corrected onto the phone
 * clock by the caller) **and its sensor type**, so attribution is by **timestamp window**, not arrival
 * order, and de-dup is **per (scenario, type)**:
 *
 *  - A reading is filed into the scenario whose `[startedAt, endedAt]` window contains its timestamp.
 *    A scenario with `endedAt == null` has an undefined window and is never a target.
 *  - Readings in a between-scenario gap (or after the last scenario ended) are **dropped** — no
 *    scenario was active, so they are not study data.
 *  - Readings already written live are skipped via a per-(scenario, type) **high-water mark** (the max
 *    corrected timestamp already persisted live for that scenario+type), so live + drained never
 *    double-up. The type is part of the key because HR/IBI/EDA advance independently.
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

    /** A buffered watch reading carrying its corrected (phone-clock) timestamp, type, and value. */
    data class Reading(
        val correctedTimestampMs: Long,
        val sensorType: SensorType,
        val value: Float
    )

    /** Composite de-dup key: a reading is "already live" only for its own scenario AND sensor type. */
    data class HwmKey(val scenarioId: Long, val sensorType: SensorType)

    /**
     * @param readings    corrected (phone-clock) watch readings buffered over the whole session,
     *                    each carrying its [SensorType].
     * @param windows     scenario windows for the session.
     * @param highWaterMarks per-(scenario, type) max corrected timestamp already written live.
     * @return [SensorSampleEntity] rows to insert, attributed by window and de-duplicated by type.
     */
    fun drain(
        readings: List<Reading>,
        windows: List<ScenarioWindow>,
        highWaterMarks: Map<HwmKey, Long>
    ): List<SensorSampleEntity> {
        // Only scenarios with a defined end are valid targets.
        val closed = windows.filter { it.endedAt != null }
        if (closed.isEmpty()) return emptyList()

        val out = ArrayList<SensorSampleEntity>(readings.size)
        for (r in readings) {
            val tc = r.correctedTimestampMs
            val window = closed.firstOrNull { tc >= it.startedAt && tc <= it.endedAt!! } ?: continue
            val hwm = highWaterMarks[HwmKey(window.scenarioId, r.sensorType)]
            if (hwm != null && tc <= hwm) continue // already written live
            out += SensorSampleEntity(
                scenarioId = window.scenarioId,
                timestampMs = tc,
                elapsedMs = tc - window.startedAt,
                sensorType = r.sensorType,
                value = r.value
            )
        }
        return out
    }
}
