package com.biometrix.operator.presentation.screens.sensors.watch

import androidx.lifecycle.ViewModel
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.sensor.watch.WatchSensorReceiver
import com.biometrix.operator.data.sensor.watch.model.WatchReading
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Diagnostic ViewModel for the Galaxy Watch evaluation screen. Surfaces the live readings,
 * supported-tracker list, and battery from [WatchSensorReceiver] — Phase 1 is display only.
 */
@HiltViewModel
class WatchSensorViewModel @Inject constructor(
    receiver: WatchSensorReceiver
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = receiver.connectionState
    val latestByType: StateFlow<Map<String, WatchReading>> = receiver.latestByType
    val availableTrackers: StateFlow<List<String>> = receiver.availableTrackers
    val batteryLevel: StateFlow<Int?> = receiver.batteryLevel
}
