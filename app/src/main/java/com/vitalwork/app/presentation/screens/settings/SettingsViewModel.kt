package com.vitalwork.app.presentation.screens.settings

import androidx.lifecycle.ViewModel
import com.vitalwork.app.data.link.PeerRole
import com.vitalwork.app.data.prefs.DEVICE_PREFIXES
import com.vitalwork.app.data.prefs.DeviceModePreferencesRepository
import com.vitalwork.app.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val deviceModePreferences: DeviceModePreferencesRepository
) : ViewModel() {

    val devicePrefixes: List<String> = DEVICE_PREFIXES

    private val _devicePrefix = MutableStateFlow(settingsRepository.getDevicePrefix())
    val devicePrefix: StateFlow<String> = _devicePrefix.asStateFlow()

    /** Device-link role (Server/Client). Null only before the first-launch pick, which can't
     *  happen here — Settings is reachable only from Home, i.e. after a mode was chosen. */
    private val _deviceMode = MutableStateFlow(deviceModePreferences.getMode())
    val deviceMode: StateFlow<PeerRole?> = _deviceMode.asStateFlow()

    fun onPrefixSelected(value: String) {
        settingsRepository.setDevicePrefix(value)
        _devicePrefix.value = value
    }

    fun onModeSelected(role: PeerRole) {
        deviceModePreferences.setMode(role)
        _deviceMode.value = role
    }
}
