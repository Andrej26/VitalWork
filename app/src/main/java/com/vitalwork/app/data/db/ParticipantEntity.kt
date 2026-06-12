package com.vitalwork.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "participants",
    indices = [
        Index(value = ["participantCode"], unique = true)
    ]
)
data class ParticipantEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val participantCode: String,

    val age: Int? = null,

    val gender: String? = null
)
