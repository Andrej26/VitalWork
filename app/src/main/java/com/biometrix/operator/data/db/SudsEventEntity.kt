package com.biometrix.operator.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "suds_events",
    foreignKeys = [
        ForeignKey(
            entity = TestEntity::class,
            parentColumns = ["id"],
            childColumns = ["testId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["testId"])]
)
data class SudsEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val testId: Long,
    val timestampMs: Long,
    val value: Int
)
