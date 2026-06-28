package com.vitalwork.app.presentation.screens.mode

import androidx.lifecycle.ViewModel
import com.vitalwork.app.data.link.PeerRole
import com.vitalwork.app.data.prefs.DeviceModePreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ModeSelectionViewModel @Inject constructor(
    private val deviceModePreferences: DeviceModePreferencesRepository
) : ViewModel() {

    /** Persist the picked role so the next launch skips this screen. */
    fun selectMode(role: PeerRole) {
        deviceModePreferences.setMode(role)
    }
}
