package com.biometrix.operator.presentation.screens.tests

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.db.RecordingEntity
import com.biometrix.operator.data.db.TestEntity
import com.biometrix.operator.data.db.TestStatus
import com.biometrix.operator.data.export.TestExporter
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.repository.TestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TestDetailUiState(
    val test: TestEntity? = null,
    val recordings: List<RecordingEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false,
    val isExporting: Boolean = false,
    val exportResult: String? = null
)

@HiltViewModel
class TestDetailViewModel @Inject constructor(
    private val testRepository: TestRepository,
    private val recordingRepository: RecordingRepository,
    private val exportService: TestExporter,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val testId: Long = savedStateHandle.get<Long>("testId") ?: -1L

    private val _uiState = MutableStateFlow(TestDetailUiState())
    val uiState: StateFlow<TestDetailUiState> = _uiState.asStateFlow()

    init {
        loadTest()
    }

    private fun loadTest() {
        viewModelScope.launch {
            val testDeferred = async { testRepository.getTestById(testId) }
            val recordingsDeferred = async { recordingRepository.getRecordingsForTestOnce(testId) }
            awaitAll(testDeferred, recordingsDeferred)

            _uiState.value = _uiState.value.copy(
                test = testDeferred.await(),
                recordings = recordingsDeferred.await(),
                isLoading = false
            )
        }
    }

    fun deleteTest(onDeleted: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            testRepository.deleteTest(testId)
            onDeleted()
        }
    }

    fun exportTest() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExporting = true)

            val result = exportService.exportTest(testId)

            result.fold(
                onSuccess = { path ->
                    testRepository.markExported(testId)
                    _uiState.value = _uiState.value.copy(
                        test = _uiState.value.test?.copy(status = TestStatus.EXPORTED),
                        isExporting = false,
                        exportResult = "Exported to: $path"
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        exportResult = "Export failed: ${error.message}"
                    )
                }
            )
        }
    }

    fun clearExportResult() {
        _uiState.value = _uiState.value.copy(exportResult = null)
    }
}
