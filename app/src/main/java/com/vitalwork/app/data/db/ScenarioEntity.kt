package com.vitalwork.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ScenarioCategory {
    A,
    B,
    C
}

enum class ScenarioCode(
    val officialCode: String,
    val displayName: String,
    val category: ScenarioCategory
) {
    FALLING_PALLET("A1", "Falling Pallet", ScenarioCategory.A),
    BLIND_CORNER("A2", "Blind Corner", ScenarioCategory.A),
    EQUIPMENT_COLLISION("A3", "Collision with Equipment", ScenarioCategory.A),
    FLOOR_OBSTACLE("A4", "Obstacle on the Floor", ScenarioCategory.A),
    MACHINE_JAM("B1", "Machine Jam", ScenarioCategory.B),
    CONVEYOR_ACCELERATION("B2", "Uncontrolled Conveyor Acceleration", ScenarioCategory.B),
    MEDIUM_LEAKAGE("B3", "Medium Leakage", ScenarioCategory.B),
    ELECTRICAL_SHORT("B4", "Electrical Short Circuit", ScenarioCategory.B),
    SLING_FAILURE("C1", "Sling Failure", ScenarioCategory.C);

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

    val scenarioCategory: ScenarioCategory,

    val startedAt: Long,

    val endedAt: Long? = null,

    val eventTimestampMs: Long? = null,

    val reactionTimestampMs: Long? = null
)
