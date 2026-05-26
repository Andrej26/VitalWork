package com.biometrix.operator.data.sensor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.SparseArray
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import com.biometrix.operator.data.sensor.ble.model.BleGattCharacteristic
import com.biometrix.operator.data.sensor.ble.model.BleGattService
import com.biometrix.operator.data.sensor.ble.model.CharacteristicProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Implementation of BleManager using Android's Bluetooth LE APIs.
 */
class BleManagerImpl(
    private val context: Context
) : BleManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionTimeoutJob: Job? = null

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bleScanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null

    // State flows
    private val _bluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    override val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<BleDevice>> = _discoveredDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BleDevice?>(null)
    override val connectedDevice: StateFlow<BleDevice?> = _connectedDevice.asStateFlow()

    private val _discoveredServices = MutableStateFlow<List<BleGattService>>(emptyList())
    override val discoveredServices: StateFlow<List<BleGattService>> = _discoveredServices.asStateFlow()

    private val _heartRate = MutableStateFlow<Int?>(null)
    override val heartRate: StateFlow<Int?> = _heartRate.asStateFlow()

    private val _heartRateSampleFlow = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    override val heartRateSampleFlow: SharedFlow<Float> = _heartRateSampleFlow.asSharedFlow()

    private val _rrIntervalSampleFlow = MutableSharedFlow<Float>(extraBufferCapacity = 256)
    override val rrIntervalSampleFlow: SharedFlow<Float> = _rrIntervalSampleFlow.asSharedFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    override val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _bleEvents = MutableSharedFlow<BleEvent>(replay = 0, extraBufferCapacity = 64)
    override val bleEvents: Flow<BleEvent> = _bleEvents.asSharedFlow()

    private var warmupJob: Job? = null
    private val _isHeartRateWarmingUp = MutableStateFlow(false)
    override val isHeartRateWarmingUp: StateFlow<Boolean> = _isHeartRateWarmingUp.asStateFlow()

    // Tracks whether the CCCD has been written to enable HR notifications for the current connection.
    // Prevents redundant CCCD writes (and the warmup restart they cause) from multiple callers.
    private var heartRateNotificationsEnabled = false

    // Bluetooth state receiver - monitors adapter on/off changes
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        _bluetoothEnabled.value = true
                        emitEvent(BleEvent.Debug("Bluetooth enabled"))
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        _bluetoothEnabled.value = false
                        emitEvent(BleEvent.Debug("Bluetooth disabled"))
                        handleBluetoothDisabled()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF -> {
                        handleBluetoothDisabled()
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothStateReceiver, filter)
    }

    // Scan callback
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = parseScannedDevice(result)

            // Only show eSense devices
            if (isEsenseDevice(device, result)) {
                updateDiscoveredDevices(device)
                emitEvent(BleEvent.DeviceFound(device))
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                val device = parseScannedDevice(result)
                if (isEsenseDevice(device, result)) {
                    updateDiscoveredDevices(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error ($errorCode)"
            }
            emitEvent(BleEvent.Error("Scan failed: $errorMessage"))
        }
    }

    // GATT callback
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionTimeoutJob?.cancel()
                    _connectionState.value = ConnectionState.CONNECTED
                    _connectedDevice.value?.let { emitEvent(BleEvent.Connected(it)) }
                    emitEvent(BleEvent.DiscoveringServices())
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = _connectionState.value == ConnectionState.CONNECTED
                    val deviceName = _connectedDevice.value?.displayName ?: "Unknown"

                    if (status != BluetoothGatt.GATT_SUCCESS && wasConnected) {
                        resetConnectionState(emitDisconnectedEvent = false)
                        emitEvent(BleEvent.UnexpectedDisconnection(deviceName, status))
                    } else {
                        val reason = if (status != BluetoothGatt.GATT_SUCCESS) "Status: $status" else null
                        resetConnectionState(emitDisconnectedEvent = true, reason = reason)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val services = gatt.services.map { parseGattService(it) }
                _discoveredServices.value = services
                services.forEach { service ->
                    emitEvent(BleEvent.ServiceDiscovered(service))
                }
                emitEvent(BleEvent.ServicesDiscoveryComplete(services.size))
                // Balanced priority (30–50 ms intervals) is sufficient for 1 Hz HR data
                // and coexists better with other concurrent BLE connections (e.g. Fibion Flash)
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                enableHeartRateNotifications()
            } else {
                emitEvent(BleEvent.Error("Service discovery failed with status: $status"))
            }
        }

        // Android 13+ (API 33+) version
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleHeartRateData(characteristic.uuid, value)
        }

        // Legacy version for Android 12 and below
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            handleHeartRateData(characteristic.uuid, value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    val isDisable = descriptor.value?.contentEquals(
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    ) == true

                    if (isDisable) {
                        warmupJob?.cancel()
                        _isHeartRateWarmingUp.value = false
                        heartRateNotificationsEnabled = false
                        _heartRate.value = null
                        emitEvent(BleEvent.HeartRateNotificationDisabled())
                    } else {
                        emitEvent(BleEvent.HeartRateNotificationEnabled())
                        heartRateNotificationsEnabled = true
                        // Start warmup: readings shown in UI but not recorded
                        warmupJob?.cancel()
                        _isHeartRateWarmingUp.value = true
                        warmupJob = scope.launch {
                            delay(HEART_RATE_WARMUP_MS)
                            _isHeartRateWarmingUp.value = false
                        }
                        readBatteryLevel()
                    }
                } else {
                    emitEvent(BleEvent.Error("Failed to write CCCD descriptor: status $status"))
                }
            }
        }

        // Android 13+ (API 33+) version
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleBatteryLevelData(characteristic.uuid, value)
            }
        }

        // Legacy version for Android 12 and below
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value ?: return
                handleBatteryLevelData(characteristic.uuid, value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun startScan() {
        if (_isScanning.value) {
            emitEvent(BleEvent.Debug("Scan already in progress"))
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            emitEvent(BleEvent.Error("Bluetooth is disabled"))
            return
        }

        val scanner = bleScanner
        if (scanner == null) {
            emitEvent(BleEvent.Error("Bluetooth LE Scanner not available"))
            return
        }

        _discoveredDevices.value = emptyList()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, scanSettings, scanCallback)
            _isScanning.value = true
            emitEvent(BleEvent.ScanStarted())
        } catch (e: SecurityException) {
            emitEvent(BleEvent.Error("Permission denied: ${e.message}"))
        } catch (e: Exception) {
            emitEvent(BleEvent.Error("Scan failed: ${e.message}"))
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        if (!_isScanning.value) return

        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            emitEvent(BleEvent.Error("Permission denied: ${e.message}"))
        } catch (e: IllegalStateException) {
            // Scanner may be unavailable if BT was just disabled
            emitEvent(BleEvent.Debug("Could not stop scan: ${e.message}"))
        }
        _isScanning.value = false
        emitEvent(BleEvent.ScanStopped())
    }

    @SuppressLint("MissingPermission")
    override fun connect(device: BleDevice) {
        stopScan()

        _connectionState.value = ConnectionState.CONNECTING
        _connectedDevice.value = device
        _discoveredServices.value = emptyList()
        emitEvent(BleEvent.Connecting(device))

        try {
            val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address)
            if (bluetoothDevice == null) {
                _connectionState.value = ConnectionState.ERROR
                emitEvent(BleEvent.Error("Could not find device with address: ${device.address}"))
                return
            }

            bluetoothGatt = bluetoothDevice.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = scope.launch {
                delay(CONNECTION_TIMEOUT_MS)
                if (_connectionState.value == ConnectionState.CONNECTING) {
                    _connectionState.value = ConnectionState.ERROR
                    _connectedDevice.value?.let { d ->
                        emitEvent(BleEvent.ConnectionTimeout(d.displayName))
                    }
                    try {
                        bluetoothGatt?.disconnect()
                        bluetoothGatt?.close()
                    } catch (_: SecurityException) { }
                    bluetoothGatt = null
                    _connectedDevice.value = null
                }
            }
        } catch (e: SecurityException) {
            _connectionState.value = ConnectionState.ERROR
            emitEvent(BleEvent.Error("Permission denied: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            _connectionState.value = ConnectionState.ERROR
            emitEvent(BleEvent.Error("Invalid device address: ${device.address}"))
        }
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        connectionTimeoutJob?.cancel()
        try {
            bluetoothGatt?.disconnect()
        } catch (e: SecurityException) {
            emitEvent(BleEvent.Error("Permission denied: ${e.message}"))
        }
    }

    @SuppressLint("MissingPermission")
    override fun enableHeartRateNotifications() {
        if (heartRateNotificationsEnabled) return
        withHeartRateCharacteristic { gatt, char ->
            writeHeartRateCccd(gatt, char, enable = true)
        }
    }

    @SuppressLint("MissingPermission")
    override fun disableHeartRateNotifications() {
        withHeartRateCharacteristic { gatt, char ->
            writeHeartRateCccd(gatt, char, enable = false)
        }
    }

    @SuppressLint("MissingPermission")
    private inline fun withHeartRateCharacteristic(
        block: (BluetoothGatt, BluetoothGattCharacteristic) -> Unit
    ) {
        val gatt = bluetoothGatt
        if (gatt == null) {
            emitEvent(BleEvent.Error("Not connected to device"))
            return
        }
        val service = gatt.getService(HEART_RATE_SERVICE_UUID)
        if (service == null) {
            emitEvent(BleEvent.Error("Heart Rate service not found"))
            return
        }
        val char = service.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
        if (char == null) {
            emitEvent(BleEvent.Error("Heart Rate Measurement characteristic not found"))
            return
        }
        block(gatt, char)
    }

    @SuppressLint("MissingPermission")
    private fun writeHeartRateCccd(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        enable: Boolean
    ) {
        try {
            val notificationSet = gatt.setCharacteristicNotification(char, enable)
            if (enable && !notificationSet) {
                emitEvent(BleEvent.Error("Failed to enable local notifications"))
                return
            }

            val cccd = char.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                emitEvent(BleEvent.Error("CCCD descriptor not found"))
                return
            }

            val value = if (enable) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }

            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            val writeResult = gatt.writeDescriptor(cccd)
            if (!writeResult) {
                emitEvent(BleEvent.Error("Failed to write CCCD descriptor"))
            } else {
                val verb = if (enable) "Enabling" else "Disabling"
                emitEvent(BleEvent.Debug("$verb heart rate notifications..."))
            }
        } catch (e: SecurityException) {
            emitEvent(BleEvent.Error("Permission denied: ${e.message}"))
        }
    }

    @SuppressLint("MissingPermission")
    override fun readBatteryLevel() {
        val gatt = bluetoothGatt
        if (gatt == null) {
            emitEvent(BleEvent.Error("Not connected to device"))
            return
        }

        val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
        if (batteryService == null) {
            emitEvent(BleEvent.Debug("Battery service not found"))
            return
        }

        val batteryChar = batteryService.getCharacteristic(BATTERY_LEVEL_UUID)
        if (batteryChar == null) {
            emitEvent(BleEvent.Debug("Battery level characteristic not found"))
            return
        }

        try {
            @Suppress("DEPRECATION")
            val readStarted = gatt.readCharacteristic(batteryChar)
            if (!readStarted) {
                emitEvent(BleEvent.Error("Failed to start battery read"))
            } else {
                emitEvent(BleEvent.Debug("Reading battery level..."))
            }
        } catch (e: SecurityException) {
            emitEvent(BleEvent.Error("Permission denied: ${e.message}"))
        }
    }

    override fun resetScanState() {
        _isScanning.value = false
        _discoveredDevices.value = emptyList()
        emitEvent(BleEvent.Debug("Scan state reset"))
    }

    @SuppressLint("MissingPermission")
    override fun cleanup() {
        stopScan()
        resetConnectionState(emitDisconnectedEvent = false)
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered or already unregistered
        }
        scope.cancel()
    }

    /**
     * Handle Bluetooth being disabled: stop scanning and disconnect cleanly.
     */
    private fun handleBluetoothDisabled() {
        if (_isScanning.value) {
            _isScanning.value = false
            emitEvent(BleEvent.ScanStopped())
        }
        val state = _connectionState.value
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
            resetConnectionState(emitDisconnectedEvent = true, reason = "Bluetooth disabled")
        }
    }

    /**
     * Tear down any active connection and reset all connection-related state.
     * When [emitDisconnectedEvent] is true, emits a [BleEvent.Disconnected] with the given [reason].
     */
    @SuppressLint("MissingPermission")
    private fun resetConnectionState(
        emitDisconnectedEvent: Boolean,
        reason: String? = null
    ) {
        connectionTimeoutJob?.cancel()
        warmupJob?.cancel()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: SecurityException) {
            // Ignore - may happen during cleanup or when BT is turning off
        }
        bluetoothGatt = null

        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDevice.value = null
        _discoveredServices.value = emptyList()
        _heartRate.value = null
        _batteryLevel.value = null
        _isHeartRateWarmingUp.value = false
        heartRateNotificationsEnabled = false

        if (emitDisconnectedEvent) {
            emitEvent(BleEvent.Disconnected(reason))
        }
    }

    // Helper methods
    @SuppressLint("MissingPermission")
    private fun parseScannedDevice(result: ScanResult): BleDevice {
        val scanRecord = result.scanRecord
        val advertisementData = mutableMapOf<String, String>()

        // Get device name from scan record or device
        val deviceName = scanRecord?.deviceName ?: result.device.name

        scanRecord?.let { record ->
            // TX Power Level
            record.txPowerLevel.takeIf { it != Int.MIN_VALUE }?.let {
                advertisementData["TX Power"] = "$it dBm"
            }

            // Service UUIDs
            record.serviceUuids?.let { uuids ->
                if (uuids.isNotEmpty()) {
                    advertisementData["Service UUIDs"] = uuids.joinToString(", ") {
                        it.uuid.toString().substring(4, 8).uppercase()
                    }
                }
            }

            // Manufacturer specific data
            record.manufacturerSpecificData?.let { data ->
                parseManufacturerData(data, advertisementData)
            }

            // Service data
            record.serviceData?.forEach { (uuid, bytes) ->
                advertisementData["Service Data (${uuid.uuid.toString().substring(4, 8)})"] =
                    bytes.toHexString()
            }
        }

        return BleDevice(
            name = deviceName,
            address = result.device.address,
            rssi = result.rssi,
            advertisementData = advertisementData,
            isConnectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                result.isConnectable
            } else {
                true // Assume connectable on older APIs
            }
        )
    }

    private fun parseManufacturerData(
        data: SparseArray<ByteArray>,
        advertisementData: MutableMap<String, String>
    ) {
        for (i in 0 until data.size()) {
            val manufacturerId = data.keyAt(i)
            val manufacturerData = data.valueAt(i)
            val companyName = BleParsers.getCompanyName(manufacturerId)
            val key = if (companyName != null) {
                "Manufacturer ($companyName)"
            } else {
                "Manufacturer (0x${manufacturerId.toString(16).uppercase().padStart(4, '0')})"
            }
            advertisementData[key] = manufacturerData.toHexString()
        }
    }

    /**
     * Check if a device is an eSense device by name prefix or manufacturer ID.
     */
    @SuppressLint("MissingPermission")
    private fun isEsenseDevice(device: BleDevice, result: ScanResult): Boolean {
        // Check device name prefix
        if (device.name?.startsWith("eSense", ignoreCase = true) == true) {
            return true
        }

        // Check manufacturer ID (0xFF0C for eSense Pulse)
        val manufacturerData = result.scanRecord?.manufacturerSpecificData
        if (manufacturerData != null) {
            for (i in 0 until manufacturerData.size()) {
                if (manufacturerData.keyAt(i) == ESENSE_MANUFACTURER_ID) {
                    return true
                }
            }
        }

        return false
    }

    private fun parseGattService(service: BluetoothGattService): BleGattService {
        return BleGattService(
            uuid = service.uuid,
            name = BleParsers.getServiceName(service.uuid),
            characteristics = service.characteristics.map { parseCharacteristic(it) }
        )
    }

    private fun parseCharacteristic(char: BluetoothGattCharacteristic): BleGattCharacteristic {
        val properties = mutableSetOf<CharacteristicProperty>()

        if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0)
            properties.add(CharacteristicProperty.READ)
        if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            properties.add(CharacteristicProperty.WRITE)
        if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            properties.add(CharacteristicProperty.WRITE_NO_RESPONSE)
        if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            properties.add(CharacteristicProperty.NOTIFY)
        if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            properties.add(CharacteristicProperty.INDICATE)
        if (char.properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0)
            properties.add(CharacteristicProperty.SIGNED_WRITE)
        if (char.properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0)
            properties.add(CharacteristicProperty.EXTENDED_PROPERTIES)

        return BleGattCharacteristic(
            uuid = char.uuid,
            name = BleParsers.getCharacteristicName(char.uuid),
            properties = properties
        )
    }

    private fun updateDiscoveredDevices(device: BleDevice) {
        val currentList = _discoveredDevices.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.address == device.address }

        if (existingIndex >= 0) {
            currentList[existingIndex] = device
        } else {
            currentList.add(device)
        }

        // Sort by RSSI (strongest signal first)
        _discoveredDevices.value = currentList.sortedByDescending { it.rssi }
    }

    private fun emitEvent(event: BleEvent) {
        _bleEvents.tryEmit(event)
    }

    /**
     * Handle incoming heart rate data from characteristic notifications.
     */
    private fun handleHeartRateData(uuid: UUID, value: ByteArray) {
        if (uuid != HEART_RATE_MEASUREMENT_UUID || value.size < 2) return
        val measurement = BleParsers.parseHeartRateMeasurement(value) ?: return
        if (measurement.heartRate <= 0) return

        _heartRate.value = measurement.heartRate
        emitEvent(BleEvent.HeartRateReceived(measurement.heartRate))
        // Only feed recording flows after warmup (display always updates)
        if (_isHeartRateWarmingUp.value) return

        _heartRateSampleFlow.tryEmit(measurement.heartRate.toFloat())
        if (measurement.rrIntervals.isEmpty()) return

        var rrDropped = false
        for (rr in measurement.rrIntervals) {
            if (!_rrIntervalSampleFlow.tryEmit(rr)) rrDropped = true
        }
        if (rrDropped) {
            emitEvent(BleEvent.Debug("eSense RR buffer full — sample(s) dropped"))
        }
    }

    /**
     * Handle battery level data from characteristic read.
     */
    private fun handleBatteryLevelData(uuid: UUID, value: ByteArray) {
        if (uuid == BATTERY_LEVEL_UUID && value.isNotEmpty()) {
            val level = value[0].toInt() and 0xFF
            _batteryLevel.value = level
            emitEvent(BleEvent.BatteryLevelRead(level))
        }
    }

    companion object {
        // Heart rate readings may be inaccurate for the first few seconds after enabling
        // notifications (motion artifacts, sensor settling) — hide from recording during warmup.
        private const val HEART_RATE_WARMUP_MS = 5_000L

        // Max time to wait for a GATT connection before giving up.
        private const val CONNECTION_TIMEOUT_MS = 8_000L

        // Heart Rate Service and Characteristic UUIDs (Bluetooth SIG standard)
        private val HEART_RATE_SERVICE_UUID: UUID =
            UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT_UUID: UUID =
            UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")

        // Client Characteristic Configuration Descriptor UUID
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Battery Service and Characteristic UUIDs (Bluetooth SIG standard)
        private val BATTERY_SERVICE_UUID: UUID =
            UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_UUID: UUID =
            UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")
    }
}
