package com.biometrix.operator.data.db

import androidx.room.TypeConverter

class Converters {

    @TypeConverter
    fun fromSessionStatus(status: SessionStatus): String = status.name

    @TypeConverter
    fun toSessionStatus(value: String): SessionStatus = SessionStatus.valueOf(value)

    @TypeConverter
    fun fromScenarioCategory(category: ScenarioCategory): String = category.name

    @TypeConverter
    fun toScenarioCategory(value: String): ScenarioCategory = ScenarioCategory.valueOf(value)

    @TypeConverter
    fun fromScenarioCode(code: ScenarioCode): String = code.name

    @TypeConverter
    fun toScenarioCode(value: String): ScenarioCode = ScenarioCode.valueOf(value)

    @TypeConverter
    fun fromSensorType(sensorType: SensorType): String = sensorType.name

    @TypeConverter
    fun toSensorType(value: String): SensorType = SensorType.valueOf(value)
}
