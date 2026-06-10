package com.biometrix.operator.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service (type `health`) that owns the Samsung Health Sensor SDK on the watch,
 * registers the continuous trackers, and forwards every reading to the paired tablet as a JSON
 * line via [WatchDataSender].
 *
 * Trackers: HEART_RATE_CONTINUOUS (HR + IBI), EDA_CONTINUOUS, + battery. All continuous, streamed
 * live at ~1 Hz. (On-demand sensors — SpO2/ECG/skin-temp — were evaluated and removed: they can't
 * run alongside continuous without pausing it, which broke real-time tracking. Kept the set minimal.)
 */
class WatchSensorService : Service() {

    companion object {
        private const val TAG = "WatchSensorService"
        private const val NOTIF_CHANNEL_ID = "biometrix_watch_sensors"
        private const val NOTIF_ID = 1
        const val ACTION_START = "com.biometrix.operator.wear.START"
        const val ACTION_STOP = "com.biometrix.operator.wear.STOP"

        /**
         * How often the flush loop forces the SDK's buffered batch out *while the AP is awake*.
         * 1 s = smoothest live feed when the screen is on; raising it loses no samples (each keeps
         * its own timestamp, delivered in a batch ≤interval late; phone watchdog is 6 s).
         * NOTE: screen-off in Doze the AP suspends and this loop's `delay()` freezes regardless —
         * data is then delivered in bursts on the next maintenance wake, with timestamps intact.
         * See doc/sensor_galaxy_watch.md (a wake lock cannot defeat Wear Doze — measured DISABLED).
         */
        private const val FLUSH_INTERVAL_MS = 1_000L

        /**
         * Heartbeat cadence. ~30 s is frequent enough to land inside a Doze maintenance window yet
         * cheap on the radio/battery. The phone treats a heartbeat-within-window (but no readings) as
         * "dozing/buffering" rather than DISCONNECTED.
         */
        private const val HEARTBEAT_INTERVAL_MS = 30_000L

        /** Observable state for the watch UI. */
        val isTracking = MutableStateFlow(false)
        val connectionText = MutableStateFlow("Idle")
        private val _availableTrackers = MutableStateFlow<List<String>>(emptyList())
        val availableTrackers: StateFlow<List<String>> = _availableTrackers.asStateFlow()
        private val _lastValues = MutableStateFlow<Map<String, Float>>(emptyMap())
        val lastValues: StateFlow<Map<String, Float>> = _lastValues.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var sender: WatchDataSender
    private lateinit var store: WatchSampleStore
    private var trackingService: HealthTrackingService? = null
    private val activeTrackers = mutableListOf<HealthTracker>()
    private var batteryJob: Job? = null
    private var flushJob: Job? = null
    private var heartbeatJob: Job? = null
    private var capabilitiesCsv: String? = null
    private var capabilitiesResendJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sender = WatchDataSender(this)
        store = WatchSampleStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }
        // Any non-STOP delivery (incl. null-action sticky redelivery) must (re)promote to foreground
        // within ~5s or the OS kills us. Promote FIRST, unconditionally, before the init guard.
        ensureForeground()
        startTracking()
        // Re-deliver the explicit ACTION_START on restart so the action is never null next time.
        return START_REDELIVER_INTENT
    }

    /** Promote to a typed (health) foreground service. Idempotent; safe to call repeatedly. */
    private fun ensureForeground() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(health) failed", e)
        }
    }

    private fun startTracking() {
        // Idempotency guard on the REAL resource (instance), not the static UI flag — prevents
        // rebuilding the SDK connection on service re-entry/redelivery (the connect/unbind churn).
        if (trackingService != null) return
        // Fresh tracking session: drop any rows left over from a prior session that was already
        // flushed & acked (or abandoned). A new session starts with an empty durable store.
        store.clear()
        isTracking.value = true
        connectionText.value = "Connecting…"

        sender.connect { ok ->
            connectionText.value = if (ok) "Linked" else "Phone not found"
        }

        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.i(TAG, "Health SDK connected")
                val service = trackingService ?: return
                val caps = try {
                    service.trackingCapability.supportHealthTrackerTypes
                } catch (e: Exception) {
                    Log.e(TAG, "capability query failed", e); emptyList()
                }
                _availableTrackers.value = caps.map { it.name }
                connectionText.value = "SDK connected (${caps.size} trackers)"
                // Best-effort messages can drop; resend capabilities a few times so a single
                // lost message doesn't leave the phone's tracker list empty forever.
                capabilitiesCsv = caps.joinToString(",") { it.name }
                startCapabilitiesResend()
                registerBaselineTrackers(service, caps)
            }

            override fun onConnectionEnded() {
                Log.i(TAG, "Health SDK connection ended")
                connectionText.value = "SDK disconnected"
            }

            override fun onConnectionFailed(e: HealthTrackerException) {
                Log.e(TAG, "Health SDK connection failed: ${e.message}")
                connectionText.value = "SDK error: ${e.message}"
            }
        }

        try {
            trackingService = HealthTrackingService(listener, this).also { it.connectService() }
        } catch (e: Exception) {
            Log.e(TAG, "connectService failed", e)
            connectionText.value = "SDK init failed"
        }

        startBatteryReporting()
        startHeartbeat()
    }

    /**
     * Emit a low-rate "alive, just dozing" beacon so the phone can show "buffering" instead of
     * "Disconnected" during expected screen-off/Doze gaps. Like the flush loop, the `delay()` freezes
     * while the AP is suspended — but it fires at each Doze maintenance wake, which is exactly when the
     * phone needs to hear "still here" to avoid flapping to DISCONNECTED.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                runCatching { sender.sendLine(WatchMessage.heartbeat()) }
            }
        }
    }

    private fun registerBaselineTrackers(
        service: HealthTrackingService,
        caps: List<HealthTrackerType>
    ) {
        // HEART_RATE_CONTINUOUS yields BOTH HR and IBI in one HeartRateSet.
        if (HealthTrackerType.HEART_RATE_CONTINUOUS in caps) {
            register(service, HealthTrackerType.HEART_RATE_CONTINUOUS) { dp, out ->
                handleHeartRate(dp, out)
            }
        }
        if (HealthTrackerType.EDA_CONTINUOUS in caps) {
            register(service, HealthTrackerType.EDA_CONTINUOUS) { dp, out ->
                val eda = dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE)
                val status = dp.getValue(ValueKey.EdaSet.STATUS)
                emit("EDA", eda, status, out)
            }
        }
    }

    private fun register(
        service: HealthTrackingService,
        type: HealthTrackerType,
        onPoint: (DataPoint, MutableList<String>) -> Unit
    ) {
        try {
            val tracker = service.getHealthTracker(type)
            tracker.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(data: MutableList<DataPoint>) {
                    // One callback may carry several 1 Hz samples (screen off). Collect them all
                    // and send as ONE bundled message — fewer radio wakeups, Doze-friendlier, and
                    // each reading keeps its own timestamp so the phone unpacks them 1:1.
                    val out = ArrayList<String>(data.size + 4)
                    data.forEach { runCatching { onPoint(it, out) }.onFailure { Log.e(TAG, "parse $type", it) } }
                    if (out.isNotEmpty()) sender.sendBatch(out)
                }
                override fun onFlushCompleted() {}
                override fun onError(error: HealthTracker.TrackerError?) {
                    Log.e(TAG, "tracker $type error: $error")
                }
            })
            activeTrackers += tracker
            Log.i(TAG, "registered $type")
            startFlushLoop()
        } catch (e: Exception) {
            Log.e(TAG, "register $type failed", e)
        }
    }

    /** HR + IBI live in one HeartRateSet; both are status-gated (HR and IBI use DIFFERENT conventions). */
    private fun handleHeartRate(dp: DataPoint, out: MutableList<String>) {
        val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
        val hrStatus = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
        // Samsung HR convention: status == 1 means a successful reading; anything else (warm-up,
        // poor contact during doze, -3 not-worn) reports HR=0. Forwarding those 0s made the phone
        // flash 0.0 at startup (~15 s warm-up) and flap 0→value→0 in sleep. Skip invalid samples so
        // the phone keeps the last good value instead.
        if (hrStatus == 1 && hr > 0) emit("HR", hr.toFloat(), hrStatus, out)

        val ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST)
        val ibiStatusList = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
        ibiList.forEachIndexed { i, ibi ->
            val status = ibiStatusList.getOrNull(i) ?: 1
            // Valid IBI per Samsung spec: status == 0 && value != 0
            if (status == 0 && ibi != 0) emit("IBI", ibi.toFloat(), 0, out)
        }
    }

    /**
     * Update the on-watch UI value, persist the reading to the durable store, and append it to the
     * current outgoing batch. The JSON line is built ONCE so the stored copy and the streamed copy
     * carry the identical timestamp — the phone's timestamp-window attribution relies on that match.
     */
    private fun emit(type: String, value: Float, accuracy: Int, out: MutableList<String>) {
        _lastValues.value = _lastValues.value.toMutableMap().apply { put(type, value) }
        val line = WatchMessage.reading(type, value, accuracy)
        // Durable record first (survives Doze / dropped messages); then stream live for the display.
        if (store.shouldPersist(type)) store.append(line)
        out.add(line)
    }

    private fun startCapabilitiesResend() {
        capabilitiesResendJob?.cancel()
        capabilitiesResendJob = scope.launch {
            // Resend ~every 2s for the first ~10s so one dropped message isn't permanent.
            repeat(5) {
                capabilitiesCsv?.let { sender.sendLine(WatchMessage.capabilities(it)) }
                delay(2_000)
            }
        }
    }

    /**
     * When the screen is off the Samsung SDK collects samples in batches and only delivers them
     * when the AP next wakes. [HealthTracker.flush] forces the batched data out immediately, so
     * driving it on [FLUSH_INTERVAL_MS] keeps the feed continuous **while the screen is on**.
     *
     * Screen-off in Doze this loop's `delay()` freezes (the AP suspends) and data reverts to
     * bursts on the next maintenance wake. That is an unavoidable Wear OS platform limit — a
     * partial wake lock is forcibly DISABLED by Wear Doze (verified on-device; Google issue
     * #228086086), so it cannot be defeated in app code. Crucially the sensors keep **sampling**
     * at 1 Hz on the watch the whole time and every sample carries its own timestamp, so the
     * burst-delivered data is complete and correctly ordered — only live latency suffers, not the
     * recorded data. See doc/sensor_galaxy_watch.md.
     */
    private fun startFlushLoop() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                activeTrackers.forEach { runCatching { it.flush() } }
            }
        }
    }

    private fun startBatteryReporting() {
        batteryJob?.cancel()
        batteryJob = scope.launch {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            while (isActive) {
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (level in 0..100) {
                    _lastValues.value = _lastValues.value.toMutableMap().apply { put("BATTERY", level.toFloat()) }
                    sender.sendLine(WatchMessage.reading("BATTERY", level.toFloat(), 0))
                }
                delay(15_000)
            }
        }
    }

    private fun stopTracking() {
        // Tell the phone we're stopping BEFORE tearing down the sender, so it shows DISCONNECTED
        // instantly instead of waiting out the inactivity watchdog. Best-effort: if it drops, the
        // watchdog still catches the silence a few seconds later.
        if (isTracking.value) runCatching { sender.sendLine(WatchMessage.stop()) }
        batteryJob?.cancel(); batteryJob = null
        flushJob?.cancel(); flushJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        capabilitiesResendJob?.cancel(); capabilitiesResendJob = null
        capabilitiesCsv = null
        activeTrackers.forEach { runCatching { it.unsetEventListener() } }
        activeTrackers.clear()
        runCatching { trackingService?.disconnectService() }
        trackingService = null
        sender.close()
        isTracking.value = false
        connectionText.value = "Stopped"
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "Sensor streaming",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("BioMetrix")
            .setContentText("Streaming watch sensors")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }
}
