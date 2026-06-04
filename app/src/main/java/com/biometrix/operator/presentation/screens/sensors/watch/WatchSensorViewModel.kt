package com.biometrix.operator.presentation.screens.sensors.watch

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.ViewModel
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.sensor.watch.WatchSensorReceiver
import com.biometrix.operator.data.sensor.watch.model.WatchReading
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Diagnostic ViewModel for the Galaxy Watch evaluation screen. Surfaces the live readings,
 * supported-tracker list, and battery from [WatchSensorReceiver] — Phase 1 is display only.
 *
 * Also tracks the phone's own Bluetooth adapter state: the watch ↔ tablet Data Layer link must run
 * over direct Bluetooth (the cloud relay can't deliver to a dozing phone), so when Bluetooth is off
 * the screen surfaces a warning to turn it on — mirroring the eSense Pulse screen.
 */
@HiltViewModel
class WatchSensorViewModel @Inject constructor(
    receiver: WatchSensorReceiver,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = receiver.connectionState
    val latestByType: StateFlow<Map<String, WatchReading>> = receiver.latestByType
    val availableTrackers: StateFlow<List<String>> = receiver.availableTrackers
    val batteryLevel: StateFlow<Int?> = receiver.batteryLevel

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _bluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    /** Whether the phone's Bluetooth adapter is on (required for the direct watch link). */
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> _bluetoothEnabled.value = true
                    BluetoothAdapter.STATE_OFF -> _bluetoothEnabled.value = false
                }
            }
        }
    }

    init {
        context.registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
    }

    override fun onCleared() {
        super.onCleared()
        context.unregisterReceiver(bluetoothStateReceiver)
    }
}
