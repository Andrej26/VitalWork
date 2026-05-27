package com.biometrix.operator.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RecordingStatus {
    RECORDING,
    COMPLETED,
    DISCARDED
}

@Entity(
    tableName = "recordings",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["recordingIdentifier"], unique = true)
    ]
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: Long,

    /** Unique identifier for external matching. Format: BMX-YYYY-NNN-RNN */
    val recordingIdentifier: String,

    /** Sequence number within the test (1, 2, 3, ...) */
    val sequenceNumber: Int,

    /** Unix timestamp in milliseconds when recording started */
    val startedAt: Long,

    /** Unix timestamp in milliseconds when recording ended */
    val endedAt: Long? = null,

    /** Recording duration in milliseconds */
    val durationMs: Long = 0,

    val status: RecordingStatus = RecordingStatus.RECORDING,

    /** Whether heart rate sensor was enabled during this recording */
    val heartRateEnabled: Boolean = false,

    /** Whether respiration sensor was enabled during this recording */
    val respirationEnabled: Boolean = false,

    /** Number of heart rate samples collected */
    val heartRateSampleCount: Int = 0,

    /** Number of respiration samples collected */
    val respirationSampleCount: Int = 0,

    /** Number of eSense Pulse R-R interval samples collected */
    val esenseRrIntervalSampleCount: Int = 0
)
