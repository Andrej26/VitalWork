package com.biometrix.operator.data.export

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.biometrix.operator.data.db.RecordingEntity
import com.biometrix.operator.data.db.SensorType
import com.biometrix.operator.data.recording.detectEsenseRrIntervalGaps
import com.biometrix.operator.data.recording.detectHeartRateGaps
import com.biometrix.operator.data.recording.detectRespirationGaps
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.repository.TestRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface TestExporter {
    suspend fun exportTest(testId: Long): Result<String>
}

@Singleton
class TestExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val testRepository: TestRepository,
    private val recordingRepository: RecordingRepository,
    private val mapper: TestExportMapper
) : TestExporter {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    override suspend fun exportTest(testId: Long): Result<String> = withContext(Dispatchers.IO) {
        try {
            val test = testRepository.getTestById(testId)
                ?: return@withContext Result.failure(IllegalArgumentException("Test not found"))

            val recordings = recordingRepository.getRecordingsForTestOnce(testId)

            val exportData = mapper.buildExportData(test, recordings)
            val jsonContent = json.encodeToString(exportData)

            // Write to Documents folder
            val fileName = "${test.testIdentifier}_export.json"
            val folderName = test.testIdentifier

            val outputPath = writeToDocuments(folderName, fileName, jsonContent.toByteArray())

            // Also export individual CSV files for each recording
            for (recording in recordings) {
                exportRecordingCsv(recording, folderName)
            }

            Result.success(outputPath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun exportRecordingCsv(
        recording: RecordingEntity,
        folderName: String
    ) {
        val samples = recordingRepository.getSamplesForRecording(recording.id)
        if (samples.isEmpty()) return

        val hrGaps       = if (recording.heartRateEnabled) detectHeartRateGaps(samples) else emptyList()
        val esenseRrGaps = if (recording.heartRateEnabled && recording.esenseRrIntervalSampleCount > 0) detectEsenseRrIntervalGaps(samples) else emptyList()
        val respGaps     = if (recording.respirationEnabled) detectRespirationGaps(samples) else emptyList()

        val csvContent = buildString {
            // Metadata header
            appendLine("# recording_id,${recording.recordingIdentifier}")
            appendLine("# test_id,$folderName")
            appendLine("# sequence,${recording.sequenceNumber}")
            appendLine("# start_time,${isoFormat.format(Date(recording.startedAt))}")
            recording.endedAt?.let {
                appendLine("# end_time,${isoFormat.format(Date(it))}")
            }
            appendLine("# duration_ms,${recording.durationMs}")
            appendLine("# esense_pulse_hr_samples,${recording.heartRateSampleCount}")
            if (recording.esenseRrIntervalSampleCount > 0) {
                appendLine("# esense_pulse_rr_samples,${recording.esenseRrIntervalSampleCount}")
            }
            appendLine("# esense_resp_samples,${recording.respirationSampleCount}")
            if (hrGaps.isNotEmpty()) {
                appendLine("# esense_pulse_hr_gaps,${hrGaps.size}")
                appendLine("# esense_pulse_hr_gap_total_ms,${hrGaps.sumOf { it.gapMs }}")
            }
            if (esenseRrGaps.isNotEmpty()) {
                appendLine("# esense_pulse_rr_gaps,${esenseRrGaps.size}")
                appendLine("# esense_pulse_rr_gap_total_ms,${esenseRrGaps.sumOf { it.gapMs }}")
            }
            if (respGaps.isNotEmpty()) {
                appendLine("# esense_resp_gaps,${respGaps.size}")
                appendLine("# esense_resp_gap_total_ms,${respGaps.sumOf { it.gapMs }}")
            }

            // Header row
            appendLine("timestamp_ms,elapsed_ms,value,sensor_type")

            // Data rows
            samples.forEach { sample ->
                val sensorType = when (sample.sensorType) {
                    SensorType.HEART_RATE -> "esense_pulse_hr"
                    SensorType.ESENSE_RR_INTERVAL -> "esense_pulse_rr_interval"
                    SensorType.RESPIRATION -> "esense_resp"
                }
                appendLine("${sample.timestampMs},${sample.elapsedMs},${sample.value},$sensorType")
            }
        }

        val fileName = "${recording.recordingIdentifier}.csv"
        writeToDocuments(folderName, fileName, csvContent.toByteArray())
    }

    private fun writeToDocuments(folderName: String, fileName: String, content: ByteArray): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToDocumentsMediaStore(folderName, fileName, content)
        } else {
            writeToDocumentsLegacy(folderName, fileName, content)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDocumentsMediaStore(
        folderName: String,
        fileName: String,
        content: ByteArray
    ): String {
        val mimeType = if (fileName.endsWith(".json")) "application/json" else "text/csv"
        val relativePath = "Documents/BioMetrix/$folderName"

        // Check if a file with the same name already exists to avoid creating duplicates
        val existingUri = findExistingMediaStoreFile(fileName, relativePath)

        val uri = if (existingUri != null) {
            existingUri
        } else {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            context.contentResolver.insert(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), contentValues
            ) ?: throw Exception("Failed to create $fileName in Documents")
        }

        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(content)
        } ?: throw Exception("Failed to write $fileName")

        return "$relativePath/$fileName"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findExistingMediaStoreFile(fileName: String, relativePath: String): Uri? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(fileName, "$relativePath/")

        context.contentResolver.query(
            collection, projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                )
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    private fun writeToDocumentsLegacy(
        folderName: String,
        fileName: String,
        content: ByteArray
    ): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "BioMetrix/$folderName"
        )
        dir.mkdirs()

        val file = File(dir, fileName)
        file.writeBytes(content)

        return file.absolutePath
    }
}
