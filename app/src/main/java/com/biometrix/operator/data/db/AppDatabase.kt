package com.biometrix.operator.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        SessionEntity::class,
        RecordingEntity::class,
        SensorSampleEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun recordingDao(): RecordingDao
    abstract fun sensorSampleDao(): SensorSampleDao
}
