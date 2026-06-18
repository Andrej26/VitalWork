package com.vitalwork.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.lyft.kronos.KronosClock
import com.vitalwork.app.data.system.KeepAliveCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import java.util.UUID

@HiltAndroidApp
class VitalWorkApplication : Application() {

    /** Hilt can't field-inject an [Application], so pull singletons out via an entry point. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppEntryPoint {
        fun kronosClock(): KronosClock
        fun keepAliveCoordinator(): KeepAliveCoordinator
    }

    override fun onCreate() {
        super.onCreate()
        // A fresh process-instance id on each cold start. If this id changes mid-session (e.g. after
        // backgrounding while another app filmed video), the OS killed and recreated our process —
        // the root cause behind sensors dropping + scenarios left open (see BackgroundConnectionService).
        Log.i(TAG, "process start id=${UUID.randomUUID().toString().take(8)}")
        val entryPoint = EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java)
        // Kick off NTP sync at startup so persisted timestamps land on true UTC (see TimeProvider).
        // Non-blocking; falls back to the device clock until the first sync completes.
        entryPoint.kronosClock().syncInBackground()
        // Drive the SESSION keep-alive reason app-wide from process start (re-acquires a still-ACTIVE
        // session after a process restart).
        entryPoint.keepAliveCoordinator().start()
        createBackgroundChannel()
    }

    private fun createBackgroundChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BACKGROUND_CHANNEL_ID,
                getString(R.string.background_channel_name),
                // LOW: visible + ongoing, but silent (no sound/vibration).
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.background_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "VitalWorkLifecycle"
        const val BACKGROUND_CHANNEL_ID = "vitalwork_background"
    }
}
