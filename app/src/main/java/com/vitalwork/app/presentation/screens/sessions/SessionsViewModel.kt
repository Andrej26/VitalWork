package com.vitalwork.app.presentation.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitalwork.app.data.db.SessionEntity
import com.vitalwork.app.data.db.SessionStatus
import com.vitalwork.app.data.export.SessionUploader
import com.vitalwork.app.data.recording.ScenarioRecordingRepository
import com.vitalwork.app.data.recording.model.DataRecordingState
import com.vitalwork.app.data.repository.ConnectionRepository
import com.vitalwork.app.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionsUiState(
    val sessions: List<SessionEntity> = emptyList(),
    val activeSession: SessionEntity? = null,
    val isLoading: Boolean = true,
    val activeSessionHeartRate: Int? = null,
    val activeSessionDuration: String = "00:00:00",
    val isRecording: Boolean = false
) {
    /** Completed sessions not yet on the server — the pending-upload safety-net count. */
    val pendingUploadCount: Int get() = sessions.count { it.status == SessionStatus.COMPLETED }
}

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val connectionRepository: ConnectionRepository,
    private val sensorRecordingRepository: ScenarioRecordingRepository,
    private val uploader: SessionUploader,
) : ViewModel() {

    private val tickerFlow = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }

    /** Session ids with an upload currently in flight (drives per-card spinners). */
    private val _uploadingIds = MutableStateFlow<Set<Long>>(emptySet())
    val uploadingIds: StateFlow<Set<Long>> = _uploadingIds.asStateFlow()

    /** One-shot user-facing message after a manual upload (success or failure). */
    private val _uploadMessage = MutableStateFlow<String?>(null)
    val uploadMessage: StateFlow<String?> = _uploadMessage.asStateFlow()

    val uiState: StateFlow<SessionsUiState> = combine(
        sessionRepository.allSessions,
        sessionRepository.activeSession,
        connectionRepository.heartRate,
        sensorRecordingRepository.recordingState,
        tickerFlow
    ) { allSessions, activeSession, heartRate, recordingState, currentTimeMs ->
        val completedSessions = allSessions.filter { it.status != SessionStatus.ACTIVE }
        val activeSessionDurationMs = activeSession?.let { currentTimeMs - it.startedAt } ?: 0L

        SessionsUiState(
            sessions = completedSessions,
            activeSession = activeSession,
            isLoading = false,
            activeSessionHeartRate = heartRate,
            activeSessionDuration = formatDuration(activeSessionDurationMs),
            isRecording = recordingState == DataRecordingState.RECORDING
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SessionsUiState())

    /** Manually upload a pending session (offline-now / upload-later path). Safe to retry. */
    fun uploadSession(sessionId: Long) {
        if (sessionId in _uploadingIds.value) return
        viewModelScope.launch {
            _uploadingIds.value = _uploadingIds.value + sessionId
            val result = uploader.upload(sessionId)
            result.fold(
                onSuccess = {
                    sessionRepository.markUploaded(sessionId)
                    _uploadMessage.value = "Uploaded to server."
                },
                onFailure = { error ->
                    _uploadMessage.value = "Upload failed: ${error.message}"
                }
            )
            _uploadingIds.value = _uploadingIds.value - sessionId
        }
    }

    fun clearUploadMessage() {
        _uploadMessage.value = null
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
