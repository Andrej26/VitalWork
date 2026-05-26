package com.biometrix.operator.presentation.screens.tests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.db.TestEntity
import com.biometrix.operator.data.db.TestStatus
import com.biometrix.operator.data.recording.SensorRecordingRepository
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.repository.TestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TestFilter {
    ALL,
    ACTIVE,
    COMPLETED,
    EXPORTED
}

data class TestsUiState(
    val tests: List<TestEntity> = emptyList(),
    val activeTest: TestEntity? = null,
    val selectedFilter: TestFilter = TestFilter.ALL,
    val isLoading: Boolean = true,
    val activeTestHeartRate: Int? = null,
    val activeTestDuration: String = "00:00:00",
    val isRecording: Boolean = false
)

@HiltViewModel
class TestsViewModel @Inject constructor(
    private val testRepository: TestRepository,
    private val connectionRepository: ConnectionRepository,
    private val sensorRecordingRepository: SensorRecordingRepository,
    private val recordingRepository: RecordingRepository
) : ViewModel() {

    private val _selectedFilter = MutableStateFlow(TestFilter.ALL)

    /** Emits current time every second so the active test duration stays live */
    private val tickerFlow = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }

    val uiState: StateFlow<TestsUiState> = combine(
        testRepository.allTests,
        testRepository.activeTest,
        _selectedFilter,
        connectionRepository.heartRate,
        sensorRecordingRepository.recordingState,
        tickerFlow
    ) { values ->
        val allTests = values[0] as List<*>
        val activeTest = values[1] as TestEntity?
        val filter = values[2] as TestFilter
        val heartRate = values[3] as Int?
        val recordingState = values[4] as DataRecordingState
        val currentTimeMs = values[5] as Long

        @Suppress("UNCHECKED_CAST")
        val tests = allTests as List<TestEntity>

        val filteredTests = when (filter) {
            TestFilter.ALL -> tests
            TestFilter.ACTIVE -> tests.filter { it.status == TestStatus.ACTIVE }
            TestFilter.COMPLETED -> tests.filter { it.status == TestStatus.COMPLETED }
            TestFilter.EXPORTED -> tests.filter { it.status == TestStatus.EXPORTED }
        }

        val activeTestDurationMs = activeTest?.let { currentTimeMs - it.createdAt } ?: 0L

        TestsUiState(
            tests = filteredTests,
            activeTest = activeTest,
            selectedFilter = filter,
            isLoading = false,
            activeTestHeartRate = heartRate,
            activeTestDuration = formatDuration(activeTestDurationMs),
            isRecording = recordingState == DataRecordingState.RECORDING
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TestsUiState())

    fun setFilter(filter: TestFilter) {
        _selectedFilter.value = filter
    }

    fun createNewTest(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val test = testRepository.createTest()
            onCreated(test.id)
        }
    }

    fun endAndStartNew(activeTestId: Long, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                // Stop recording if active
                if (sensorRecordingRepository.recordingState.value == DataRecordingState.RECORDING) {
                    sensorRecordingRepository.stopRecording()
                }

                val recordingCount = testRepository.getCompletedRecordingCount(activeTestId)
                testRepository.endTest(activeTestId, recordingCount)

                // Create and navigate to the new test
                val newTest = testRepository.createTest()
                onCreated(newTest.id)
            } catch (_: Exception) {
                // If ending fails, still try to create new test
                val newTest = testRepository.createTest()
                onCreated(newTest.id)
            }
        }
    }

    fun deleteTest(testId: Long) {
        viewModelScope.launch {
            testRepository.deleteTest(testId)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
