package com.biometrix.operator.data.export

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.biometrix.operator.data.db.ScenarioEntity
import com.biometrix.operator.data.db.SensorType
import com.biometrix.operator.data.recording.detectEsenseRrIntervalGaps
import com.biometrix.operator.data.recording.detectHeartRateGaps
import com.biometrix.operator.data.recording.detectRespirationGaps
import com.biometrix.operator.data.repository.ParticipantRepository
import com.biometrix.operator.data.repository.ScenarioRepository
import com.biometrix.operator.data.repository.SessionRepository
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

interface SessionExporter {
    suspend fun exportSession(sessionId: Long): Result<String>
}

@Singleton
class SessionExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val participantRepository: ParticipantRepository,
    private val scenarioRepository: ScenarioRepository,
    private val mapper: SessionExportMapper
) : SessionExporter, SessionUploader {

    override suspend fun upload(sessionId: Long): Result<String> = exportSession(sessionId)

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    override suspend fun exportSession(sessionId: Long): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val session = sessionRepository.getSessionById(sessionId)
                    ?: return@withContext Result.failure(
                        IllegalArgumentException("Session not found")
                    )

                val participant = participantRepository.getParticipantById(session.participantId)
                    ?: return@withContext Result.failure(
                        IllegalArgumentException("Participant not found for session")
                    )

                val scenarios = scenarioRepository.getScenariosForSessionOnce(sessionId)

                val exportData = mapper.buildExportData(participant, session, scenarios)
                val jsonContent = json.encodeToString(exportData)

                val folderName = session.sessionCode
                val jsonFileName = "${session.sessionCode}_export.json"

                val outputPath = writeToDocuments(folderName, jsonFileName, jsonContent.toByteArray())

                for (scenario in scenarios) {
                    exportScenarioCsv(session.sessionCode, scenario, folderName)
                }

                Result.success(outputPath)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun exportScenarioCsv(
        sessionCode: String,
        scenario: ScenarioEntity,
        folderName: String
    ) {
        val samples = scenarioRepository.getSamplesForScenario(scenario.id)
        if (samples.isEmpty()) return

        val hrGaps = detectHeartRateGaps(samples)
        val rrGaps = detectEsenseRrIntervalGaps(samples)
        val respGaps = detectRespirationGaps(samples)

        val hrCount = samples.count { it.sensorType == SensorType.HEART_RATE }
        val rrCount = samples.count { it.sensorType == SensorType.ESENSE_RR_INTERVAL }
        val respCount = samples.count { it.sensorType == SensorType.RESPIRATION }
        val edaCount = samples.count { it.sensorType == SensorType.EDA }

        val csvContent = buildString {
            appendLine("# session_code,$sessionCode")
            appendLine("# scenario_code,${scenario.scenarioCode.name}")
            appendLine("# scenario_category,${scenario.scenarioCategory.name}")
            appendLine("# start_time,${isoFormat.format(Date(scenario.startedAt))}")
            scenario.endedAt?.let {
                appendLine("# end_time,${isoFormat.format(Date(it))}")
            }
            scenario.eventTimestampMs?.let {
                appendLine("# event_timestamp_ms,$it")
            }
            scenario.reactionTimestampMs?.let {
                appendLine("# reaction_timestamp_ms,$it")
            }
            if (scenario.eventTimestampMs != null && scenario.reactionTimestampMs != null) {
                appendLine("# reaction_time_ms,${scenario.reactionTimestampMs - scenario.eventTimestampMs}")
            }
            appendLine("# hr_samples,$hrCount")
            if (rrCount > 0) appendLine("# rr_interval_samples,$rrCount")
            appendLine("# respiration_samples,$respCount")
            if (edaCount > 0) appendLine("# eda_samples,$edaCount")
            if (hrGaps.isNotEmpty()) {
                appendLine("# hr_gaps,${hrGaps.size}")
                appendLine("# hr_gap_total_ms,${hrGaps.sumOf { it.gapMs }}")
            }
            if (rrGaps.isNotEmpty()) {
                appendLine("# rr_interval_gaps,${rrGaps.size}")
                appendLine("# rr_interval_gap_total_ms,${rrGaps.sumOf { it.gapMs }}")
            }
            if (respGaps.isNotEmpty()) {
                appendLine("# respiration_gaps,${respGaps.size}")
                appendLine("# respiration_gap_total_ms,${respGaps.sumOf { it.gapMs }}")
            }

            appendLine("timestamp_ms,elapsed_ms,sensor_type,value")

            samples.forEach { sample ->
                val sensorType = when (sample.sensorType) {
                    SensorType.HEART_RATE -> "heart_rate"
                    SensorType.ESENSE_RR_INTERVAL -> "rr_interval"
                    SensorType.RESPIRATION -> "respiration"
                    SensorType.EDA -> "eda"
                    SensorType.WATCH_IBI -> "watch_ibi"
                }
                appendLine("${sample.timestampMs},${sample.elapsedMs},$sensorType,${sample.value}")
            }
        }

        val fileName = "${sessionCode}_${scenario.scenarioCode.name}.csv"
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
