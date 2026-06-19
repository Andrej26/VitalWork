package com.vitalwork.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ScenarioCode(
    val officialCode: String,
    val displayName: String
) {
    FALLING_PALLET("A1", "Falling Pallet"),
    BLIND_CORNER("A2", "Blind Corner"),
    EQUIPMENT_COLLISION("A3", "Collision with Equipment"),
    FLOOR_OBSTACLE("A4", "Obstacle on the Floor"),
    MACHINE_JAM("B1", "Machine Jam"),
    CONVEYOR_ACCELERATION("B2", "Uncontrolled Conveyor Acceleration"),
    MEDIUM_LEAKAGE("B3", "Medium Leakage"),
    ELECTRICAL_SHORT("B4", "Electrical Short Circuit"),
    SLING_FAILURE("C1", "Sling Failure");

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
