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
    // v2: added SensorType.WATCH_IBI for Galaxy Watch store-and-forward.
    // v3: split per-device SensorTypes — HEART_RATE → ESENSE_HEART_RATE + WATCH_HR, EDA → WATCH_EDA —
    //     so HR from the eSense Pulse and the Galaxy Watch (recorded simultaneously) never merge.
    // The DB uses fallbackToDestructiveMigration (see AppModule), so renamed enum values need no
    // hand-written Migration — the destructive fallback wipes the old local rows (sessions are already
    // exported/uploaded), and values are stored as strings by Converters.
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun participantDao(): ParticipantDao
    abstract fun sessionDao(): SessionDao
    abstract fun scenarioDao(): ScenarioDao
    abstract fun sensorSampleDao(): SensorSampleDao
}
