package com.biometrix.operator.data.recording

import com.biometrix.operator.data.db.ScenarioEntity
import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.recording.model.ScenarioMetadata
import com.biometrix.operator.data.repository.ScenarioRepository
import com.biometrix.operator.data.sensor.DeviceState
import com.biometrix.operator.data.sensor.SensorDevice
import com.biometrix.operator.data.sensor.ble.BleManager
import com.biometrix.operator.data.sensor.watch.WatchSensorReceiver
import com.biometrix.operator.data.time.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ScenarioRecordingRepositoryImpl(
    private val bleManager: BleManager,
    private val respirationDevice: SensorDevice,
    private val scenarioRepository: ScenarioRepository,
    private val watchReceiver: WatchSensorReceiver,
    private val timeProvider: TimeProvider = TimeProvider.system(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : ScenarioRecordingRepository {

    private val _recordingState = MutableStateFlow(DataRecordingState.IDLE)
    override val recordingState: StateFlow<DataRecordingState> = _recordingState.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    override val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _recordingMetadata = MutableStateFlow<ScenarioMetadata?>(null)
    override val recordingMetadata: StateFlow<ScenarioMetadata?> = _recordingMetadata.asStateFlow()

    private var writeChannel = Channel<SensorSampleEntity>(Channel.BUFFERED)

    private var dbWriterJob: Job? = null
    private var durationJob: Job? = null
    private val collectorJobs = mutableListOf<Job>()

    private var startTimeMs: Long = 0L
    private var currentScenarioId: Long = 0L

    // --- Galaxy Watch EDA (session-scoped, independent of per-scenario recording) ---

    /** Continuous session-long EDA collector job; null when no watch session is active. */
    private var watchEdaJob: Job? = null

    /**
     * Every EDA reading delivered during the session, corrected onto the phone clock, as
     * (correctedTimestampMs, value). Retained so the End-Session drain can back-fill late/out-of-
     * window deliveries. Cleared when the session is finalized. Concurrent: written from the watch
     * collector coroutine, read on the End-Session path.
     */
    private val watchEdaSessionBuffer = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Float>>())

    /** Per-scenario max corrected EDA timestamp written live (de-dup boundary for the drain). */
    private val watchEdaHighWaterMarks = ConcurrentHashMap<Long, Long>()

    /**
     * Snapshot of the actively-recording scenario for the session-long watch collector to read,
     * race-free, without touching the per-scenario [writeChannel] (which closes between scenarios).
     * `(scenarioId, startTimeMs)` while a scenario records; null otherwise.
     */
    @Volatile
    private var activeWatchTarget: Pair<Long, Long>? = null

    override suspend fun startRecording(scenarioId: Long, scenarioIdentifier: String) {
        if (_recordingState.value == DataRecordingState.RECORDING) return

        startTimeMs = timeProvider.nowMs()
        currentScenarioId = scenarioId

        val isHeartRateConnected = bleManager.connectionState.value == ConnectionState.CONNECTED
        val respirationState = respirationDevice.state.value
        val isRespirationAvailable = respirationState == DeviceState.Streaming ||
                respirationState == DeviceState.Connected

        val shouldRecordHr = isHeartRateConnected
        val shouldRecordResp = isRespirationAvailable
        // Watch EDA is captured by the session-long collector; this flag only reflects whether the
        // watch was connected at scenario start (for the metadata/UI). Live writes are gated on the
        // watch connection inside that collector.
        val shouldRecordEda = watchReceiver.connectionState.value == ConnectionState.CONNECTED

        // Auto-start streaming if sensor is connected but not yet streaming
        if (respirationState == DeviceState.Connected) {
            respirationDevice.startStreaming()
        }

        _recordingMetadata.value = ScenarioMetadata(
            scenarioId = scenarioId,
            scenarioIdentifier = scenarioIdentifier,
            startTimestampMs = startTimeMs,
            heartRateRecording = shouldRecordHr,
            respirationRecording = shouldRecordResp,
            edaRecording = shouldRecordEda
        )

        // Ensure heart rate notifications are enabled before recording
        if (shouldRecordHr) {
            bleManager.enableHeartRateNotifications()
        }

        // Start database writer coroutine
        dbWriterJob = scope.launch {
            val batch = mutableListOf<SensorSampleEntity>()
            val batchSize = 50
            var lastFlushTime = System.currentTimeMillis()

            for (sample in writeChannel) {
                batch.add(sample)

                // Flush batch when it reaches size limit or after 1 second
                val now = System.currentTimeMillis()
                if (batch.size >= batchSize || now - lastFlushTime > 1000) {
                    scenarioRepository.addSamples(batch.toList())
                    batch.clear()
                    lastFlushTime = now
                }
            }

            // Flush remaining samples when channel closes
            if (batch.isNotEmpty()) {
                scenarioRepository.addSamples(batch.toList())
            }
        }

        // Start duration tracker
        durationJob = scope.launch {
            while (isActive) {
                delay(1000)
                _recordingDurationMs.value = System.currentTimeMillis() - startTimeMs
            }
        }

        // Start sensor collectors
        if (shouldRecordHr) {
            collectorJobs += collectSensor(
                bleManager.heartRateSampleFlow, SensorType.HEART_RATE
            ) { it.copy(heartRateSampleCount = it.heartRateSampleCount + 1) }

            collectorJobs += collectSensor(
                bleManager.rrIntervalSampleFlow, SensorType.ESENSE_RR_INTERVAL
            ) { it.copy(esenseRrIntervalSampleCount = it.esenseRrIntervalSampleCount + 1) }
        }

        if (shouldRecordResp) {
            collectorJobs += collectSensor(
                respirationDevice.sampleFlow, SensorType.RESPIRATION
            ) { it.copy(respirationSampleCount = it.respirationSampleCount + 1) }
        }

        // Expose this scenario as the live-write target for the session-long watch EDA collector.
        activeWatchTarget = scenarioId to startTimeMs

        _recordingState.value = DataRecordingState.RECORDING
    }

    override suspend fun stopRecording() {
        if (_recordingState.value != DataRecordingState.RECORDING) return

        // Cancel collectors
        collectorJobs.forEach { it.cancel() }
        collectorJobs.clear()

        // Cancel duration tracker
        durationJob?.cancel()
        durationJob = null

        // Stop live watch-EDA writes for this scenario (drain back-fills any late deliveries).
        activeWatchTarget = null

        // Close channel and wait for writer to finish flushing
        writeChannel.close()
        dbWriterJob?.join()
        dbWriterJob = null

        // Mark the scenario as ended
        scenarioRepository.endScenario(currentScenarioId)

        // Reset state
        resetState()
    }

    private fun collectSensor(
        flow: SharedFlow<Float>,
        sensorType: SensorType,
        updateMetadata: (ScenarioMetadata) -> ScenarioMetadata
    ): Job = scope.launch {
        flow.collect { value ->
            val now = timeProvider.nowMs()
            writeChannel.send(
                SensorSampleEntity(
                    scenarioId = currentScenarioId,
                    timestampMs = now,
                    elapsedMs = now - startTimeMs,
                    sensorType = sensorType,
                    value = value
                )
            )
            _recordingMetadata.value = _recordingMetadata.value?.let(updateMetadata)
        }
    }

    private fun resetState() {
        _recordingState.value = DataRecordingState.IDLE
        _recordingDurationMs.value = 0L
        _recordingMetadata.value = null
        currentScenarioId = 0L

        // Re-create the channel for the next recording
        writeChannel = Channel(Channel.BUFFERED)
    }

    // --- Galaxy Watch EDA session capture ---

    override fun startWatchEdaSession() {
        if (watchEdaJob?.isActive == true) return
        watchEdaSessionBuffer.clear()
        watchEdaHighWaterMarks.clear()
        watchEdaJob = scope.launch {
            watchReceiver.edaSampleFlow.collect { reading ->
                // Correct the watch-stamped time onto the phone clock once, here.
                val tc = watchReceiver.correctedTimestamp(reading.timestampMs)
                // Always retain for the End-Session drain (covers late/out-of-window deliveries).
                watchEdaSessionBuffer.add(tc to reading.value)

                // If a scenario is actively recording, write this sample live too.
                val target = activeWatchTarget ?: return@collect
                val (scenarioId, scenarioStart) = target
                // Skip samples whose corrected time predates the active scenario (rare clock edge).
                if (tc < scenarioStart) return@collect
                scenarioRepository.addSamples(
                    listOf(
                        SensorSampleEntity(
                            scenarioId = scenarioId,
                            timestampMs = tc,
                            elapsedMs = tc - scenarioStart,
                            sensorType = SensorType.EDA,
                            value = reading.value
                        )
                    )
                )
                // Advance the per-scenario high-water mark (de-dup boundary for the drain).
                watchEdaHighWaterMarks.merge(scenarioId, tc, ::maxOf)
                _recordingMetadata.value = _recordingMetadata.value?.let {
                    if (it.scenarioId == scenarioId) it.copy(edaSampleCount = it.edaSampleCount + 1) else it
                }
            }
        }
    }

    override suspend fun drainAndFinalizeWatchEda(scenarios: List<ScenarioEntity>) {
        try {
            val readings = synchronized(watchEdaSessionBuffer) { watchEdaSessionBuffer.toList() }
            val windows = scenarios.map {
                WatchSessionDrainer.ScenarioWindow(it.id, it.startedAt, it.endedAt)
            }
            val rows = WatchSessionDrainer.drain(
                readings = readings,
                windows = windows,
                highWaterMarks = watchEdaHighWaterMarks.toMap()
            )
            if (rows.isNotEmpty()) {
                scenarioRepository.addSamples(rows)
            }
        } finally {
            // Stop the session collector and clear all session-scoped watch state.
            watchEdaJob?.cancel(); watchEdaJob = null
            watchEdaSessionBuffer.clear()
            watchEdaHighWaterMarks.clear()
            activeWatchTarget = null
        }
    }
}
