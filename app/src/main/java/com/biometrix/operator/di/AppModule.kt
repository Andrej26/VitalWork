package com.biometrix.operator.di

import android.content.Context
import androidx.room.Room
import com.biometrix.operator.data.db.AppDatabase
import com.biometrix.operator.data.db.ParticipantDao
import com.biometrix.operator.data.db.ScenarioDao
import com.biometrix.operator.data.db.SensorSampleDao
import com.biometrix.operator.data.db.SessionDao
import com.biometrix.operator.data.export.SessionExportService
import com.biometrix.operator.data.export.SessionExporter
import com.biometrix.operator.data.export.SessionUploader
import com.biometrix.operator.data.recording.ScenarioRecordingRepository
import com.biometrix.operator.data.recording.ScenarioRecordingRepositoryImpl
import com.biometrix.operator.data.repository.ScenarioRepository
import com.biometrix.operator.data.sensor.SensorDevice
import com.biometrix.operator.data.sensor.audio.MindfieldRespiration
import com.biometrix.operator.data.sensor.ble.BleManager
import com.biometrix.operator.data.sensor.ble.BleManagerImpl
import com.biometrix.operator.data.network.NetworkChecker
import com.biometrix.operator.data.system.LocationChecker
import com.biometrix.operator.data.system.LocationCheckerImpl
import com.biometrix.operator.data.vr.MdnsDiscoveryService
import com.biometrix.operator.data.vr.VRConnectionManager
import com.biometrix.operator.data.vr.VRWebSocketClient
import com.biometrix.operator.data.vr.VrDeviceDiscovery
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
    abstract fun bindSessionExporter(impl: SessionExportService): SessionExporter

    @Binds
    @Singleton
    abstract fun bindSessionUploader(impl: SessionExportService): SessionUploader
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @Named("lanAvailable")
    fun provideLanAvailableFlow(networkChecker: NetworkChecker): StateFlow<Boolean> =
        networkChecker.lanAvailable

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "biometrix_database"
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
    fun provideVRConnectionManager(networkChecker: NetworkChecker): VRConnectionManager {
        return VRWebSocketClient(networkChecker)
    }

    @Provides
    @Singleton
    fun provideVrDeviceDiscovery(mdnsDiscoveryService: MdnsDiscoveryService): VrDeviceDiscovery {
        return mdnsDiscoveryService
    }

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
        scenarioRepository: ScenarioRepository
    ): ScenarioRecordingRepository {
        return ScenarioRecordingRepositoryImpl(bleManager, respirationDevice, scenarioRepository)
    }
}
