package com.biometrix.operator.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        TestEntity::class,
        RecordingEntity::class,
        SensorSampleEntity::class,
        SudsEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun testDao(): TestDao
    abstract fun recordingDao(): RecordingDao
    abstract fun sensorSampleDao(): SensorSampleDao
    abstract fun sudsEventDao(): SudsEventDao
}
