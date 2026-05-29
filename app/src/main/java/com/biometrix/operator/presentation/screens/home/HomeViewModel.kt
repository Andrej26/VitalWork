package com.biometrix.operator.presentation.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.db.SessionEntity
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.prefs.TutorialPreferencesRepository
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.repository.SessionRepository
import com.biometrix.operator.data.system.SessionPrerequisite
import com.biometrix.operator.data.system.SystemReadinessChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val tutorialPreferences: TutorialPreferencesRepository,
    private val readinessChecker: SystemReadinessChecker
) : ViewModel() {

    val vrConnectionState: StateFlow<ConnectionState> = connectionRepository.vrConnectionState

    /**
     * Session prerequisites currently missing. Re-derived from live OS state via [refresh] on
     * every screen resume, so a silently-revoked permission/setting reappears here immediately.
     */
    private val _missingPrerequisites =
        MutableStateFlow<Set<SessionPrerequisite>>(emptySet())
    val missingPrerequisites: StateFlow<Set<SessionPrerequisite>> =
        _missingPrerequisites.asStateFlow()

    private var refreshJob: Job? = null

    /**
     * Re-derive readiness from live OS state. Checks immediately, then re-checks a couple of times
     * over the next second: some systems (notably MIUI's battery-optimization flow) report the new
     * value with a short lag after the user confirms and returns, which previously left the card
     * stale until the next manual tap.
     */
    fun refresh() {
        _missingPrerequisites.value = readinessChecker.missingPrerequisites()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            for (delayMs in longArrayOf(350L, 800L)) {
                delay(delayMs)
                _missingPrerequisites.value = readinessChecker.missingPrerequisites()
            }
        }
    }

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
