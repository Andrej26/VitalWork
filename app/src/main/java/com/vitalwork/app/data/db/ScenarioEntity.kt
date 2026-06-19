package com.vitalwork.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ScenarioCode(
    val officialCode: String,
    val displayName: String,
    val countdownMinutes: Int
) {
    REFERENCE_STATE("A", "Scenario A – Reference State", 10),
    COGNITIVE_LOAD("B", "Scenario B – Increased Cognitive Load", 20),
    DISTRACTING_ENVIRONMENT("C", "Scenario C – Distracting Environment", 20),
    LONG_TERM_FATIGUE("D", "Scenario D – Long-Term Load and Fatigue", 30),
    REACTION_TASKS("E", "Scenario E – Reaction Tasks", 10);

    companion object {
        fun fromOfficialCode(code: String): ScenarioCode? =
            entries.firstOrNull { it.officialCode.equals(code, ignoreCase = true) }
    }
}

@Entity(
    tableName = "scenarios",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId", "scenarioCode"])
    ]
)
data class ScenarioEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: Long,

    val scenarioCode: ScenarioCode,

    val startedAt: Long,

    val endedAt: Long? = null
)
