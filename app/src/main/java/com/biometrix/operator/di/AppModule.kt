package com.biometrix.operator.di

import android.content.Context
import androidx.room.Room
import com.biometrix.operator.data.db.AppDatabase
import com.biometrix.operator.data.db.RecordingDao
import com.biometrix.operator.data.db.SensorSampleDao
import com.biometrix.operator.data.db.SudsEventDao
import com.biometrix.operator.data.db.TestDao
import com.biometrix.operator.data.export.TestExportService
import com.biometrix.operator.data.export.TestExporter
import com.biometrix.operator.data.prefs.HeartRateDevice
import com.biometrix.operator.data.prefs.HeartRateDevicePreferences
import com.biometrix.operator.data.recording.SensorRecordingRepository
import com.biometrix.operator.data.recording.SensorRecordingRepositoryImpl
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.sensor.SensorDevice
import com.biometrix.operator.data.sensor.audio.MindfieldRespiration
import com.biometrix.operator.data.db.BloodPressureEventDao
import com.biometrix.operator.data.sensor.ble.BeurerbC87Manager
import com.biometrix.operator.data.sensor.ble.BeurerbC87ManagerImpl
import com.biometrix.operator.data.sensor.ble.BleManager
import com.biometrix.operator.data.sensor.ble.BleManagerImpl
import com.biometrix.operator.data.sensor.fibion.FibionFlashManager
import com.biometrix.operator.data.sensor.fibion.FibionFlashManagerImpl
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
    abstract fun bindTestExporter(impl: TestExportService): TestExporter
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSelectedHeartRateDeviceFlow(
        prefs: HeartRateDevicePreferences
    ): StateFlow<HeartRateDevice> = prefs.selectedDevice

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
            "claustroff_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideTestDao(database: AppDatabase): TestDao {
        return database.testDao()
    }

    @Provides
    @Singleton
    fun provideRecordingDao(database: AppDatabase): RecordingDao {
        return database.recordingDao()
    }

    @Provides
    @Singleton
    fun provideSensorSampleDao(database: AppDatabase): SensorSampleDao {
        return database.sensorSampleDao()
    }

    @Provides
    @Singleton
    fun provideSudsEventDao(database: AppDatabase): SudsEventDao {
        return database.sudsEventDao()
    }

    @Provides
    @Singleton
    fun provideBloodPressureEventDao(database: AppDatabase): BloodPressureEventDao {
        return database.bloodPressureEventDao()
    }

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
    fun provideBeurerbC87Manager(
        @ApplicationContext context: Context
    ): BeurerbC87Manager {
        return BeurerbC87ManagerImpl(context)
    }

    @Provides
    @Singleton
    fun provideFibionFlashManager(
        @ApplicationContext context: Context
    ): FibionFlashManager {
        return FibionFlashManagerImpl(context)
    }

    @Provides
    @Singleton
    fun provideSensorRecordingRepository(
        bleManager: BleManager,
        @Named("respiration") respirationDevice: SensorDevice,
        fibionFlashManager: FibionFlashManager,
        recordingRepository: RecordingRepository,
        heartRateDevicePreferences: HeartRateDevicePreferences
    ): SensorRecordingRepository {
        return SensorRecordingRepositoryImpl(bleManager, respirationDevice, fibionFlashManager, recordingRepository, heartRateDevicePreferences.selectedDevice)
    }
}
