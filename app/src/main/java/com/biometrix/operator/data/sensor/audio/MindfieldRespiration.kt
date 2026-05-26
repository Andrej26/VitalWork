package com.biometrix.operator.data.sensor.audio

import android.content.Context
import com.biometrix.operator.data.sensor.DeviceState
import com.biometrix.operator.data.sensor.SensorDevice
import de.mindfield.esense_sdk_2_lib.HardwareController
import de.mindfield.esense_sdk_2_lib.IValueChangedCallback
import de.mindfield.esense_sdk_2_lib.SensorData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class LowSignalWarning { NONE, WARNING }

object MindfieldRespiration : SensorDevice {

    override val deviceName: String = "eSense Respiration"

    // --- State ---
    private val _state = MutableStateFlow(DeviceState.Disconnected)
    override val state: StateFlow<DeviceState> = _state.asStateFlow()

    private val _dataRate = MutableStateFlow(0f)
    override val dataRate: StateFlow<Float> = _dataRate.asStateFlow()

    private val _detailedStats = MutableStateFlow("Ready")
    override val detailedStats: StateFlow<String> = _detailedStats.asStateFlow()

    private val _events = MutableSharedFlow<String>(replay = 0)
    override val events: SharedFlow<String> = _events.asSharedFlow()

    private val _sampleFlow = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    override val sampleFlow: SharedFlow<Float> = _sampleFlow.asSharedFlow()

    // SCOPE: We use Main here because the SDK Handler MUST be created on Main Looper
    // We will use Dispatchers.Default for heavy calculations if needed, but SDK init is UI-bound.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var controller: HardwareController? = null
    private var watchdogJob: Job? = null

    // --- Internals ---
    private var isSampling = false
    internal var isVerifying = false
    internal var verifyCount = 0
    internal val verifyBuffer = DoubleArray(MAX_VERIFY_BUFFER)

    // Watchdog State
    private var tValid = 0L
    private var lastRA = 0.0

    // Signal Detection
    private var lowSignalStartMs: Long = 0L
    private var highSignalStartMs: Long = 0L
    private val _lowSignalWarning = MutableStateFlow(LowSignalWarning.NONE)
    val lowSignalWarning: StateFlow<LowSignalWarning> = _lowSignalWarning.asStateFlow()

    // Disconnect reason for UI dialogs
    private val _lastDisconnectReason = MutableStateFlow<String?>(null)
    val lastDisconnectReason: StateFlow<String?> = _lastDisconnectReason.asStateFlow()

    // Constants
    private const val SENSOR_TYPE_INT = 3  // respiration (RA), not conductance (2)
    private const val SAMPLE_FREQ = 5
    private const val RANGE_MIN = 0.0
    private const val RANGE_MAX = 460.0  // Normal breathing: 0.5-420 RA; microphone (jack pulled): ~500 RA
    private const val SPIKE_DISCONNECT_MS = 1000L  // 1s sustained above RANGE_MAX → disconnect
    private const val VERIFY_MS = 2500L
    private const val VERIFY_MIN_SAMPLES = 5
    private const val MAX_VERIFY_BUFFER = 100
    private const val VERIFY_DELTA = 0.02
    private const val TIMEOUT_MS = 1250L

    // Low signal detection thresholds
    private const val LOW_SIGNAL_THRESHOLD = 0.4
    private const val LOW_SIGNAL_WARNING_MS = 1500L    // 1.5s -> WARNING

    // Breathing rate calculation - sliding window of RA samples
    private const val RATE_WINDOW_SECONDS = 30
    private const val RATE_WINDOW_SAMPLES = RATE_WINDOW_SECONDS * SAMPLE_FREQ // 150 samples
    private const val RATE_MIN_SAMPLES = 3 * SAMPLE_FREQ // 15 samples (need ≥3s for estimate)
    internal val raBuffer = ArrayDeque<Double>()

