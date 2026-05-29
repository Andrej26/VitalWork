package com.biometrix.operator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BioMetrixOperatorApplication : Application() {

    override fun onCreate() {
        super.onCreate()
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
