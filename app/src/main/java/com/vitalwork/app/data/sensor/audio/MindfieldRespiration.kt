package com.vitalwork.app.data.sensor.audio

import android.content.Context
import com.vitalwork.app.data.sensor.DeviceState
import com.vitalwork.app.data.sensor.SensorDevice
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

/**
 * The one respiration problem worth putting in front of the operator. Only ever one at a time:
 * [SIGNAL_LOST] wins over [NO_BREATHING] because it is the specific, actually-observed failure
 * (the strap comes off and RA collapses to ~0.4), and two banners saying "check the strap" are
 * worse than one.
 */
enum class RespirationWarning {
    NONE,

    /** RA below [MindfieldRespiration.SIGNAL_LOST_THRESHOLD_RA] — strap off, or no chest contact. */
    SIGNAL_LOST,

    /** RA healthy but the waveform does not track breathing — strap on but loose, or a breath hold. */
    NO_BREATHING
}

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
    private var connectJob: Job? = null

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
    private var noBreathingStartMs: Long = 0L
    private val _warning = MutableStateFlow(RespirationWarning.NONE)
    val warning: StateFlow<RespirationWarning> = _warning.asStateFlow()

    /** Latest breathing-rate verdict. Display only — never recorded (see [BreathingRateEstimator]). */
    private val _breathingEstimate =
        MutableStateFlow<BreathingRateEstimate>(BreathingRateEstimate.Warmup)
    val breathingEstimate: StateFlow<BreathingRateEstimate> = _breathingEstimate.asStateFlow()

    /** Last verdict *logged*, so the event log gets one line per change instead of five per second. */
    private var loggedEstimate: BreathingRateEstimate? = null

    // Disconnect reason for UI dialogs
    private val _lastDisconnectReason = MutableStateFlow<String?>(null)
    val lastDisconnectReason: StateFlow<String?> = _lastDisconnectReason.asStateFlow()

    // Constants
    private const val SENSOR_TYPE_INT = 3  // respiration (RA), not conductance (2)
    private const val SAMPLE_FREQ = 5
    private const val RANGE_MIN = 0.0
    // Measured on this hardware: a worn strap rests around 200-250 RA and swings 150-300 on deeper
    // breaths; a strap off the chest falls below 1. Device range is ~0-450, and the tablet
    // microphone (jack pulled) reads ~500.
    private const val RANGE_MAX = 460.0
    private const val SPIKE_DISCONNECT_MS = 1000L  // 1s sustained above RANGE_MAX → disconnect
    private const val VERIFY_MS = 2500L
    private const val VERIFY_MIN_SAMPLES = 5
    private const val MAX_VERIFY_BUFFER = 100
    private const val VERIFY_DELTA = 0.02
    private const val TIMEOUT_MS = 1250L

    /**
     * RA below this means the strap has lost chest contact. A loosened strap drops to ~0.4 in
     * practice, so 0.4 itself would sit exactly on the observed value with no headroom; 0.8 keeps
     * a 2× margin below while staying ~190× under real breathing (RA 150–300), so a false alarm is
     * impossible. **Shared with the export-time detection** in
     * [com.vitalwork.app.data.recording.detectRespirationIssues] so the banner and the recorded data
     * can never disagree.
     */
    const val SIGNAL_LOST_THRESHOLD_RA = 0.8
    private const val SIGNAL_LOST_WARNING_MS = 1500L
    /** How long [BreathingRateEstimate.NoBreathing] must hold before the operator is told. */
    private const val NO_BREATHING_WARNING_MS = 20_000L

    // Sliding window feeding the breathing-rate estimate.
    // Display-only: the recorded/exported RESPIRATION samples are the raw RA waveform, never this.
    private const val RATE_WINDOW_SECONDS = 60
    private const val RATE_WINDOW_SAMPLES = RATE_WINDOW_SECONDS * SAMPLE_FREQ // 300 samples

    /**
     * Guarded by its own monitor: appended from the SDK callback thread, cleared from
     * [disconnect]/[stopStreaming] on other threads, and snapshotted for the estimator.
     */
    internal val raBuffer = ArrayDeque<Double>()

    override fun connect(context: Context) {
        if (_state.value == DeviceState.Connecting || _state.value == DeviceState.Connected) return

        _state.value = DeviceState.Connecting
        _detailedStats.value = "Verifying Signal..."
        _lastDisconnectReason.value = null
        resetEstimateState()
        emitLog("Initializing...")

        connectJob = scope.launch {
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

            } catch (e: CancellationException) {
                // Cancelled by disconnect() during the verify window — do NOT resurrect the sensor
                // via forceDisconnect below; just unwind cleanly.
                throw e
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
            // Drop the rate window: samples from before the pause would otherwise be mixed with
            // post-resume ones across an arbitrary time gap.
            resetEstimateState()
            highSignalStartMs = 0L
            _state.value = DeviceState.Connected
            _detailedStats.value = "Paused"
        }
    }

    /** Clears the rate window and every derived verdict, so a resume starts from a clean warmup. */
    private fun resetEstimateState() {
        synchronized(raBuffer) { raBuffer.clear() }
        lowSignalStartMs = 0L
        noBreathingStartMs = 0L
        loggedEstimate = null
        _breathingEstimate.value = BreathingRateEstimate.Warmup
        _warning.value = RespirationWarning.NONE
    }

    override fun disconnect() {
        // Cancel any in-flight connect/verify coroutine first, so a disconnect during the 2.5s
        // verify window can't let finishVerification() run afterwards and resurrect the sensor.
        connectJob?.cancel()
        connectJob = null
        stopWatchdog()
        isVerifying = false
        resetEstimateState()
        highSignalStartMs = 0L

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
                val window = synchronized(raBuffer) {
                    raBuffer.addLast(ra)
                    while (raBuffer.size > RATE_WINDOW_SAMPLES) {
                        raBuffer.removeFirst()
                    }
                    raBuffer.toList()
                }

                // Signal-lost tracking (strap off the chest)
                if (ra < SIGNAL_LOST_THRESHOLD_RA) {
                    if (lowSignalStartMs == 0L) {
                        lowSignalStartMs = now
                    }
                } else {
                    if (_warning.value == RespirationWarning.SIGNAL_LOST) {
                        _warning.value = RespirationWarning.NONE
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

                // Raw RA is what is streamed to the recorder and shown as the primary value.
                _dataRate.value = ra.toFloat()
                _sampleFlow.tryEmit(ra.toFloat())

                val estimate = BreathingRateEstimator.estimate(window, SAMPLE_FREQ)
                _breathingEstimate.value = estimate
                _detailedStats.value = formatEstimate(estimate)
                trackNoBreathing(estimate, now)
                logEstimateChange(estimate)
            }
        }

        override fun samplingStateChanged(enabled: Boolean) { }
    }

    /** The small monospace readout on the sensor screen. */
    private fun formatEstimate(estimate: BreathingRateEstimate): String = when (estimate) {
        BreathingRateEstimate.Warmup -> "-- br/min"
        BreathingRateEstimate.NoBreathing -> "no breathing"
        is BreathingRateEstimate.Rate ->
            String.format(Locale.US, "%.1f br/min", estimate.brPerMin)
    }

    /**
     * Starts/stops the [NO_BREATHING_WARNING_MS] debounce clock. Warmup deliberately does not count
     * — a freshly (re)started stream has no verdict yet, which is not a problem to report.
     */
    private fun trackNoBreathing(estimate: BreathingRateEstimate, now: Long) {
        if (estimate is BreathingRateEstimate.NoBreathing) {
            if (noBreathingStartMs == 0L) noBreathingStartMs = now
        } else {
            noBreathingStartMs = 0L
            if (_warning.value == RespirationWarning.NO_BREATHING) {
                _warning.value = RespirationWarning.NONE
            }
        }
    }

    /**
     * One log line per verdict change, carrying the measured amplitude — the field data needed to
     * confirm [BreathingRateEstimator.MIN_AMPLITUDE_RA] suits this hardware.
     */
    private fun logEstimateChange(estimate: BreathingRateEstimate) {
        val previous = loggedEstimate
        val changed = when {
            previous == null -> estimate !is BreathingRateEstimate.Warmup
            previous is BreathingRateEstimate.Rate && estimate is BreathingRateEstimate.Rate -> false
            else -> previous::class != estimate::class
        }
        if (!changed) return

        loggedEstimate = estimate
        when (estimate) {
            is BreathingRateEstimate.Rate -> emitLog(
                String.format(
                    Locale.US,
                    "Breathing detected: %.1f br/min (amplitude %.1f RA)",
                    estimate.brPerMin, estimate.amplitudeRa
                )
            )
            BreathingRateEstimate.NoBreathing ->
                emitLog("WARN: No breathing detected in the RA waveform")
            BreathingRateEstimate.Warmup -> Unit
        }
    }

    /**
     * Current breathing-rate verdict over the live window. Exposed for tests; the UI reads
     * [breathingEstimate].
     */
    internal fun calculateBreathingRate(): BreathingRateEstimate {
        val window = synchronized(raBuffer) { raBuffer.toList() }
        return BreathingRateEstimator.estimate(window, SAMPLE_FREQ)
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

                    // Signal lost (strap off the chest) — the specific, actually-observed failure,
                    // so it outranks NO_BREATHING and is evaluated first.
                    if (lowSignalStartMs > 0L) {
                        val lowDuration = now - lowSignalStartMs
                        if (lowDuration >= SIGNAL_LOST_WARNING_MS &&
                            _warning.value != RespirationWarning.SIGNAL_LOST
                        ) {
                            _warning.value = RespirationWarning.SIGNAL_LOST
                            emitLog(
                                "WARN: Respiration signal lost (RA < $SIGNAL_LOST_THRESHOLD_RA for " +
                                    "${lowDuration / 1000}s). Check chest strap placement."
                            )
                        }
                    } else if (noBreathingStartMs > 0L) {
                        // RA is healthy but the waveform is not tracking breathing.
                        val flatDuration = now - noBreathingStartMs
                        if (flatDuration >= NO_BREATHING_WARNING_MS &&
                            _warning.value == RespirationWarning.NONE
                        ) {
                            _warning.value = RespirationWarning.NO_BREATHING
                            emitLog(
                                "WARN: No breathing detected for ${flatDuration / 1000}s. " +
                                    "Check chest strap placement."
                            )
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
