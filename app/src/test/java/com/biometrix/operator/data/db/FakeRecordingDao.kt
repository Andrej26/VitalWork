package com.biometrix.operator.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeRecordingDao : RecordingDao {

    val recordings = mutableListOf<RecordingEntity>()
    private var nextId = 1L

    override fun getRecordingsForTest(testId: Long): Flow<List<RecordingEntity>> =
        flowOf(recordings.filter { it.testId == testId })

    override suspend fun getRecordingsForTestOnce(testId: Long): List<RecordingEntity> =
        recordings.filter { it.testId == testId }

    override suspend fun getRecordingById(id: Long): RecordingEntity? =
        recordings.find { it.id == id }

    override fun getActiveRecording(testId: Long): Flow<RecordingEntity?> =
        flowOf(recordings.firstOrNull { it.testId == testId && it.status == RecordingStatus.RECORDING })

    override suspend fun getMaxSequenceNumber(testId: Long): Int? =
        recordings.filter { it.testId == testId }.maxOfOrNull { it.sequenceNumber }

    override suspend fun getCompletedRecordingCount(testId: Long): Int =
        recordings.count { it.testId == testId && it.status == RecordingStatus.COMPLETED }

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
