package com.biometrix.operator.data.db

import androidx.room.TypeConverter

class Converters {

    @TypeConverter
    fun fromTestStatus(status: TestStatus): String = status.name

    @TypeConverter
    fun toTestStatus(value: String): TestStatus = TestStatus.valueOf(value)

    @TypeConverter
    fun fromRecordingStatus(status: RecordingStatus): String = status.name

    @TypeConverter
    fun toRecordingStatus(value: String): RecordingStatus = RecordingStatus.valueOf(value)

    @TypeConverter
    fun fromSensorType(sensorType: SensorType): String = sensorType.name

    @TypeConverter
    fun toSensorType(value: String): SensorType = SensorType.valueOf(value)
}
