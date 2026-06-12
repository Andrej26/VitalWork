package com.vitalwork.app.presentation.screens.settings

import androidx.lifecycle.ViewModel
import com.vitalwork.app.data.prefs.DEVICE_PREFIXES
import com.vitalwork.app.data.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val devicePrefixes: List<String> = DEVICE_PREFIXES

    private val _devicePrefix = MutableStateFlow(settingsRepository.getDevicePrefix())
    val devicePrefix: StateFlow<String> = _devicePrefix.asStateFlow()

    fun onPrefixSelected(value: String) {
        settingsRepository.setDevicePrefix(value)
        _devicePrefix.value = value
    }
}
