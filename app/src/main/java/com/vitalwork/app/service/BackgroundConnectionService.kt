package com.vitalwork.app.service

import android.Manifest
import android.app.Activity
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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.vitalwork.app.MainActivity
import com.vitalwork.app.R
import com.vitalwork.app.VitalWorkApplication.Companion.BACKGROUND_CHANNEL_ID
import com.vitalwork.app.data.link.PeerLinkManager
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.recording.ScenarioRecordingRepository
import com.vitalwork.app.data.recording.model.DataRecordingState
import com.vitalwork.app.data.system.KeepAliveCoordinator
import com.vitalwork.app.data.system.KeepAliveReason
import com.vitalwork.app.data.webrtc.ScreenShareController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * The single app-wide foreground service. It owns no connection logic — the app-scoped singletons
 * ([ScenarioRecordingRepository], [PeerLinkManager], …) keep doing the work. Its only job is to keep
 * the process alive (+ a Wi-Fi lock) while [KeepAliveCoordinator] reports at least one active reason
 * (a recording SESSION and/or a device LINK), so every connection survives the screen turning off.
 *
 * Replaces the former per-feature `SessionRecordingService`. Lifecycle is coordinator-driven: it
 * starts on the empty→non-empty edge (via [com.vitalwork.app.data.system.ForegroundServiceLauncher])
 * and self-stops when the reason set drains.
 */
@AndroidEntryPoint
class BackgroundConnectionService : Service() {

    @Inject lateinit var coordinator: KeepAliveCoordinator
    @Inject lateinit var recordingRepository: ScenarioRecordingRepository
    @Inject lateinit var linkManager: PeerLinkManager
    @Inject lateinit var screenShareController: ScreenShareController

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var watchdogJob: Job? = null
    private var hadAnyReason = false