    override fun connect(context: Context) {
        if (_state.value == DeviceState.Connecting || _state.value == DeviceState.Connected) return

        _state.value = DeviceState.Connecting
        _detailedStats.value = "Verifying Signal..."
        _lastDisconnectReason.value = null
        raBuffer.clear()
        emitLog("Initializing...")

        scope.launch {
            try {
                // 1. Get Instance (MUST be on Main Thread)
                // This was the cause of the "Flash" crash - it was on a background thread before.
                controller = HardwareController.getInstance()

                // 2. Force Stop & Flush
                if (controller?.isSampling == true) {
                    controller?.stopSampling()
                    // Non-blocking delay (Doesn't freeze UI)
                    delay(200)
                }

                // 3. Toggle Type to force internal reset
                controller?.setSensorType(1)
                controller?.setSampleFrequencyHz(SAMPLE_FREQ)
                controller?.setSensorType(SENSOR_TYPE_INT)

                // 4. Attach Observer
                controller?.removeObserver(sdkObserver)
                controller?.addObserver(sdkObserver)

                // 5. Start
                isVerifying = true
                verifyCount = 0

                controller?.startSampling()
                isSampling = true
                emitLog("Sampling started (Verify Phase)...")

                // 6. Schedule Result Check
                delay(VERIFY_MS)
                finishVerification()

            } catch (e: Exception) {
                forceDisconnect("Init Failed: ${e.message}")
            }
        }
    }

    override fun startStreaming() {
        if (_state.value == DeviceState.Connected) {
            _state.value = DeviceState.Streaming
            emitLog("Streaming Started")

            // Reset timers before starting watchdog
            tValid = android.os.SystemClock.elapsedRealtime()
            startWatchdog()
        }
    }

    override fun stopStreaming() {
        if (_state.value == DeviceState.Streaming) {
            stopWatchdog()
            lowSignalStartMs = 0L
            highSignalStartMs = 0L
            _lowSignalWarning.value = LowSignalWarning.NONE
            _state.value = DeviceState.Connected
            _detailedStats.value = "Paused"
        }
    }

    override fun disconnect() {
        stopWatchdog()
        isVerifying = false
        raBuffer.clear()
        lowSignalStartMs = 0L
        highSignalStartMs = 0L
        _lowSignalWarning.value = LowSignalWarning.NONE

        try {
            if (isSampling) {
                controller?.stopSampling()
                isSampling = false
            }
            controller?.removeObserver(sdkObserver)
        } catch (_: Exception) {}

        _state.value = DeviceState.Disconnected
        _detailedStats.value = "Disconnected"
        _dataRate.value = 0f
        emitLog("Disconnected")
    }

    private val sdkObserver = object : IValueChangedCallback {
        override fun valueHasChanged(data: SensorData?) {
            if (data == null) return

            val ra = data.SampleData  // Respiration Amplitude (RA)

            // Capture for verification
            if (isVerifying) {
                if (verifyCount < MAX_VERIFY_BUFFER) {
                    verifyBuffer[verifyCount++] = ra
                }
                return
            }

            // Streaming
            if (_state.value == DeviceState.Streaming) {
                val now = android.os.SystemClock.elapsedRealtime()
                tValid = now
                lastRA = ra

                // Add to breathing rate calculation buffer
                raBuffer.addLast(ra)
                while (raBuffer.size > RATE_WINDOW_SAMPLES) {
                    raBuffer.removeFirst()
                }

                // Low signal tracking
                if (ra < LOW_SIGNAL_THRESHOLD) {
                    if (lowSignalStartMs == 0L) {
                        lowSignalStartMs = now
                    }
                } else {
                    if (_lowSignalWarning.value != LowSignalWarning.NONE) {
                        _lowSignalWarning.value = LowSignalWarning.NONE
                        emitLog("Signal recovered (RA: ${String.format(Locale.US, "%.2f", ra)})")
                    }
                    lowSignalStartMs = 0L
                }

                // High signal tracking (jack removal → microphone detection)
                if (ra > RANGE_MAX) {
                    if (highSignalStartMs == 0L) highSignalStartMs = now
                } else {
                    highSignalStartMs = 0L
                }

                _dataRate.value = ra.toFloat()
                _sampleFlow.tryEmit(ra.toFloat())
                val br = calculateBreathingRate()
                _detailedStats.value = String.format(Locale.US, "%.1f br/min", br)
            }
        }

        override fun samplingStateChanged(enabled: Boolean) { }
    }

