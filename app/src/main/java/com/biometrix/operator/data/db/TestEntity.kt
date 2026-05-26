package com.biometrix.operator.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TestStatus {
    ACTIVE,
    COMPLETED,
    EXPORTED
}

@Entity(
    tableName = "tests",
    indices = [
        Index(value = ["testIdentifier"], unique = true)
    ]
)
data class TestEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Test number format: yyMMdd-HHmmss */
    val testNumber: String,

    /** Unique identifier for external matching. Format: BMX-yyMMdd-HHmmss */
    val testIdentifier: String,

    val createdAt: Long,
    val endedAt: Long? = null,
    val durationMs: Long = 0,
    val status: TestStatus = TestStatus.ACTIVE,
    val notes: String = "",

    /** Number of recordings in this test */
    val recordingCount: Int = 0,

    /** Total heart rate samples across all recordings */
    val totalHeartRateSampleCount: Int = 0,

    /** Total respiration samples across all recordings */
    val totalRespirationSampleCount: Int = 0,

    /** Total eSense Pulse R-R interval samples across all recordings */
    val totalEsenseRrIntervalSampleCount: Int = 0,

    /** Number of blood pressure events in this test */
    val bpEventCount: Int = 0
)