    @Suppress("DEPRECATION") // WifiLock is the supported way to keep Wi-Fi awake under Doze
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BackgroundConnectionService.onCreate")
        acquireWifiLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "BackgroundConnectionService.onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_DISCONNECT_LINK ->
                // Manual disconnect from the notification — releases LINK; the observer self-stops
                // if no other reason remains.
                linkManager.stop()
            ACTION_START_SCREEN ->
                // Acquire BEFORE startForeground so the synchronous promotion below already includes
                // the mediaProjection type (Android requires it before creating the projection).
                coordinator.acquire(KeepAliveReason.SCREEN_SHARE)
        }

        val startingScreenShare = intent?.action == ACTION_START_SCREEN
        // Promote to foreground immediately (must be within ~5s of start). For the screen-share path,
        // force the mediaProjection type into the snapshot explicitly so this promotion always carries
        // it — `snapshot()` reads the coordinator's reason set, which may not yet reflect the just-made
        // acquire, and a missing mediaProjection type makes MediaProjection.startCapture() throw.
        startForegroundFromState(snapshot(), forceScreenShare = startingScreenShare)

        if (startingScreenShare) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
            if (data != null) {
                screenShareController.beginCapture(resultCode, data)
            } else {
                coordinator.release(KeepAliveReason.SCREEN_SHARE)
            }
        }

        startObserving()
        startRestartWatchdog()
        return START_STICKY
    }

    private data class State(
        val reasons: Set<KeepAliveReason>,
        val isRecording: Boolean,
        val durationMs: Long,
        val linkState: ConnectionState,
        val peerLabel: String?
    )

    private fun snapshot() = State(
        reasons = coordinator.activeReasons.value,
        isRecording = recordingRepository.recordingState.value == DataRecordingState.RECORDING,
        durationMs = recordingRepository.recordingDurationMs.value,
        linkState = linkManager.connectionState.value,
        peerLabel = linkManager.peerLabel.value
    )

    private fun startObserving() {
        if (observerJob != null) return
        observerJob = scope.launch {
            combine(
                coordinator.activeReasons,
                recordingRepository.recordingState,
                recordingRepository.recordingDurationMs,
                linkManager.connectionState,
                linkManager.peerLabel
            ) { reasons, recState, durationMs, linkState, peerLabel ->
                State(reasons, recState == DataRecordingState.RECORDING, durationMs, linkState, peerLabel)
            }
                .distinctUntilChanged()
                .collect { state ->
                    if (state.reasons.isNotEmpty()) {
                        hadAnyReason = true
                        startForegroundFromState(state)
                    } else if (hadAnyReason) {
                        stopForegroundAndSelf("all keep-alive reasons released")
                    }
                }
        }
    }

    /**
     * Guards the `START_STICKY` null-intent restart case: if the in-memory reason set was lost and
     * nothing re-acquires within a short grace, stop instead of leaking a foreground service.
     */
    private fun startRestartWatchdog() {
        if (watchdogJob != null || hadAnyReason) return
        watchdogJob = scope.launch {
            delay(RESTART_GRACE_MS)
            if (!hadAnyReason && coordinator.activeReasons.value.isEmpty()) {
                stopForegroundAndSelf("no reason after restart grace")
            }
        }
    }

    private fun startForegroundFromState(state: State, forceScreenShare: Boolean = false) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(state),
            computeForegroundType(state.reasons, forceScreenShare)
        )
    }

    /**
     * `connectedDevice` + `dataSync` are always claimed; `microphone` is added only while a SESSION is
     * active AND RECORD_AUDIO is granted — on API 34+ a mic-typed FGS throws without the permission,
     * and a link-only service must not claim a mic type it never uses.
     */
    private fun computeForegroundType(reasons: Set<KeepAliveReason>, forceScreenShare: Boolean = false): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (KeepAliveReason.SESSION in reasons && micGranted) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        // `forceScreenShare` covers the ACTION_START_SCREEN promotion, where the coordinator's reason
        // set may not yet observably contain SCREEN_SHARE but the mediaProjection type must be present.
        if (forceScreenShare || KeepAliveReason.SCREEN_SHARE in reasons) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return type
    }

    private fun buildNotification(state: State): Notification {
        val parts = buildList {
            if (KeepAliveReason.SESSION in state.reasons) {
                add(
                    if (state.isRecording) {
                        getString(R.string.recording_notification_recording, formatDuration(state.durationMs))
                    } else {
                        getString(R.string.recording_notification_idle)
                    }
                )
            }
            if (KeepAliveReason.SCREEN_SHARE in state.reasons) {
                add(getString(R.string.screen_share_notification))
            }
            if (KeepAliveReason.LINK in state.reasons) {
                add(
                    if (state.linkState == ConnectionState.CONNECTED) {
                        getString(R.string.link_notification_connected, state.peerLabel.orEmpty())
                    } else {
                        getString(R.string.link_notification_hosting)
                    }
                )
            }
        }
        val contentText = parts.joinToString(" • ").ifEmpty {
            getString(R.string.background_notification_title)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, BACKGROUND_CHANNEL_ID)
            .setContentTitle(getString(R.string.background_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Offer Disconnect only for the device link (session stop stays in-app). Plain getService:
        // the service is already running/foreground, so no startForeground-within-5s obligation.
        if (KeepAliveReason.LINK in state.reasons) {
            val disconnectIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, BackgroundConnectionService::class.java).apply { action = ACTION_DISCONNECT_LINK },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notification_disconnect), disconnectIntent)
        }
        return builder.build()
    }

    private fun stopForegroundAndSelf(reason: String) {
        Log.i(TAG, "BackgroundConnectionService.stopForegroundAndSelf ($reason)")
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
        Log.i(TAG, "BackgroundConnectionService.onDestroy")
        observerJob?.cancel()
        watchdogJob?.cancel()
        scope.cancel()
        releaseWifiLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VitalWorkLifecycle"
        private const val NOTIFICATION_ID = 1001
        private const val WIFI_LOCK_TAG = "VitalWork:BackgroundConnection"
        private const val RESTART_GRACE_MS = 5000L
        const val ACTION_DISCONNECT_LINK = "com.vitalwork.app.action.DISCONNECT_LINK"
        const val ACTION_START_SCREEN = "com.vitalwork.app.action.START_SCREEN"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        /**
         * Promote the service to a `mediaProjection` foreground service and start screen capture with
         * the consent result. Must be called from a foreground context (right after the consent
         * dialog) so the background-FGS-start restriction doesn't apply.
         */
        fun startScreenShare(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, BackgroundConnectionService::class.java).apply {
                action = ACTION_START_SCREEN
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
