package com.biometrix.operator.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SensorType {
    HEART_RATE,
    RESPIRATION,
    ESENSE_RR_INTERVAL
}

@Entity(
    tableName = "sensor_samples",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["recordingId"]),
        Index(value = ["recordingId", "timestampMs"])
    ]
)
data class SensorSampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Foreign key to the recording this sample belongs to */
    val recordingId: Long,

    /** Absolute Unix timestamp in milliseconds */
    val timestampMs: Long,

    /** Elapsed time since recording start in milliseconds */
    val elapsedMs: Long,

    /** Type of sensor that produced this sample */
    val sensorType: SensorType,

    /** Sensor reading value (BPM for heart rate, breathing amplitude for respiration) */
    val value: Float
)
