package com.biometrix.operator.presentation.screens.participants

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.repository.ParticipantRepository
import com.biometrix.operator.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class GenderOption(val label: String, val storedValue: String?) {
    MALE("Male", "M"),
    FEMALE("Female", "F"),
    OTHER("Other", "Other"),
    NOT_SPECIFIED("Not specified", null)
}

data class ParticipantEntryUiState(
    val participantCode: String = "",
    val codeError: String? = null,
    val ageInput: String = "",
    val ageError: String? = null,
    val gender: GenderOption = GenderOption.NOT_SPECIFIED,
    val isSubmitting: Boolean = false,
    val isInitialized: Boolean = false,
    val submitError: String? = null
)

sealed class ParticipantEntryEvent {
    data class SessionStarted(val sessionId: Long) : ParticipantEntryEvent()
    data class ActiveSessionDetected(val sessionId: Long) : ParticipantEntryEvent()
}

@HiltViewModel
class ParticipantEntryViewModel @Inject constructor(
    private val participantRepository: ParticipantRepository,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ParticipantEntryUiState())
    val uiState: StateFlow<ParticipantEntryUiState> = _uiState.asStateFlow()

    private val _events = Channel<ParticipantEntryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val existingActive = sessionRepository.getActiveSessionOnce()
            if (existingActive != null) {
                _events.send(ParticipantEntryEvent.ActiveSessionDetected(existingActive.id))
                return@launch
            }
            val suggested = participantRepository.generateNextParticipantCode()
            _uiState.value = _uiState.value.copy(
                participantCode = suggested,
                isInitialized = true
            )
        }
    }

    fun onCodeChange(value: String) {
        _uiState.value = _uiState.value.copy(
            participantCode = value,
            codeError = null,
            submitError = null
        )
    }

    fun onAgeChange(value: String) {
        val sanitized = value.filter { it.isDigit() }.take(3)
        _uiState.value = _uiState.value.copy(
            ageInput = sanitized,
            ageError = null,
            submitError = null
        )
    }

    fun onGenderChange(option: GenderOption) {
        _uiState.value = _uiState.value.copy(gender = option, submitError = null)
    }

    fun submit() {
        val current = _uiState.value
        if (current.isSubmitting) return

        val trimmedCode = current.participantCode.trim()
        if (trimmedCode.isEmpty()) {
            _uiState.value = current.copy(codeError = "Participant code is required")
            return
        }

        val age = current.ageInput.toIntOrNull()
        if (current.ageInput.isNotEmpty() && (age == null || age !in 18..80)) {
            _uiState.value = current.copy(ageError = "Age must be between 18 and 80")
            return
        }

        _uiState.value = current.copy(isSubmitting = true, submitError = null)
        viewModelScope.launch {
            try {
                val existing = participantRepository.getParticipantByCode(trimmedCode)
                if (existing != null) {
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        codeError = "A participant with this code already exists"
                    )
                    return@launch
                }

                val participant = participantRepository.createParticipant(
                    participantCode = trimmedCode,
                    age = age,
                    gender = current.gender.storedValue
                )

                val session = sessionRepository.createSessionIfNoneActive(participant.id)
                if (session == null) {
                    val active = sessionRepository.getActiveSessionOnce()
                    if (active != null) {
                        _events.send(ParticipantEntryEvent.ActiveSessionDetected(active.id))
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isSubmitting = false,
                            submitError = "Failed to start session"
                        )
                    }
                    return@launch
                }

                _events.send(ParticipantEntryEvent.SessionStarted(session.id))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    submitError = e.message ?: "Failed to create participant"
                )
            }
        }
    }
}
