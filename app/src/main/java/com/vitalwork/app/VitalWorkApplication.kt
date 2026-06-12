package com.vitalwork.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.lyft.kronos.KronosClock
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

@HiltAndroidApp
class VitalWorkApplication : Application() {

    /** Hilt can't field-inject an [Application], so pull the singleton clock out via an entry point. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ClockEntryPoint {
        fun kronosClock(): KronosClock
    }

    override fun onCreate() {
        super.onCreate()
        // Kick off NTP sync at startup so persisted timestamps land on true UTC (see TimeProvider).
        // Non-blocking; falls back to the device clock until the first sync completes.
        EntryPointAccessors.fromApplication(this, ClockEntryPoint::class.java)
            .kronosClock()
            .syncInBackground()
        createRecordingNotificationChannel()
    }

    private fun createRecordingNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RECORDING_CHANNEL_ID,
                getString(R.string.recording_channel_name),
                // LOW: visible + ongoing, but silent (no sound/vibration during a session)
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.recording_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val RECORDING_CHANNEL_ID = "session_recording"
    }
}