    /**
     * Calculate breathing rate (br/min) from the RA waveform using zero-crossing detection.
     * Counts upward crossings of the mean value, each representing one breath cycle.
     */
    internal fun calculateBreathingRate(): Float {
        if (raBuffer.size < RATE_MIN_SAMPLES) return 0f

        val values = raBuffer.toList()
        val mean = values.average()

        // Count upward zero-crossings (signal crosses above mean = 1 breath)
        var breaths = 0
        var wasAbove = values.first() > mean
        for (v in values) {
            val isAbove = v > mean
            if (isAbove && !wasAbove) breaths++
            wasAbove = isAbove
        }

        val durationSec = values.size.toDouble() / SAMPLE_FREQ
        return ((breaths / durationSec) * 60.0).toFloat()
    }

    internal fun finishVerification() {
        isVerifying = false

        if (verifyCount < VERIFY_MIN_SAMPLES) {
            forceDisconnect("No Signal (Received $verifyCount samples).")
            return
        }

        var min = Double.MAX_VALUE
        var max = Double.MIN_VALUE
        for (i in 0 until verifyCount) {
            val v = verifyBuffer[i]
            if (v < min) min = v
            if (v > max) max = v
        }

        val inRange = min >= RANGE_MIN && max <= RANGE_MAX
        val hasMovement = (max - min) >= VERIFY_DELTA

        if (inRange && hasMovement) {
            emitLog("Verified (RA: $min - $max).")
            _state.value = DeviceState.Connected
            _detailedStats.value = "Connected"

            lastRA = (min + max) / 2.0

            // Auto-start streaming so data flows immediately after verification
            startStreaming()
        } else {
            forceDisconnect("Signal Out of Range (RA: $min - $max).")
        }
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(2000)

                if (_state.value == DeviceState.Streaming) {
                    val now = android.os.SystemClock.elapsedRealtime()

                    if (now - tValid > TIMEOUT_MS) {
                        forceDisconnect("Connection Lost (No Data)")
                        break
                    }

                    if (lastRA < RANGE_MIN) {
                        forceDisconnect("Signal Out of Range")
                        break
                    }

                    // Sustained high signal → likely jack removed (microphone active)
                    if (highSignalStartMs > 0L) {
                        val highDuration = now - highSignalStartMs
                        if (highDuration >= SPIKE_DISCONNECT_MS) {
                            forceDisconnect("Signal abnormality detected (device may be disconnected)")
                            break
                        }
                    }

                    // Low signal detection
                    if (lowSignalStartMs > 0L) {
                        val lowDuration = now - lowSignalStartMs
                        if (lowDuration >= LOW_SIGNAL_WARNING_MS && _lowSignalWarning.value == LowSignalWarning.NONE) {
                            _lowSignalWarning.value = LowSignalWarning.WARNING
                            emitLog("WARN: Low signal detected (RA < $LOW_SIGNAL_THRESHOLD for ${lowDuration / 1000}s). Check chest strap placement.")
                        }
                    }
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun forceDisconnect(reason: String) {
        emitLog("ERR: $reason")
        _lastDisconnectReason.value = reason
        disconnect()
    }

    fun clearDisconnectReason() {
        _lastDisconnectReason.value = null
    }

    private fun emitLog(msg: String) {
        scope.launch { _events.emit(msg) }
    }
}
