package com.biometrix.operator.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Sensor reading category. Every value maps to **exactly one physical sensor** so a sample's source
 * is unambiguous — heart rate in particular is recorded from two devices simultaneously (eSense Pulse
 * and the Galaxy Watch), so they get distinct types and are never merged.
 *
 *  - `ESENSE_*` → Mindfield eSense (Pulse over BLE, Respiration over audio jack).
 *  - `WATCH_*`  → Samsung Galaxy Watch via the `:wear` companion / Data Layer.
 */
enum class SensorType {
    /** eSense Pulse heart rate (BPM), over BLE. */
    ESENSE_HEART_RATE,
    /** eSense Respiration breathing rate (breaths/min), over audio jack. */
    RESPIRATION,
    /** eSense Pulse RR interval (ms), over BLE. */
    ESENSE_RR_INTERVAL,

    /** Galaxy Watch heart rate (BPM). Distinct from [ESENSE_HEART_RATE] — both record HR at once. */
    WATCH_HR,
    /**
     * Galaxy Watch inter-beat interval (ms). Kept distinct from [ESENSE_RR_INTERVAL] so watch HRV
     * data is attributable to its source in export/analysis (eSense RR comes from a different sensor
     * with different validity conventions).
     */
    WATCH_IBI,
    /** Galaxy Watch electrodermal activity / skin conductance (µS). */
    WATCH_EDA
}

@Entity(
    tableName = "sensor_samples",
    foreignKeys = [
        ForeignKey(
            entity = ScenarioEntity::class,
            parentColumns = ["id"],
            childColumns = ["scenarioId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["scenarioId", "timestampMs"])
    ]
)
data class SensorSampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Foreign key to the scenario this sample belongs to */
    val scenarioId: Long,

    /** Absolute Unix timestamp in milliseconds (Android clock) */
    val timestampMs: Long,

    /** Elapsed time since scenario start in milliseconds */
    val elapsedMs: Long,

    /** Type of sensor that produced this sample */
    val sensorType: SensorType,

    /** Sensor reading value. Units depend on sensorType:
     *  BPM (heart rate), ms (RR interval), breaths/min (respiration), μS (EDA, Galaxy Watch). */
    val value: Float
)
