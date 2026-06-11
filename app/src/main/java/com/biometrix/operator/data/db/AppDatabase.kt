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
    // v2: added SensorType.WATCH_IBI for Galaxy Watch store-and-forward (HR reuses HEART_RATE).
    // The DB uses fallbackToDestructiveMigration (see AppModule), so a new enum value needs no
    // hand-written Migration — the value is stored as a string by Converters.
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
