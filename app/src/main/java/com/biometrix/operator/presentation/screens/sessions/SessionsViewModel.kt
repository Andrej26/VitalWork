package com.biometrix.operator.presentation.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.recording.SensorRecordingRepository
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SessionsUiState(
    val sessions: List<SessionEntity> = emptyList(),
    val activeSession: SessionEntity? = null,
    val isLoading: Boolean = true,
    val activeSessionHeartRate: Int? = null,
    val activeSessionDuration: String = "00:00:00",
    val isRecording: Boolean = false
)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val connectionRepository: ConnectionRepository,
    private val sensorRecordingRepository: SensorRecordingRepository,
) : ViewModel() {

    private val tickerFlow = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }

    val uiState: StateFlow<SessionsUiState> = combine(
        sessionRepository.allSessions,
        sessionRepository.activeSession,
        connectionRepository.heartRate,
        sensorRecordingRepository.recordingState,
        tickerFlow
    ) { allSessions, activeSession, heartRate, recordingState, currentTimeMs ->
        val completedSessions = allSessions.filter { it.status != SessionStatus.ACTIVE }
        val activeSessionDurationMs = activeSession?.let { currentTimeMs - it.createdAt } ?: 0L

        SessionsUiState(
            sessions = completedSessions,
            activeSession = activeSession,
            isLoading = false,
            activeSessionHeartRate = heartRate,
            activeSessionDuration = formatDuration(activeSessionDurationMs),
            isRecording = recordingState == DataRecordingState.RECORDING
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SessionsUiState())

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
