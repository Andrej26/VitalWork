package com.biometrix.operator.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ParticipantEntity::class,
        SessionEntity::class,
        ScenarioEntity::class,
        SensorSampleEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun participantDao(): ParticipantDao
    abstract fun sessionDao(): SessionDao
    abstract fun scenarioDao(): ScenarioDao
    abstract fun sensorSampleDao(): SensorSampleDao
}
