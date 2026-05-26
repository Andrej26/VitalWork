package com.biometrix.operator.data.export

import com.biometrix.operator.data.db.RecordingEntity
import com.biometrix.operator.data.db.SensorSampleEntity
import com.biometrix.operator.data.db.SensorType
import com.biometrix.operator.data.db.TestEntity
import com.biometrix.operator.data.export.model.GapExport
import com.biometrix.operator.data.export.model.RecordingData
import com.biometrix.operator.data.export.model.RecordingGaps
import com.biometrix.operator.data.export.model.SensorData
import com.biometrix.operator.data.export.model.SensorGapInfo
import com.biometrix.operator.data.export.model.SensorInfo
import com.biometrix.operator.data.export.model.SensorSample
import com.biometrix.operator.data.export.model.SudsEventExport
import com.biometrix.operator.data.export.model.TestData
import com.biometrix.operator.data.export.model.TestExport
import com.biometrix.operator.data.export.model.TestStatistics
import com.biometrix.operator.data.recording.GapEvent
import com.biometrix.operator.data.recording.detectEsenseRrIntervalGaps
import com.biometrix.operator.data.recording.detectHeartRateGaps
import com.biometrix.operator.data.recording.detectRespirationGaps
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.repository.SudsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TestExportMapper @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val sudsRepository: SudsRepository
) {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    suspend fun buildExportData(
        test: TestEntity,
        recordings: List<RecordingEntity>
    ): TestExport {
        val recordingDataList = mutableListOf<RecordingData>()

        for (recording in recordings) {
            val samples = recordingRepository.getSamplesForRecording(recording.id)
            recordingDataList.add(buildRecordingData(recording, samples))
        }

        val sudsEvents = sudsRepository.getEventsForTest(test.id).map { event ->
            SudsEventExport(timestampMs = event.timestampMs, value = event.value)
        }

        return TestExport(
            exportedAt = isoFormat.format(Date()),
            test = TestData(
                id = test.testIdentifier,
                testNumber = test.testNumber,
                createdAt = isoFormat.format(Date(test.createdAt)),
                endedAt = test.endedAt?.let { isoFormat.format(Date(it)) },
                durationMs = test.durationMs,
                status = test.status.name,
                notes = test.notes,
                statistics = TestStatistics(
                    recordingCount = test.recordingCount,
                    totalHeartRateSamples = test.totalHeartRateSampleCount,
                    totalRespirationSamples = test.totalRespirationSampleCount,
                    totalSudsEvents = sudsEvents.size,
                    totalEsenseRrIntervalSamples = test.totalEsenseRrIntervalSampleCount
                ),
                recordings = recordingDataList,
                sudsEvents = sudsEvents
            )
        )
    }

    fun buildRecordingData(
        recording: RecordingEntity,
        samples: List<SensorSampleEntity>
    ): RecordingData {
        val sensorSamples = samples.map { sample ->
            SensorSample(
                timestampMs = sample.timestampMs,
                elapsedMs = sample.elapsedMs,
                value = sample.value,
                sensorType = when (sample.sensorType) {
                    SensorType.HEART_RATE -> "esensePulse_hr"
                    SensorType.ESENSE_RR_INTERVAL -> "esensePulse_rr"
                    SensorType.RESPIRATION -> "esenseResp_resp"
                }
            )
        }

        fun gapInfoOrNull(gaps: List<GapEvent>): SensorGapInfo? =
            if (gaps.isEmpty()) null
            else SensorGapInfo(gaps.size, gaps.sumOf { it.gapMs }, gaps.map { GapExport(it.startElapsedMs, it.endElapsedMs, it.gapMs) })

        val hrGaps       = if (recording.heartRateEnabled) detectHeartRateGaps(samples) else emptyList()
        val esenseRrGaps = if (recording.heartRateEnabled && recording.esenseRrIntervalSampleCount > 0) detectEsenseRrIntervalGaps(samples) else emptyList()
        val respGaps     = if (recording.respirationEnabled) detectRespirationGaps(samples) else emptyList()

        val allGaps = listOf(hrGaps, esenseRrGaps, respGaps)
        val recordingGaps = if (allGaps.all { it.isEmpty() }) null
        else RecordingGaps(
            esensePulseHeartRate = gapInfoOrNull(hrGaps),
            esensePulseRrInterval = gapInfoOrNull(esenseRrGaps),
            esenseRespRespiration = gapInfoOrNull(respGaps)
        )

        return RecordingData(
            id = recording.recordingIdentifier,
            sequence = recording.sequenceNumber,
            startedAt = isoFormat.format(Date(recording.startedAt)),
            endedAt = recording.endedAt?.let { isoFormat.format(Date(it)) },
            durationMs = recording.durationMs,
            sensors = SensorData(
                heartRate = if (recording.heartRateEnabled) {
                    SensorInfo(enabled = true, sampleCount = recording.heartRateSampleCount)
                } else null,
                esenseRrInterval = if (recording.heartRateEnabled && recording.esenseRrIntervalSampleCount > 0) {
                    SensorInfo(enabled = true, sampleCount = recording.esenseRrIntervalSampleCount)
                } else null,
                respiration = if (recording.respirationEnabled) {
                    SensorInfo(enabled = true, sampleCount = recording.respirationSampleCount)
                } else null
            ),
            recordingGaps = recordingGaps,
            data = sensorSamples
        )
    }
}
