package com.biometrix.operator.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeRecordingDao : RecordingDao {

    val recordings = mutableListOf<RecordingEntity>()
    private var nextId = 1L

    override fun getRecordingsForTest(sessionId: Long): Flow<List<RecordingEntity>> =
        flowOf(recordings.filter { it.sessionId == sessionId })

    override suspend fun getRecordingsForTestOnce(sessionId: Long): List<RecordingEntity> =
        recordings.filter { it.sessionId == sessionId }

    override suspend fun getRecordingById(id: Long): RecordingEntity? =
        recordings.find { it.id == id }

    override fun getActiveRecording(sessionId: Long): Flow<RecordingEntity?> =
        flowOf(recordings.firstOrNull { it.sessionId == sessionId && it.status == RecordingStatus.RECORDING })

    override suspend fun getMaxSequenceNumber(sessionId: Long): Int? =
        recordings.filter { it.sessionId == sessionId }.maxOfOrNull { it.sequenceNumber }

    override suspend fun getCompletedRecordingCount(sessionId: Long): Int =
        recordings.count { it.sessionId == sessionId && it.status == RecordingStatus.COMPLETED }

    override suspend fun insert(recording: RecordingEntity): Long {
        val id = nextId++
        recordings.add(recording.copy(id = id))
        return id
    }

    override suspend fun update(recording: RecordingEntity) {
        val index = recordings.indexOfFirst { it.id == recording.id }
        if (index >= 0) recordings[index] = recording
    }

    override suspend fun delete(recording: RecordingEntity) {
        recordings.removeAll { it.id == recording.id }
    }
}
