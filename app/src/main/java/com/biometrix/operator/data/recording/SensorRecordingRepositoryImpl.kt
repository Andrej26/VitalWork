package com.biometrix.operator.data.recording

import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.recording.model.RecordingMetadata
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.sensor.DeviceState
import com.biometrix.operator.data.sensor.SensorDevice
import com.biometrix.operator.data.sensor.ble.BleManager
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

class SensorRecordingRepositoryImpl(
    private val bleManager: BleManager,
    private val respirationDevice: SensorDevice,
    private val recordingRepository: RecordingRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : SensorRecordingRepository {

    private val _recordingState = MutableStateFlow(DataRecordingState.IDLE)
    override val recordingState: StateFlow<DataRecordingState> = _recordingState.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    override val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _recordingMetadata = MutableStateFlow<RecordingMetadata?>(null)
    override val recordingMetadata: StateFlow<RecordingMetadata?> = _recordingMetadata.asStateFlow()

    private var writeChannel = Channel<SensorSampleEntity>(Channel.BUFFERED)

    private var dbWriterJob: Job? = null
    private var durationJob: Job? = null
    private val collectorJobs = mutableListOf<Job>()

    private var startTimeMs: Long = 0L
    private var currentRecordingId: Long = 0L


    override suspend fun startRecording(testId: Long, testIdentifier: String) {
        if (_recordingState.value == DataRecordingState.RECORDING) return

        startTimeMs = System.currentTimeMillis()

        val isHeartRateConnected = bleManager.connectionState.value == ConnectionState.CONNECTED
        val respirationState = respirationDevice.state.value
        val isRespirationAvailable = respirationState == DeviceState.Streaming ||
                respirationState == DeviceState.Connected

        val shouldRecordHr = isHeartRateConnected
        val shouldRecordResp = isRespirationAvailable

        // Auto-start streaming if sensor is connected but not yet streaming
        if (respirationState == DeviceState.Connected) {
            respirationDevice.startStreaming()
        }

        // Create recording entity in database
        val recording = recordingRepository.createRecording(
            testId = testId,
            testIdentifier = testIdentifier,
            heartRateEnabled = shouldRecordHr,
            respirationEnabled = shouldRecordResp
        )
        currentRecordingId = recording.id

        _recordingMetadata.value = RecordingMetadata(
            recordingId = recording.id,
            recordingIdentifier = recording.recordingIdentifier,
            startTimestampMs = startTimeMs,
            heartRateRecording = shouldRecordHr,
            respirationRecording = shouldRecordResp
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
                    recordingRepository.addSamples(batch.toList())
                    batch.clear()
                    lastFlushTime = now
                }
            }

            // Flush remaining samples when channel closes
            if (batch.isNotEmpty()) {
                recordingRepository.addSamples(batch.toList())
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

        // Close channel and wait for writer to finish flushing
        writeChannel.close()
        dbWriterJob?.join()
        dbWriterJob = null

        // Finalize recording (updates status, duration, sample counts)
        recordingRepository.completeRecording(currentRecordingId)

        // Reset state
        resetState()
    }

    private fun collectSensor(
        flow: SharedFlow<Float>,
        sensorType: SensorType,
        updateMetadata: (RecordingMetadata) -> RecordingMetadata
    ): Job = scope.launch {
        flow.collect { value ->
            val now = System.currentTimeMillis()
            writeChannel.send(
                SensorSampleEntity(
                    recordingId = currentRecordingId,
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
        currentRecordingId = 0L

        // Re-create the channel for the next recording
        writeChannel = Channel(Channel.BUFFERED)
    }
}
