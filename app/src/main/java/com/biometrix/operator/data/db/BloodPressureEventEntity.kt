package com.biometrix.operator.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "blood_pressure_events",
    foreignKeys = [
        ForeignKey(
            entity = TestEntity::class,
            parentColumns = ["id"],
            childColumns = ["testId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["testId"])
    ]
)
data class BloodPressureEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val testId: Long,
    val timestampMs: Long,
    val elapsedTestMs: Long,
    val systolicMmHg: Int,
    val diastolicMmHg: Int,
    val meanArterialMmHg: Int?,
    val pulseRateBpm: Int?
)
