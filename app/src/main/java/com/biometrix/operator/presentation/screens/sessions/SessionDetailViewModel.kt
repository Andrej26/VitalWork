package com.biometrix.operator.presentation.screens.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.db.ScenarioEntity
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.export.SessionUploader
import com.biometrix.operator.data.repository.ScenarioRepository
import com.biometrix.operator.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionDetailUiState(
    val session: SessionEntity? = null,
    val scenarios: List<ScenarioEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false,
    val isExporting: Boolean = false,
    val exportResult: String? = null
)

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val scenarioRepository: ScenarioRepository,
    private val exportService: SessionUploader,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: -1L

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    init {
        loadSession()
    }

    private fun loadSession() {
        viewModelScope.launch {
            val sessionDeferred = async { sessionRepository.getSessionById(sessionId) }
            val scenariosDeferred = async {
                scenarioRepository.getScenariosForSessionOnce(sessionId)
            }
            awaitAll(sessionDeferred, scenariosDeferred)

            _uiState.value = _uiState.value.copy(
                session = sessionDeferred.await(),
                scenarios = scenariosDeferred.await(),
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

    fun exportSession() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExporting = true)

            val result = exportService.upload(sessionId)

            result.fold(
                onSuccess = { path ->
                    sessionRepository.markUploaded(sessionId)
                    _uiState.value = _uiState.value.copy(
                        session = _uiState.value.session?.copy(status = SessionStatus.UPLOADED),
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
