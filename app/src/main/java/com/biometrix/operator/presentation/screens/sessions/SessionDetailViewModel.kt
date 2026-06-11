package com.biometrix.operator.presentation.screens.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.db.ScenarioEntity
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.export.SessionExporter
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

/**
 * Upload-to-server progress, surfaced to the Review screen as a dialog.
 * Distinct from the local-export state ([SessionDetailUiState.isExporting]).
 */
sealed interface UploadState {
    data object Idle : UploadState
    data object Uploading : UploadState
    data object Success : UploadState
    data class Failed(val message: String) : UploadState
}

data class SessionDetailUiState(
    val session: SessionEntity? = null,
    val scenarios: List<ScenarioEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false,
    val isExporting: Boolean = false,
    val exportResult: String? = null,
    val uploadState: UploadState = UploadState.Idle
)

private const val AUTO_UPLOAD_ENABLED = false

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val scenarioRepository: ScenarioRepository,
    private val exporter: SessionExporter,
    private val uploader: SessionUploader,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: -1L

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    /** Guards the auto-upload so it fires at most once per screen load. */
    private var autoUploadAttempted = false

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

            val session = sessionDeferred.await()
            _uiState.value = _uiState.value.copy(
                session = session,
                scenarios = scenariosDeferred.await(),
                isLoading = false
            )

            maybeAutoUpload(session)
        }
    }

    /**
     * Auto-fire the server upload once when a freshly-completed (not-yet-UPLOADED) session with data is
     * opened — this is the post-session upload path. Sessions already UPLOADED, empty, or in-flight are
     * skipped; the operator can still trigger a manual upload via [uploadSession].
     *
     * Disabled while [AUTO_UPLOAD_ENABLED] is false (server-integration testing); flip it back to true
     * once uploads are verified end-to-end. The manual "Upload to server" button always works regardless.
     */
    private fun maybeAutoUpload(session: SessionEntity?) {
        if (!AUTO_UPLOAD_ENABLED) return
        if (autoUploadAttempted) return
        if (session == null || session.status != SessionStatus.COMPLETED) return
        if (_uiState.value.scenarios.isEmpty()) return
        autoUploadAttempted = true
        uploadSession()
    }

    /** Upload the session to the BioMetrix server. Sets status UPLOADED only on success (HTTP 201). */
    fun uploadSession() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(uploadState = UploadState.Uploading)

            uploader.upload(sessionId).fold(
                onSuccess = {
                    sessionRepository.markUploaded(sessionId)
                    _uiState.value = _uiState.value.copy(
                        session = _uiState.value.session?.copy(status = SessionStatus.UPLOADED),
                        uploadState = UploadState.Success
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        uploadState = UploadState.Failed(error.message ?: "Upload failed")
                    )
                }
            )
        }
    }

    /** Dismiss the upload dialog (e.g. "Continue anyway" or after the success auto-dismiss). */
    fun dismissUploadDialog() {
        _uiState.value = _uiState.value.copy(uploadState = UploadState.Idle)
    }

    /**
     * Write the session to local Documents files. This is an offline convenience and does NOT change the
     * session status — only a successful server upload marks it UPLOADED.
     */
    fun exportSession() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExporting = true)

            exporter.exportSession(sessionId).fold(
                onSuccess = { path ->
                    _uiState.value = _uiState.value.copy(
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

    fun deleteSession(onDeleted: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            sessionRepository.deleteSession(sessionId)
            onDeleted()
        }
    }

    fun clearExportResult() {
        _uiState.value = _uiState.value.copy(exportResult = null)
    }
}
