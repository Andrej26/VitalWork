package com.biometrix.operator.data.sensor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import com.biometrix.operator.data.model.BloodPressureReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Android BLE implementation for the Beurer BC 87 wrist blood pressure monitor.
 *
 * Key behaviours:
 * - ScanFilter on Blood Pressure Service UUID 0x1810
 * - GATT flow: connect → discover → BP CCCD → RACP CCCD → RACP request → collect indications → emit on disconnect
 * - MAP always calculated: (systolic + 2 * diastolic) / 3
 * - pendingReading holds the most recent record; emitted when the session ends (RACP Success or disconnect)
 * - Scan auto-restarts after disconnect; 60-second anti-throttle cycle
 * - Singleton — scanner survives screen navigation
 */
@SuppressLint("MissingPermission")
class BeurerbC87ManagerImpl(
    private val context: Context
) : BeurerbC87Manager {

    companion object {
        // Blood Pressure Service
        private val UUID_BLOOD_PRESSURE_SERVICE: UUID =
            UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")

        // Blood Pressure Measurement characteristic (Indication)
        private val UUID_BP_MEASUREMENT: UUID =
            UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb")

        // Record Access Control Point
        private val UUID_RACP: UUID =
            UUID.fromString("00002a52-0000-1000-8000-00805f9b34fb")

        // Client Characteristic Configuration Descriptor
        private val UUID_CCCD: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_RESTART_DELAY_MS = 60_000L
        private const val FALLBACK_SILENCE_TIMEOUT_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bleScanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null
    @Volatile
    private var isConnecting = false
    private var scanRestartJob: Job? = null
    private var fallbackTimeoutJob: Job? = null
    private var pendingReading: BloodPressureReading? = null
    private var hasRacp = false
    private var racpChar: BluetoothGattCharacteristic? = null
    private var isScanningActive = false

    // --- State Flows ---

    private val _state = MutableStateFlow<Bc87State>(Bc87State.Idle)
    override val state: StateFlow<Bc87State> = _state.asStateFlow()

    private val _lastReading = MutableStateFlow<BloodPressureReading?>(null)
    override val lastReading: StateFlow<BloodPressureReading?> = _lastReading.asStateFlow()

    private val _readingFlow = MutableSharedFlow<BloodPressureReading>(extraBufferCapacity = 10)
    override val readingFlow: SharedFlow<BloodPressureReading> = _readingFlow.asSharedFlow()

    private val _logFlow = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 50)
    override val logFlow: SharedFlow<Pair<String, Boolean>> = _logFlow.asSharedFlow()

    private val _bluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    override val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    _bluetoothEnabled.value = true
                    log("Bluetooth enabled")
                }
                BluetoothAdapter.STATE_OFF,
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    if (_bluetoothEnabled.value) {
                        _bluetoothEnabled.value = false
                        log("Bluetooth disabled", isError = true)
                    }
                    handleBluetoothDisabled()
                }
            }
        }
    }

    init {
        context.applicationContext.registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
    }

    // --- Public API ---

    override fun startScanning() {
        if (isScanningActive) return
        isScanningActive = true
        startScanInternal()
    }

    override fun stopScanning() {
        isScanningActive = false
        scanRestartJob?.cancel()
        scanRestartJob = null
        fallbackTimeoutJob?.cancel()
        fallbackTimeoutJob = null
        stopScanHardware()
        closeGatt()
        pendingReading = null
        _state.value = Bc87State.Idle
    }

    // --- Scan ---

    private fun startScanInternal() {
        if (!isScanningActive) return
        val scanner = bleScanner ?: run {
            log("Bluetooth not available", isError = true)
            _state.value = Bc87State.Error("Bluetooth not available")
            return
        }

        isConnecting = false
        pendingReading = null

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID_BLOOD_PRESSURE_SERVICE))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            _state.value = Bc87State.Scanning
            log("Scanning started")
        } catch (e: Exception) {
            log("Scan start failed: ${e.message}", isError = true)
            _state.value = Bc87State.Error("Scan failed: ${e.message}")
            scheduleResumeScan()
        }
    }

    private fun stopScanHardware() {
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            // Scanner may already be stopped
        }
    }

    private fun scheduleResumeScan() {
        scanRestartJob?.cancel()
        scanRestartJob = scope.launch {
            delay(SCAN_RESTART_DELAY_MS)
            if (isScanningActive) {
                log("Restarting scan (anti-throttle)")
                startScanInternal()
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (isConnecting) return
            isConnecting = true

            val device = result.device
            val address = device.address ?: "unknown"
            log("Device found [$address], connecting...")

            stopScanHardware()
            _state.value = Bc87State.Connecting

            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed (error $errorCode)", isError = true)
            _state.value = Bc87State.Error("Scan error: $errorCode")
            scheduleResumeScan()
        }
    }

    // --- GATT Callback ---

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            bluetoothGatt = gatt

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        log("GATT connected, discovering services...")
                        gatt.discoverServices()
                    } else {
                        handleGattError(status, "Connection")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    fallbackTimeoutJob?.cancel()

                    if (pendingReading != null) {
                        log("Emitting reading on disconnect (no RACP response received)")
                        emitPendingReading()
                    }

                    log("Disconnected")
                    closeGatt()
                    isConnecting = false
                    resumeScanning()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleGattError(status, "Service discovery")
                return
            }
            log("Services discovered")

            val bpService = gatt.getService(UUID_BLOOD_PRESSURE_SERVICE)
            if (bpService == null) {
                handleGattError(null, "Blood Pressure service not found")
                return
            }

            val bpChar = bpService.getCharacteristic(UUID_BP_MEASUREMENT)
            if (bpChar == null) {
                handleGattError(null, "BP Measurement characteristic not found")
                return
            }

            racpChar = bpService.getCharacteristic(UUID_RACP)
            hasRacp = racpChar != null
            if (hasRacp) {
                log("RACP 0x2A52 found — RACP mode")
            } else {
                log("RACP 0x2A52 not found — fallback mode")
            }

            // Step 1: Enable indications on BP Measurement (0x2A35)
            log("Enabling indications on 0x2A35...")
            enableIndication(gatt, bpChar)
        }

        @Suppress("DEPRECATION")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleGattError(status, "Descriptor write")
                return
            }

            val charUuid = descriptor.characteristic.uuid

            when (charUuid) {
                UUID_BP_MEASUREMENT -> {
                    log("Indications enabled on 0x2A35")
                    if (hasRacp) {
                        // Step 2: Enable RACP indications
                        val racp = racpChar
                        if (racp != null) {
                            log("Enabling RACP indications...")
                            enableIndication(gatt, racp)
                        } else {
                            handleGattError(null, "RACP characteristic lost")
                        }
                    } else {
                        // No RACP — start fallback silence timer
                        _state.value = Bc87State.Receiving
                        startFallbackTimeout()
                    }
                }
                UUID_RACP -> {
                    log("RACP indications enabled, sending All Records request...")
                    // Step 3: Write RACP command: Report Stored Records (0x01), All Records (0x01)
                    val racp = racpChar
                    if (racp != null) {
                        _state.value = Bc87State.Receiving
                        writeRacpRequest(gatt, racp)
                    } else {
                        handleGattError(null, "RACP characteristic lost")
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return

            when (characteristic.uuid) {
                UUID_BP_MEASUREMENT -> {
                    BleParsers.parseBpMeasurement(value)?.let { reading ->
                        pendingReading = reading
                        val pulseStr = reading.pulseRateBpm?.let { " · Pulse: $it bpm" } ?: ""
                        log("BP record: ${reading.systolicMmHg}/${reading.diastolicMmHg} mmHg$pulseStr")

                        // Reset fallback timer on each indication
                        if (!hasRacp) {
                            startFallbackTimeout()
                        }
                    }
                }
                UUID_RACP -> {
                    handleRacpResponse(value)
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == UUID_RACP) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("RACP All Records request sent")
                } else {
                    handleGattError(status, "RACP write")
                }
            }
        }
    }

    // --- BLE Helpers ---

    @Suppress("DEPRECATION")
    private fun enableIndication(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(UUID_CCCD) ?: run {
            handleGattError(null, "CCCD descriptor not found for ${characteristic.uuid}")
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    @Suppress("DEPRECATION")
    private fun writeRacpRequest(gatt: BluetoothGatt, racpChar: BluetoothGattCharacteristic) {
        racpChar.value = byteArrayOf(0x01, 0x01) // Report Stored Records, All Records
        gatt.writeCharacteristic(racpChar)
    }

    private fun handleRacpResponse(value: ByteArray) {
        val response = BleParsers.parseRacpResponse(value) ?: return
        when (response.responseCode) {
            BleParsers.RacpResponse.RESPONSE_SUCCESS -> {
                log("RACP response: Success — emitting reading")
                emitPendingReading()
            }
            BleParsers.RacpResponse.RESPONSE_NO_RECORDS -> {
                log("RACP response: No Records — nothing to emit")
            }
            else -> {
                log(
                    "RACP response: error code ${response.responseCode} for request ${response.requestOpcode}",
                    isError = true
                )
            }
        }
    }

    private fun emitPendingReading() {
        val reading = pendingReading ?: return
        _lastReading.value = reading
        _readingFlow.tryEmit(reading)
        pendingReading = null
    }

    private fun startFallbackTimeout() {
        fallbackTimeoutJob?.cancel()
        fallbackTimeoutJob = scope.launch {
            delay(FALLBACK_SILENCE_TIMEOUT_MS)
            log("Fallback timeout — emitting reading")
            emitPendingReading()
            closeGatt()
            resumeScanning()
        }
    }

    private fun closeGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        racpChar = null
    }

    private fun resumeScanning() {
        if (!isScanningActive) {
            _state.value = Bc87State.Idle
            return
        }
        log("Resuming scan...")
        scope.launch {
            // Brief delay before resuming scan to avoid Android throttle
            delay(1_000L)
            startScanInternal()
        }
    }

    private fun handleBluetoothDisabled() {
        scanRestartJob?.cancel()
        scanRestartJob = null
        fallbackTimeoutJob?.cancel()
        fallbackTimeoutJob = null
        stopScanHardware()
        closeGatt()
        isScanningActive = false
        isConnecting = false
        pendingReading = null
        _state.value = Bc87State.Idle
    }

    private fun handleGattError(status: Int?, operation: String) {
        val msg = if (status != null) "$operation failed (status $status)" else operation
        log(msg, isError = true)
        _state.value = Bc87State.Error(msg)
        closeGatt()
        isConnecting = false
        resumeScanning()
    }

    // --- Logging ---

    private fun log(message: String, isError: Boolean = false) {
        _logFlow.tryEmit(message to isError)
    }
}
