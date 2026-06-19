package com.vitalwork.app.data.db

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
    // v4: added sessions.watchHrSampleCount + watchIbiSampleCount so the session summary counters
    //     cover all six sample types (previously WATCH_HR/WATCH_IBI were uncounted).
    // v5: biofeedback-only pivot — dropped scenarios.scenarioCategory/eventTimestampMs/
    //     reactionTimestampMs and sessions.notes (reaction-time measurement + the VR link were
    //     removed; the app now records only sensor samples per scenario).
    // The DB uses fallbackToDestructiveMigration (see AppModule), so renamed enum values and added/
    // removed columns need no hand-written Migration — the destructive fallback wipes the old local
    // rows (sessions are already exported/uploaded), and values are stored as strings by Converters.
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun participantDao(): ParticipantDao
    abstract fun sessionDao(): SessionDao
    abstract fun scenarioDao(): ScenarioDao
    abstract fun sensorSampleDao(): SensorSampleDao
}
