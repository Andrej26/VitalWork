package com.vitalwork.app.di

import android.content.Context
import androidx.room.Room
import com.vitalwork.app.data.db.AppDatabase
import com.vitalwork.app.data.db.ParticipantDao
import com.vitalwork.app.data.db.ScenarioDao
import com.vitalwork.app.data.db.SensorSampleDao
import com.vitalwork.app.data.db.SessionDao
import com.vitalwork.app.data.export.SessionExportService
import com.vitalwork.app.data.export.SessionExporter
import com.vitalwork.app.data.export.SessionUploader
import com.vitalwork.app.data.export.upload.SessionHttpUploader
import com.vitalwork.app.data.recording.ScenarioRecordingRepository
import com.vitalwork.app.data.recording.ScenarioRecordingRepositoryImpl
import com.vitalwork.app.data.repository.ScenarioRepository
import com.vitalwork.app.data.sensor.SensorDevice
import com.vitalwork.app.data.sensor.audio.MindfieldRespiration
import com.vitalwork.app.data.sensor.ble.BleManager
import com.vitalwork.app.data.sensor.ble.BleManagerImpl
import com.vitalwork.app.data.sensor.watch.WatchSensorReceiver
import com.vitalwork.app.data.network.NetworkChecker
import com.vitalwork.app.data.prefs.SettingsRepository
import com.vitalwork.app.data.prefs.SharedPrefsSettingsRepository
import com.vitalwork.app.data.system.LocationChecker
import com.vitalwork.app.data.system.LocationCheckerImpl
import com.vitalwork.app.data.system.SystemReadinessChecker
import com.vitalwork.app.data.system.SystemReadinessCheckerImpl
import com.vitalwork.app.data.time.TimeProvider
import com.lyft.kronos.AndroidClockFactory
import com.lyft.kronos.KronosClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindsModule {
    @Binds
    @Singleton
    abstract fun bindLocationChecker(impl: LocationCheckerImpl): LocationChecker

    @Binds
    @Singleton
    abstract fun bindSystemReadinessChecker(impl: SystemReadinessCheckerImpl): SystemReadinessChecker

    @Binds
    @Singleton
    abstract fun bindSessionExporter(impl: SessionExportService): SessionExporter

    @Binds
    @Singleton
    abstract fun bindSessionUploader(impl: SessionHttpUploader): SessionUploader

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SharedPrefsSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindWatchCommandSender(
        impl: com.vitalwork.app.data.sensor.watch.WatchCommandSenderImpl
    ): com.vitalwork.app.data.sensor.watch.WatchCommandSender
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @Named("lanAvailable")
    fun provideLanAvailableFlow(networkChecker: NetworkChecker): StateFlow<Boolean> =
        networkChecker.lanAvailable

    /**
     * NTP clock used for offset correction (never sets the system clock). [TimeProvider] wraps this;
     * [com.vitalwork.app.VitalWorkApplication] kicks off the first sync at startup.
     */
    @Provides
    @Singleton
    fun provideKronosClock(@ApplicationContext context: Context): KronosClock =
        AndroidClockFactory.createKronosClock(
            context,
            ntpHosts = listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org")
        )

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "vitalwork_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideParticipantDao(database: AppDatabase): ParticipantDao =
        database.participantDao()

    @Provides
    @Singleton
    fun provideSessionDao(database: AppDatabase): SessionDao = database.sessionDao()

    @Provides
    @Singleton
    fun provideScenarioDao(database: AppDatabase): ScenarioDao = database.scenarioDao()

    @Provides
    @Singleton
    fun provideSensorSampleDao(database: AppDatabase): SensorSampleDao =
        database.sensorSampleDao()

    @Provides
    @Singleton
    fun provideBleManager(
        @ApplicationContext context: Context
    ): BleManager {
        return BleManagerImpl(context)
    }

    @Provides
    @Singleton
    @Named("respiration")
    fun provideMindfieldRespiration(): SensorDevice {
        return MindfieldRespiration
    }

    @Provides
    @Singleton
    fun provideScenarioRecordingRepository(
        bleManager: BleManager,
        @Named("respiration") respirationDevice: SensorDevice,
        scenarioRepository: ScenarioRepository,
        watchReceiver: WatchSensorReceiver,
        timeProvider: TimeProvider
    ): ScenarioRecordingRepository {
        return ScenarioRecordingRepositoryImpl(
            bleManager, respirationDevice, scenarioRepository, watchReceiver, timeProvider
        )
    }
}
