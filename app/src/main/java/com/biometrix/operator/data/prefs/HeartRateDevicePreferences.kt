package com.biometrix.operator.data.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class HeartRateDevice {
    ESENSE_PULSE,
    FIBION_FLASH
}

@Singleton
class HeartRateDevicePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("hr_device_prefs", Context.MODE_PRIVATE)

    private val _selectedDevice = MutableStateFlow(loadSelection())
    val selectedDevice: StateFlow<HeartRateDevice> = _selectedDevice.asStateFlow()

    val hasExplicitSelection: Boolean
        get() = prefs.getBoolean("has_explicit_selection", false)

    fun select(device: HeartRateDevice) {
        prefs.edit()
            .putString("selected_hr_device", device.name)
            .putBoolean("has_explicit_selection", true)
            .apply()
        _selectedDevice.value = device
    }

    private fun loadSelection(): HeartRateDevice {
        val name = prefs.getString("selected_hr_device", null)
        return name?.let { runCatching { HeartRateDevice.valueOf(it) }.getOrNull() }
            ?: HeartRateDevice.ESENSE_PULSE
    }
}
