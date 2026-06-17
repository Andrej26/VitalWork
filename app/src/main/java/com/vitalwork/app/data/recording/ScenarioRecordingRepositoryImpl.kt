package com.vitalwork.app.data.recording

import com.vitalwork.app.data.db.ScenarioEntity
import com.vitalwork.app.data.db.SensorSampleEntity
import com.vitalwork.app.data.db.SensorType
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.recording.model.DataRecordingState
import com.vitalwork.app.data.recording.model.ScenarioMetadata
import com.vitalwork.app.data.repository.ScenarioRepository
import com.vitalwork.app.data.sensor.DeviceState
import com.vitalwork.app.data.sensor.SensorDevice
import com.vitalwork.app.data.sensor.ble.BleManager
import com.vitalwork.app.data.sensor.watch.WatchFlushState
import com.vitalwork.app.data.sensor.watch.WatchSensorReceiver
import com.vitalwork.app.data.time.TimeProvider
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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

    // --- Galaxy Watch (EDA + HR + IBI; session-scoped, independent of per-scenario recording) ---

    /** Continuous session-long watch collector job; null when no watch session is active. */
    private var watchEdaJob: Job? = null

    /**
     * Every watch reading delivered during the session (EDA + HR + IBI), corrected onto the phone
     * clock and tagged with its [SensorType]. Retained so the End-Session drain can back-fill late/
     * out-of-window deliveries (incl. the historical store flush). Cleared when the session is
     * finalized. Concurrent: written from the watch collector coroutine and the flush-ingest path,
     * read on the End-Session path.
     */
    private val watchSessionBuffer =
        java.util.Collections.synchronizedList(mutableListOf<WatchSessionDrainer.Reading>())

    /** Per-(scenario, type) max corrected timestamp written live (de-dup boundary for the drain). */
    private val watchHighWaterMarks = ConcurrentHashMap<WatchSessionDrainer.HwmKey, Long>()

    /**
     * Snapshot of the actively-recording scenario for the session-long watch collector to read,
     * race-free, without touching the per-scenario [writeChannel] (which closes between scenarios).
     * `(scenarioId, startTimeMs)` while a scenario records; null otherwise.
     */
    @Volatile
    private var activeWatchTarget: Pair<Long, Long>? = null

    override suspend fun startRecording(scenarioId: Long, scenarioIdentifier: String) {
        if (_recordingState.value == DataRecordingState.RECORDING) return

        Log.i(TAG, "startRecording scenarioId=$scenarioId ($scenarioIdentifier)")
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
                bleManager.heartRateSampleFlow, SensorType.ESENSE_HEART_RATE
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

        Log.i(TAG, "stopRecording scenarioId=$currentScenarioId (clean close)")
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

    // --- Galaxy Watch session capture (EDA + HR + IBI) ---

    /** Map a watch reading-type string to its DB [SensorType]; null = not a recordable sensor. */
    private fun watchSensorType(type: String): SensorType? = when (type) {
        "WATCH_EDA" -> SensorType.WATCH_EDA
        "WATCH_HR" -> SensorType.WATCH_HR
        "WATCH_IBI" -> SensorType.WATCH_IBI
        else -> null
    }

    override fun startWatchEdaSession() {
        if (watchEdaJob?.isActive == true) return
        watchSessionBuffer.clear()
        watchHighWaterMarks.clear()
        watchEdaJob = scope.launch {
            watchReceiver.watchSampleFlow.collect { reading ->
                val sensorType = watchSensorType(reading.type) ?: return@collect
                // Correct the watch-stamped time onto the phone clock once, here.
                val tc = watchReceiver.correctedTimestamp(reading.timestampMs)
                // Always retain for the End-Session drain (covers late/out-of-window + flushed deliveries).
                watchSessionBuffer.add(WatchSessionDrainer.Reading(tc, sensorType, reading.value))

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
                            sensorType = sensorType,
                            value = reading.value
                        )
                    )
                )
                // Advance the per-(scenario, type) high-water mark (de-dup boundary for the drain).
                watchHighWaterMarks.merge(WatchSessionDrainer.HwmKey(scenarioId, sensorType), tc, ::maxOf)
                // Per-type live sample counts drive the session UI badges (EDA card + HR card + IBI footer).
                _recordingMetadata.value = _recordingMetadata.value?.let {
                    if (it.scenarioId != scenarioId) return@let it
                    when (sensorType) {
                        SensorType.WATCH_EDA -> it.copy(edaSampleCount = it.edaSampleCount + 1)
                        SensorType.WATCH_HR -> it.copy(watchHrSampleCount = it.watchHrSampleCount + 1)
                        SensorType.WATCH_IBI -> it.copy(watchIbiSampleCount = it.watchIbiSampleCount + 1)
                        else -> it
                    }
                }
            }
        }
    }

    override suspend fun drainAndFinalizeWatchEda(scenarios: List<ScenarioEntity>): WatchReconciliationReport? {
        // Stop the live collector FIRST (and wait for it) so no late live write can race the
        // authoritative rebuild below. activeWatchTarget is already null post-recording, but joining
        // makes the ordering explicit and the delete-then-insert safe.
        watchEdaJob?.let { it.cancelAndJoin() }; watchEdaJob = null
        try {
            // The historical store flush — buffered losslessly in the receiver — is the COMPLETE record
            // of everything the watch sampled this session (every reading is appended to the watch store
            // before it streams). Correct each onto the phone clock for window attribution.
            val flushed = watchReceiver.takeFlushedReadings()
            val flushedReadings = flushed.mapNotNull { reading ->
                val sensorType = watchSensorType(reading.type) ?: return@mapNotNull null
                WatchSessionDrainer.Reading(
                    watchReceiver.correctedTimestamp(reading.timestampMs), sensorType, reading.value
                )
            }
            val windows = scenarios.map {
                WatchSessionDrainer.ScenarioWindow(it.id, it.startedAt, it.endedAt)
            }
            val scenarioIds = scenarios.map { it.id }
            val complete = watchReceiver.flushState.value as? WatchFlushState.Complete

            // AUTHORITATIVE PATH — a complete, fully-transferred flush is ground truth. Rebuild the
            // session's watch rows from it: NO high-water-mark de-dup (so a wake-burst dropped by the
            // bounded live flow can't be skipped as "already live"), delete-then-insert in one
            // transaction (so the provisional live rows are replaced, never duplicated). Provably
            // lossless because the store ⊇ all live writes.
            if (complete != null && complete.rowsReceived == flushed.size && scenarioIds.isNotEmpty()) {
                val rows = WatchSessionDrainer.drain(
                    readings = flushedReadings,
                    windows = windows,
                    highWaterMarks = emptyMap()
                )
                scenarioRepository.replaceWatchSamples(scenarioIds, rows)
                val dbRows = scenarioRepository.countWatchSamplesForScenarios(scenarioIds)
                return WatchReconciliationReport(
                    claimed = complete.rowsReceived,
                    received = flushed.size,
                    inScenario = rows.size,           // in-window flush rows (no skips on this path)
                    dbRows = dbRows,
                    clockSkewMs = watchReceiver.observedClockSkewMs()
                )
            }

            // FALLBACK PATH — no / partial / aborted flush: keep the provisional live writes and
            // best-effort drain whatever arrived, de-duped against the live high-water marks. Not
            // verified (the store could not be confirmed complete), so no reconciliation report.
            flushedReadings.forEach { watchSessionBuffer.add(it) }
            val readings = synchronized(watchSessionBuffer) { watchSessionBuffer.toList() }
            val rows = WatchSessionDrainer.drain(
                readings = readings,
                windows = windows,
                highWaterMarks = watchHighWaterMarks.toMap()
            )
            if (rows.isNotEmpty()) scenarioRepository.addSamples(rows)
            return null
        } finally {
            // Clear all session-scoped watch state.
            watchSessionBuffer.clear()
            watchHighWaterMarks.clear()
            activeWatchTarget = null
        }
    }

    private companion object {
        private const val TAG = "VitalWorkLifecycle"
    }
}
