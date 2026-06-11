package com.biometrix.operator.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SensorType {
    HEART_RATE,
    RESPIRATION,
    ESENSE_RR_INTERVAL,
    EDA,

    /**
     * Galaxy Watch inter-beat interval (ms). Kept distinct from [ESENSE_RR_INTERVAL] so watch HRV
     * data is attributable to its source in export/analysis (eSense RR comes from a different sensor
     * with different validity conventions). Galaxy Watch HR reuses [HEART_RATE].
     */
    WATCH_IBI
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
