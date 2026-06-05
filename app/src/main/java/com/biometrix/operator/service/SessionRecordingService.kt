package com.biometrix.operator.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.biometrix.operator.BioMetrixOperatorApplication.Companion.RECORDING_CHANNEL_ID
import com.biometrix.operator.MainActivity
import com.biometrix.operator.R
import com.biometrix.operator.data.recording.ScenarioRecordingRepository
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.repository.SessionRepository
import com.biometrix.operator.data.vr.VrHttpServer
import kotlinx.coroutines.runBlocking
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Foreground service that runs for the lifetime of an ACTIVE session. It does not own any
 * sensor logic — the app-scoped singletons ([ScenarioRecordingRepository], BLE manager,
 * respiration device, VR client) keep doing the work. This service's only job is to keep the
 * process alive and legally retain microphone + connected-device + network access while the
 * screen is locked, by holding foreground status (+ a Wi-Fi lock) until the session ends.
 *
 * Lifecycle is self-managed: the UI starts the service when entering an ACTIVE session, and the
 * service stops itself once [SessionRepository.activeSession] emits null (which happens when the
 * session is ended → COMPLETED, or discarded → deleted). No explicit stop call is needed.
 */
@AndroidEntryPoint
class SessionRecordingService : Service() {

    @Inject lateinit var sessionRepository: SessionRepository
    @Inject lateinit var recordingRepository: ScenarioRecordingRepository
    @Inject lateinit var vrHttpServer: VrHttpServer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    @Suppress("DEPRECATION") // WifiLock is the supported way to keep Wi-Fi awake under Doze
    private var wifiLock: WifiManager.WifiLock? = null

    private var hadActiveSession = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWifiLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately (must be within ~5s of start). Safe to call again
        // on an already-running service, e.g. after a START_STICKY null-intent restart.
        startForegroundWithType(buildNotification(isRecording = false, durationMs = 0L))
        startObserving()
        // Start the VR HTTP server + UDP beacon for the lifetime of this (ACTIVE) session.
        // The beacon advertises the current active session id so the Quest echoes it back.
        // Idempotent: safe on a START_STICKY null-intent restart.
        vrHttpServer.start { runBlocking { sessionRepository.getActiveSessionOnce()?.id } }
        return START_STICKY
    }

    /**
     * Single observer that (a) self-stops when no session is ACTIVE and (b) keeps the
     * notification in sync with the recording state/duration. All state comes from the
     * repositories, never from intent extras, so a null-intent restart re-attaches cleanly.
     */
    private fun startObserving() {
        if (observerJob != null) return
        observerJob = scope.launch {
            combine(
                sessionRepository.activeSession,
                recordingRepository.recordingState,
                recordingRepository.recordingDurationMs
            ) { activeSession, recordingState, durationMs ->
                Triple(activeSession != null, recordingState == DataRecordingState.RECORDING, durationMs)
            }
                .distinctUntilChanged()
                .collect { (hasActive, isRecording, durationMs) ->
                    if (hasActive) {
                        hadActiveSession = true
                        updateNotification(buildNotification(isRecording, durationMs))
                    } else if (hadActiveSession) {
                        // Session ended or discarded — tear down. (Guarded so we don't stop
                        // during the brief window before the first ACTIVE emission arrives.)
                        stopForegroundAndSelf()
                    }
                }
        }
    }

    private fun startForegroundWithType(notification: Notification) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, computeForegroundType())
    }

    /**
     * Builds the foreground-service type from currently granted permissions. `connectedDevice`
     * (BLE) + `dataSync` (VR WebSocket persistence) are always claimed; `microphone` is added
     * only when RECORD_AUDIO is granted — on API 34+ a microphone-typed FGS throws if the
     * permission is missing, so a BLE-only session must omit it.
     */
    private fun computeForegroundType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    private fun buildNotification(isRecording: Boolean, durationMs: Long): Notification {
        val contentText = if (isRecording) {
            getString(R.string.recording_notification_recording, formatDuration(durationMs))
        } else {
            getString(R.string.recording_notification_idle)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, RECORDING_CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        // Refresh the same foreground notification; re-asserting the type is harmless and keeps
        // it correct if RECORD_AUDIO was granted after the service started.
        startForegroundWithType(notification)
    }

    private fun stopForegroundAndSelf() {
        vrHttpServer.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWifiLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        // The Wi-Fi lock is an optimization to keep the VR WebSocket alive under Doze — it must
        // never crash the service. Degrade gracefully if it can't be acquired.
        try {
            wifiLock = wifiManager.createWifiLock(mode, WIFI_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            wifiLock = null
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onDestroy() {
        observerJob?.cancel()
        observerJob = null
        scope.cancel()
        vrHttpServer.stop()
        releaseWifiLock()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val WIFI_LOCK_TAG = "BioMetrix:SessionRecording"

        /** Start the service from a foreground context (e.g. the active-session screen). */
        fun start(context: Context) {
            val intent = Intent(context, SessionRecordingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
