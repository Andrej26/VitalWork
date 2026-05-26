package com.biometrix.operator.presentation.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.prefs.TutorialPreferencesRepository
import com.biometrix.operator.data.repository.ConnectionRepository
import com.biometrix.operator.data.repository.TestRepository
import com.biometrix.operator.data.sensor.DeviceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    connectionRepository: ConnectionRepository,
    testRepository: TestRepository,
    private val tutorialPreferences: TutorialPreferencesRepository
) : ViewModel() {

    /** VR headset connection state */
    val vrConnectionState: StateFlow<ConnectionState> = connectionRepository.vrConnectionState

    /** BLE sensor (eSense Pulse) connection state */
    val bleConnectionState: StateFlow<ConnectionState> = connectionRepository.bleConnectionState

    /** Audio sensor (eSense Respiration) state */
    val respirationState: StateFlow<DeviceState> = connectionRepository.respirationState

    /** Whether there is an active test */
    val hasActiveTest: StateFlow<Boolean> = testRepository.activeTest
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True on first launch; drives auto-navigation to the Tutorial screen. */
    private val _shouldAutoShowTutorial = MutableStateFlow(tutorialPreferences.isFirstLaunchPending())
    val shouldAutoShowTutorial: StateFlow<Boolean> = _shouldAutoShowTutorial.asStateFlow()

    /** Call once the Tutorial screen has been auto-navigated to. */
    fun onTutorialAutoShown() {
        tutorialPreferences.markFirstLaunchDone()
        _shouldAutoShowTutorial.update { false }
    }
}
