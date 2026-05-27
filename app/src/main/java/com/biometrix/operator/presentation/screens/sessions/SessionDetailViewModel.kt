package com.biometrix.operator.presentation.screens.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.db.RecordingEntity
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.export.SessionUploader
import com.biometrix.operator.data.repository.RecordingRepository
import com.biometrix.operator.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TestDetailUiState(
    val test: SessionEntity? = null,
    val recordings: List<RecordingEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false,
    val isExporting: Boolean = false,
    val exportResult: String? = null
)

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val recordingRepository: RecordingRepository,
    private val exportService: SessionUploader,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>("testId") ?: -1L

    private val _uiState = MutableStateFlow(TestDetailUiState())
    val uiState: StateFlow<TestDetailUiState> = _uiState.asStateFlow()

    init {
        loadTest()
    }

    private fun loadTest() {
        viewModelScope.launch {
            val testDeferred = async { sessionRepository.getSessionById(sessionId) }
            val recordingsDeferred = async { recordingRepository.getRecordingsForTestOnce(sessionId) }
            awaitAll(testDeferred, recordingsDeferred)

            _uiState.value = _uiState.value.copy(
                test = testDeferred.await(),
                recordings = recordingsDeferred.await(),
                isLoading = false
            )
        }
    }

    fun deleteSession(onDeleted: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            sessionRepository.deleteSession(sessionId)
            onDeleted()
        }
    }

    fun exportTest() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExporting = true)

            val result = exportService.upload(sessionId)

            result.fold(
                onSuccess = { path ->
                    sessionRepository.markExported(sessionId)
                    _uiState.value = _uiState.value.copy(
                        test = _uiState.value.test?.copy(status = SessionStatus.EXPORTED),
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
