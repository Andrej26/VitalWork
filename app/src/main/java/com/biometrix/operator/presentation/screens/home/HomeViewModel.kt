package com.biometrix.operator.presentation.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.prefs.TutorialPreferencesRepository
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    connectionRepository: ConnectionRepository,
    private val sessionRepository: SessionRepository,
    private val tutorialPreferences: TutorialPreferencesRepository
) : ViewModel() {

    val vrConnectionState: StateFlow<ConnectionState> = connectionRepository.vrConnectionState

    val activeSession: StateFlow<SessionEntity?> = sessionRepository.activeSession
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _shouldAutoShowTutorial = MutableStateFlow(tutorialPreferences.isFirstLaunchPending())
    val shouldAutoShowTutorial: StateFlow<Boolean> = _shouldAutoShowTutorial.asStateFlow()

    fun onTutorialAutoShown() {
        tutorialPreferences.markFirstLaunchDone()
        _shouldAutoShowTutorial.update { false }
    }

    /**
     * Decides how to begin a session.
     * - If an active session already exists, navigates straight to it (no participant prompt;
     *   the in-flight session already has one).
     * - Otherwise navigates to the participant entry flow, which creates the participant +
     *   session and signals navigation to SessionActive.
     */
    fun beginSession(
        onResumeActive: (Long) -> Unit,
        onStartNewParticipantFlow: () -> Unit
    ) {
        if (_isStarting.value) return
        viewModelScope.launch {
            _isStarting.value = true
            try {
                val active = sessionRepository.getActiveSessionOnce()
                if (active != null) {
                    onResumeActive(active.id)
                } else {
                    onStartNewParticipantFlow()
                }
            } finally {
                _isStarting.value = false
            }
        }
    }
}
