package com.biometrix.operator.data.sensor.fibion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import com.biometrix.operator.data.sensor.fibion.model.FibionFlashDeviceInfo
import com.movesense.mds.Mds
import com.movesense.mds.MdsConnectionListener
import com.movesense.mds.MdsException
import com.movesense.mds.MdsNotificationListener
import com.movesense.mds.MdsHeader
import com.movesense.mds.MdsResponseListener
import com.movesense.mds.MdsSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Implementation of [FibionFlashManager] using the Movesense MDS library.
 *
 * BLE scanning is done using Android's BluetoothLeScanner (filtering for "Movesense" prefix).
 * After discovery, connection and data streaming are delegated to the MDS library which
 * provides a REST-like API over BLE.
 *
 * Always operates in Chest mode — Heart Rate and ECG only.
 *
 * Pure parsing/matching logic lives in [FibionParsers] so it can be unit-tested without
 * Android or MDS dependencies.
 */
class FibionFlashManagerImpl(
    private val context: Context
) : FibionFlashManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionTimeoutJob: Job? = null
    private var batteryPollingJob: Job? = null
    private var autoReconnectJob: Job? = null

    private var userRequestedDisconnect = false

    private val mds: Mds by lazy {
        Mds.builder().build(context)
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bleScanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private val activeSubscriptions = mutableMapOf<String, MdsSubscription>()

    // MDS uses the serial number as device identifier for REST calls.
    private var connectedSerial: String? = null

    private var filterByName = true

    // Full ECG path used by the active ECG subscription (for correct unsubscribe URI).
    private var activeEcgPath: String? = null

    // --- State flows ---

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

    private val _deviceSerial = MutableStateFlow<String?>(null)
    override val deviceSerial: StateFlow<String?> = _deviceSerial.asStateFlow()

    private val _deviceInfo = MutableStateFlow<FibionFlashDeviceInfo?>(null)
    override val deviceInfo: StateFlow<FibionFlashDeviceInfo?> = _deviceInfo.asStateFlow()

    // --- Sensor data flows ---

    private val _heartRate = MutableStateFlow<Int?>(null)
    override val heartRate: StateFlow<Int?> = _heartRate.asStateFlow()

    private val _heartRateSampleFlow = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    override val heartRateSampleFlow: SharedFlow<Float> = _heartRateSampleFlow.asSharedFlow()

    private val _ecgSampleFlow = MutableSharedFlow<Float>(extraBufferCapacity = 1024)
    override val ecgSampleFlow: SharedFlow<Float> = _ecgSampleFlow.asSharedFlow()

    private val _rrIntervalSampleFlow = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    override val rrIntervalSampleFlow: SharedFlow<Float> = _rrIntervalSampleFlow.asSharedFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    override val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _batteryLastUpdated = MutableStateFlow<Long?>(null)
    override val batteryLastUpdated: StateFlow<Long?> = _batteryLastUpdated.asStateFlow()

    private val _events = MutableSharedFlow<FibionFlashEvent>(replay = 0, extraBufferCapacity = 64)
    override val events: Flow<FibionFlashEvent> = _events.asSharedFlow()

    // --- Bluetooth state receiver ---

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
                        emitEvent(FibionFlashEvent.Debug("Bluetooth enabled"))
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        _bluetoothEnabled.value = false
                        emitEvent(FibionFlashEvent.Debug("Bluetooth disabled"))
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

    // --- BLE Scan callback ---

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = parseScannedDevice(result)
            if (FibionParsers.isFibionFlashDevice(device, filterByName)) {
                _discoveredDevices.value = FibionParsers.mergeDiscovered(_discoveredDevices.value, device)
                emitEvent(FibionFlashEvent.DeviceFound(device))
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                val device = parseScannedDevice(result)
                if (FibionParsers.isFibionFlashDevice(device, filterByName)) {
                    _discoveredDevices.value = FibionParsers.mergeDiscovered(_discoveredDevices.value, device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            emitEvent(FibionFlashEvent.Error("Scan failed: ${FibionParsers.scanFailureMessage(errorCode)}"))
        }
    }

    // --- Public API: Scanning ---

    @SuppressLint("MissingPermission")
    override fun startScan(filterByName: Boolean) {
        if (_isScanning.value) {
            emitEvent(FibionFlashEvent.Debug("Scan already in progress"))
            return
        }
        this.filterByName = filterByName

        if (bluetoothAdapter?.isEnabled != true) {
            emitEvent(FibionFlashEvent.Error("Bluetooth is disabled"))
            return
        }

        val scanner = bleScanner
        if (scanner == null) {
            emitEvent(FibionFlashEvent.Error("Bluetooth LE Scanner not available"))
            return
        }

        _discoveredDevices.value = emptyList()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, scanSettings, scanCallback)
            _isScanning.value = true
            emitEvent(FibionFlashEvent.ScanStarted())
        } catch (e: SecurityException) {
            emitEvent(FibionFlashEvent.Error("Permission denied: ${e.message}"))
        } catch (e: Exception) {
            emitEvent(FibionFlashEvent.Error("Scan failed: ${e.message}"))
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        if (!_isScanning.value) return

        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            emitEvent(FibionFlashEvent.Error("Permission denied: ${e.message}"))
        } catch (e: IllegalStateException) {
            emitEvent(FibionFlashEvent.Debug("Could not stop scan: ${e.message}"))
        }
        _isScanning.value = false
        emitEvent(FibionFlashEvent.ScanStopped())
    }

    // --- Public API: Connection ---

    override fun connect(device: BleDevice) {
        connectInternal(device, fromAutoReconnect = false)
    }

    private fun connectInternal(device: BleDevice, fromAutoReconnect: Boolean) {
        stopScan()
        // Do not cancel the auto-reconnect job when this is invoked from within it —
        // that would cancel the enclosing coroutine and prevent further retry attempts.
        if (!fromAutoReconnect) {
            autoReconnectJob?.cancel()
            autoReconnectJob = null
            userRequestedDisconnect = false
        }

        _batteryLevel.value = null
        _connectionState.value = ConnectionState.CONNECTING
        _connectedDevice.value = device
        emitEvent(FibionFlashEvent.Connecting(device))

        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (_connectionState.value == ConnectionState.CONNECTING) {
                _connectionState.value = ConnectionState.ERROR
                emitEvent(FibionFlashEvent.ConnectionTimeout(device.displayName))
                safeMdsDisconnect(device.address, "timeout cleanup")
                _connectedDevice.value = null
                connectedSerial = null
            }
        }

        mds.connect(device.address, object : MdsConnectionListener {
            override fun onConnect(address: String) {
                emitEvent(FibionFlashEvent.Debug("MDS transport connected: $address"))
            }

            override fun onConnectionComplete(address: String, serial: String) {
                connectionTimeoutJob?.cancel()
                connectedSerial = serial
                _connectionState.value = ConnectionState.CONNECTED
                _deviceSerial.value = serial
                emitEvent(FibionFlashEvent.Connected(device, serial))

                readDeviceInfo()

                batteryPollingJob?.cancel()
                batteryPollingJob = scope.launch {
                    while (isActive) {
                        delay(BATTERY_POLL_INTERVAL_MS)
                        if (_connectionState.value == ConnectionState.CONNECTED) {
                            readBatteryLevel()
                        }
                    }
                }
            }

            override fun onError(e: MdsException) {
                connectionTimeoutJob?.cancel()
                _connectionState.value = ConnectionState.ERROR
                _connectedDevice.value = null
                connectedSerial = null
                emitEvent(FibionFlashEvent.Error("Connection error: ${e.message}"))
            }

            override fun onDisconnect(address: String) {
                connectionTimeoutJob?.cancel()
                batteryPollingJob?.cancel()
                batteryPollingJob = null
                val wasConnected = _connectionState.value == ConnectionState.CONNECTED
                resetConnectionState()
                unsubscribeAll()

                if (wasConnected) {
                    emitEvent(FibionFlashEvent.Disconnected())
                    if (!userRequestedDisconnect && bluetoothAdapter?.isEnabled == true) {
                        scheduleAutoReconnect(device)
                    }
                }
            }
        })
    }

    override fun disconnect() {
        userRequestedDisconnect = true
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        batteryPollingJob?.cancel()
        batteryPollingJob = null
        unsubscribeAll()
        _connectedDevice.value?.let { device ->
            safeMdsDisconnect(device.address, "disconnect")
        }
    }

    // --- Public API: Data subscriptions ---

    override fun subscribeHeartRate() {
        subscribeMds("/Meas/HR") { data ->
            val parsed = FibionParsers.parseHeartRateNotification(data)
            parsed.heartRate?.let { hr ->
                _heartRate.value = hr
                _heartRateSampleFlow.tryEmit(hr.toFloat())
                emitEvent(FibionFlashEvent.HeartRateReceived(hr))
            }
            parsed.rrIntervals.forEach { rr ->
                if (!_rrIntervalSampleFlow.tryEmit(rr.toFloat())) {
                    emitEvent(FibionFlashEvent.Debug("RR buffer full — sample dropped"))
                }
            }
        }
    }

    override fun subscribeEcg(sampleRate: Int) {
        // Use millivolt endpoint (/mV) on firmware >= 2.3 for calibrated clinical-grade values.
        val useMv = _deviceInfo.value?.let { FibionParsers.supportsMillivoltEcg(it.swVersion) } == true
        val path = if (useMv) "/Meas/ECG/$sampleRate/mV" else "/Meas/ECG/$sampleRate"
        activeEcgPath = path

        subscribeMds(path) { data ->
            FibionParsers.parseEcgSamples(data).forEach { sample ->
                if (!_ecgSampleFlow.tryEmit(sample)) {
                    emitEvent(FibionFlashEvent.Debug("ECG buffer full — sample dropped"))
                }
            }
        }
    }

    /**
     * Shared boilerplate for MDS subscriptions. Validates the connection, deduplicates,
     * registers the subscription, forwards data to [onData], and cleans up on error.
     */
    private fun subscribeMds(path: String, onData: (String) -> Unit) {
        val serial = connectedSerial ?: run {
            emitEvent(FibionFlashEvent.Error("Not connected"))
            return
        }
        val uri = "$serial$path"

        if (activeSubscriptions.containsKey(uri)) {
            emitEvent(FibionFlashEvent.Debug("Already subscribed to $path"))
            return
        }

        val sub = mds.subscribe(
            Mds.URI_EVENTLISTENER,
            "{\"Uri\": \"$uri\"}",
            object : MdsNotificationListener {
                override fun onNotification(data: String) {
                    try {
                        onData(data)
                    } catch (e: Exception) {
                        emitEvent(FibionFlashEvent.Debug("$path notification error: ${e.message}"))
                    }
                }

                override fun onError(e: MdsException) {
                    activeSubscriptions.remove(uri)
                    if (path == activeEcgPath) activeEcgPath = null
                    emitEvent(FibionFlashEvent.SubscriptionError(path, e.message ?: "Unknown"))
                }
            }
        )
        activeSubscriptions[uri] = sub
        emitEvent(FibionFlashEvent.SubscriptionStarted(path))
    }

    override fun unsubscribeAll() {
        activeSubscriptions.values.forEach { sub ->
            try {
                sub.unsubscribe()
            } catch (_: Exception) { }
        }
        activeSubscriptions.clear()
        activeEcgPath = null
        clearSensorData()
    }

    override fun unsubscribeHeartRate() {
        val serial = connectedSerial ?: return
        val uri = "$serial/Meas/HR"
        activeSubscriptions.remove(uri)?.let { sub ->
            try { sub.unsubscribe() } catch (_: Exception) {}
        }
        _heartRate.value = null
        emitEvent(FibionFlashEvent.Debug("Unsubscribed from /Meas/HR"))
    }

    override fun unsubscribeEcg() {
        val serial = connectedSerial ?: return
        val ecgPath = activeEcgPath ?: return
        val uri = "$serial$ecgPath"
        activeSubscriptions.remove(uri)?.let { sub ->
            try { sub.unsubscribe() } catch (_: Exception) {}
        }
        activeEcgPath = null
        emitEvent(FibionFlashEvent.Debug("Unsubscribed from $ecgPath"))
    }

    // --- Public API: Device info ---

    override fun readBatteryLevel() {
        val serial = connectedSerial ?: run {
            emitEvent(FibionFlashEvent.Error("Not connected"))
            return
        }

        mds.get(
            "suunto://$serial/System/Energy/Level",
            null,
            object : MdsResponseListener {
                override fun onSuccess(data: String, header: MdsHeader) {
                    val level = FibionParsers.parseBatteryLevel(data)
                    if (level != null) {
                        _batteryLevel.value = level
                        _batteryLastUpdated.value = System.currentTimeMillis()
                        emitEvent(FibionFlashEvent.BatteryLevelRead(level))
                    } else {
                        emitEvent(FibionFlashEvent.Debug("Battery parse error | raw: $data"))
                    }
                }

                override fun onError(e: MdsException) {
                    emitEvent(FibionFlashEvent.Error("Battery read failed: ${e.message}"))
                }
            }
        )
    }

    override fun readDeviceInfo() {
        val serial = connectedSerial ?: run {
            emitEvent(FibionFlashEvent.Error("Not connected"))
            return
        }

        mds.get(
            "suunto://$serial/Info",
            null,
            object : MdsResponseListener {
                override fun onSuccess(data: String, header: MdsHeader) {
                    val info = FibionParsers.parseDeviceInfo(data, serial)
                    if (info != null) {
                        _deviceInfo.value = info
                        emitEvent(FibionFlashEvent.DeviceInfoReceived(serial, info.productName))
                    } else {
                        emitEvent(FibionFlashEvent.Debug("Info parse error | raw: $data"))
                    }
                }

                override fun onError(e: MdsException) {
                    emitEvent(FibionFlashEvent.Error("Device info read failed: ${e.message}"))
                }
            }
        )

        readBatteryLevel()
    }

    override fun cleanup() {
        connectionTimeoutJob?.cancel()
        batteryPollingJob?.cancel()
        batteryPollingJob = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        stopScan()
        unsubscribeAll()
        _connectedDevice.value?.let { device ->
            safeMdsDisconnect(device.address, "cleanup")
        }
        resetConnectionState()
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered or already unregistered.
        }
        scope.cancel()
    }

    // --- Private helpers ---

    /**
     * Reset every StateFlow that represents an active connection: status, device, serial,
     * device info, battery, and live sensor data. Called from any path that tears down the
     * connection (normal disconnect, BT disabled, cleanup).
     */
    private fun resetConnectionState() {
        connectedSerial = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDevice.value = null
        _deviceSerial.value = null
        _deviceInfo.value = null
        _batteryLevel.value = null
        _batteryLastUpdated.value = null
        clearSensorData()
    }

    private fun safeMdsDisconnect(address: String, ctx: String) {
        try {
            mds.disconnect(address)
        } catch (e: Exception) {
            emitEvent(FibionFlashEvent.Debug("MDS disconnect ($ctx) error: ${e.message}"))
        }
    }

    private fun handleBluetoothDisabled() {
        connectionTimeoutJob?.cancel()
        batteryPollingJob?.cancel()
        batteryPollingJob = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        if (_isScanning.value) {
            stopScan()
        }
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING
        ) {
            _connectedDevice.value?.let { device ->
                safeMdsDisconnect(device.address, "BT disabled")
            }
            resetConnectionState()
            emitEvent(FibionFlashEvent.Disconnected("Bluetooth disabled"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun parseScannedDevice(result: ScanResult): BleDevice {
        val scanRecord = result.scanRecord
        val advertisementData = mutableMapOf<String, String>()

        val deviceName = scanRecord?.deviceName ?: result.device.name

        scanRecord?.let { record ->
            record.txPowerLevel.takeIf { it != Int.MIN_VALUE }?.let {
                advertisementData["TX Power"] = "$it dBm"
            }
            record.serviceUuids?.let { uuids ->
                if (uuids.isNotEmpty()) {
                    advertisementData["Service UUIDs"] = uuids.joinToString(", ") {
                        it.uuid.toString().substring(4, 8).uppercase()
                    }
                }
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
                true
            }
        )
    }

    private fun clearSensorData() {
        _heartRate.value = null
    }

    private fun emitEvent(event: FibionFlashEvent) {
        _events.tryEmit(event)
    }

    /**
     * Schedule auto-reconnection after unexpected disconnect.
     * Attempts up to [MAX_RECONNECT_ATTEMPTS] times with [RECONNECT_DELAY_MS] between attempts.
     */
    private fun scheduleAutoReconnect(device: BleDevice) {
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                delay(RECONNECT_DELAY_MS)
                if (!isActive || userRequestedDisconnect || bluetoothAdapter?.isEnabled != true) return@launch
                emitEvent(FibionFlashEvent.Debug("Auto-reconnect attempt $attempt/$MAX_RECONNECT_ATTEMPTS"))
                connectInternal(device, fromAutoReconnect = true)
                delay(CONNECTION_TIMEOUT_MS + 2000)
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    emitEvent(FibionFlashEvent.Debug("Auto-reconnect successful"))
                    return@launch
                }
            }
            emitEvent(FibionFlashEvent.Debug("Auto-reconnect failed after $MAX_RECONNECT_ATTEMPTS attempts"))
        }
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 20_000L
        private const val BATTERY_POLL_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
    }
}
