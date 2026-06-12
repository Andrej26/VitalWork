package com.vitalwork.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SessionStatus {
    ACTIVE,
    COMPLETED,
    UPLOADED
}

@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = ParticipantEntity::class,
            parentColumns = ["id"],
            childColumns = ["participantId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionCode"], unique = true),
        Index(value = ["participantId"])
    ]
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val participantId: Long,

    val sessionCode: String,

    val startedAt: Long,

    val endedAt: Long? = null,

    val status: SessionStatus = SessionStatus.ACTIVE,

    val notes: String = "",

    val hrSampleCount: Int = 0,

    val respirationSampleCount: Int = 0,

    val rrIntervalSampleCount: Int = 0,

    val edaSampleCount: Int = 0,

    val watchHrSampleCount: Int = 0,

    val watchIbiSampleCount: Int = 0,

    val scenarioCount: Int = 0
)
