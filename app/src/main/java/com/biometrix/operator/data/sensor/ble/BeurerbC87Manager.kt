package com.biometrix.operator.data.sensor.ble

import com.biometrix.operator.data.model.BloodPressureReading
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * State machine for the BC87 blood pressure monitor connection lifecycle.
 */
sealed class Bc87State {
    data object Idle : Bc87State()
    data object Scanning : Bc87State()
    data object Connecting : Bc87State()
    data object Receiving : Bc87State()
    data class Error(val message: String) : Bc87State()
}

/**
 * Manager interface for the Beurer BC 87 wrist blood pressure monitor.
 *
 * The BC87 is an episodic device: it measures BP offline, then advertises via BLE
 * for ~30 seconds so stored records can be retrieved. This manager handles the full
 * scan → connect → retrieve → disconnect → resume cycle.
 */
interface BeurerbC87Manager {
    /** Current state of the BC87 connection lifecycle. */
    val state: StateFlow<Bc87State>

    /** Most recent reading received (persists until next reading). */
    val lastReading: StateFlow<BloodPressureReading?>

    /** Emits each new reading as it arrives (for collectors that need every event). */
    val readingFlow: SharedFlow<BloodPressureReading>

    /** Debug log: emits (message, isError) pairs at each GATT step. */
    val logFlow: SharedFlow<Pair<String, Boolean>>

    /** Whether Bluetooth is currently enabled on the device (tracks adapter state live). */
    val bluetoothEnabled: StateFlow<Boolean>

    /** Begin scanning for BC87 advertisements. Scan auto-resumes after each device interaction. */
    fun startScanning()

    /** Stop scanning and close any active GATT connection. */
    fun stopScanning()
}
