package com.vitalwork.app.presentation.screens.sensors.mindfield.pulse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.bluetooth.BluetoothAdapter
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Security
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.sensor.ble.model.BleDevice
import com.vitalwork.app.data.sensor.ble.model.BleGattService
import com.vitalwork.app.presentation.components.BleDialogState
import com.vitalwork.app.presentation.components.BluetoothDisabledCard
import com.vitalwork.app.presentation.components.ConnectionStatusBadge
import com.vitalwork.app.presentation.components.DialogAction
import com.vitalwork.app.presentation.screens.sensors.components.BleDebugLog
import com.vitalwork.app.presentation.screens.sensors.components.BleDeviceItem
import com.vitalwork.app.presentation.screens.sensors.components.BleServiceExplorer
import com.vitalwork.app.presentation.screens.sensors.components.HeartRateDisplay
import com.vitalwork.app.presentation.screens.sensors.components.RrIntervalCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EsensePulseScreen(
    onNavigateBack: () -> Unit,
    viewModel: EsensePulseViewModel = hiltViewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "eSense Pulse",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        EsensePulseContent(
            viewModel = viewModel,
            modifier = Modifier.padding(paddingValues)
        )
    }
}

@Composable
private fun EsensePulseContent(
    viewModel: EsensePulseViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // BLE Dialogs
    uiState.dialogState?.let { dialogState ->
        BleAlertDialog(
            state = dialogState,
            onDismiss = { viewModel.dismissDialog() },
            onAction = { action ->
                if (action == DialogAction.OpenLocationSettings) {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                viewModel.onDialogAction(action)
            }
        )
    }

    // Permission handling
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        viewModel.setPermissionsGranted(allGranted)
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { /* result handled reactively via BleManager.bluetoothEnabled flow */ }

    // Check permissions on launch
    LaunchedEffect(Unit) {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        viewModel.setPermissionsGranted(allGranted)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Sensor Info Card
        SensorInfoCard(
            connectionState = uiState.connectionState
        )

        // Permission Card (if not granted)
        if (!uiState.permissionsGranted) {
            PermissionRequestCard(
                onRequestPermissions = { permissionLauncher.launch(requiredPermissions) }
            )
        }

        // Bluetooth Disabled Warning
        if (!uiState.bluetoothEnabled) {
            BluetoothDisabledCard(
                onClick = { enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            )
        }

        // Location Disabled Warning
        if (uiState.permissionsGranted && !uiState.locationEnabled) {
            LocationDisabledCard(
                onOpenSettings = {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            )
        }

        // Scan/Connection Section (only if permissions granted and BT enabled)
        if (uiState.permissionsGranted && uiState.bluetoothEnabled) {
            when (uiState.connectionState) {
                ConnectionState.CONNECTED -> {
                    // Connected device info
                    ConnectedDeviceCard(
                        device = uiState.connectedDevice,
                        batteryLevel = uiState.batteryLevel,
                        onDisconnect = { viewModel.disconnect() }
                    )

                    // Tabs for Heart Rate and Services
                    ConnectedDeviceTabs(
                        heartRate = uiState.heartRate,
                        isMonitoring = uiState.isMonitoringHeartRate,
                        onStartMonitoring = { viewModel.startHeartRateMonitoring() },
                        onStopMonitoring = { viewModel.stopHeartRateMonitoring() },
                        services = uiState.discoveredServices,
                        batteryLevel = uiState.batteryLevel,
                        latestRrInterval = uiState.latestRrInterval,
                        rrIntervalHistory = uiState.rrIntervalHistory
                    )
                }

                ConnectionState.CONNECTING -> {
                    ConnectingCard()
                }

                else -> {
                    // Scan controls
                    ScanControlCard(
                        isScanning = uiState.isScanning,
                        onToggleScan = { viewModel.toggleScan() }
                    )

                    // Device list
                    if (uiState.discoveredDevices.isNotEmpty() || uiState.isScanning) {
                        DeviceListSection(
                            devices = uiState.discoveredDevices,
                            isScanning = uiState.isScanning,
                            onDeviceClick = { device -> viewModel.connectToDevice(device) }
                        )
                    }
                }
            }
        }

        // Debug Log (always visible)
        BleDebugLog(
            logEntries = uiState.logEntries,
            onClearLog = { viewModel.clearLog() }
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SensorInfoCard(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "eSense Pulse",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "BLE Heart Rate Monitor",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ConnectionStatusBadge(state = connectionState)
        }
    }
}

@Composable
private fun PermissionRequestCard(
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Permissions Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Text(
                text = "Bluetooth and Location permissions are required to scan for and connect to BLE devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant Permissions")
            }
        }
    }
}

@Composable
private fun LocationDisabledCard(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Location Services Disabled",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Location Services must be enabled for BLE scanning to work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onOpenSettings) {
                Text(
                    text = "Settings",
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun ScanControlCard(
    isScanning: Boolean,
    onToggleScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Device Discovery",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = "Scan for nearby BLE devices to connect.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onToggleScan,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isScanning) "Stop Scanning" else "Scan for Devices")
            }
        }
    }
}

@Composable
private fun DeviceListSection(
    devices: List<BleDevice>,
    isScanning: Boolean,
    onDeviceClick: (BleDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Discovered Devices (${devices.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )

            if (isScanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Scanning...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (devices.isNotEmpty()) {
            Text(
                text = "Tap a device below to connect",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (devices.isEmpty() && isScanning) {
            Text(
                text = "Searching for nearby devices...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            devices.forEach { device ->
                BleDeviceItem(
                    device = device,
                    onClick = { onDeviceClick(device) }
                )
            }
        }
    }
}

@Composable
private fun ConnectedDeviceCard(
    device: BleDevice?,
    batteryLevel: Int?,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                // Battery level indicator
                batteryLevel?.let { level ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BatteryFull,
                            contentDescription = "Battery",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$level%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            device?.let {
                Text(
                    text = it.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = it.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }

            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun ConnectedDeviceTabs(
    heartRate: Int?,
    isMonitoring: Boolean,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    services: List<BleGattService>,
    batteryLevel: Int? = null,
    latestRrInterval: Int? = null,
    rrIntervalHistory: List<Float> = emptyList(),
    modifier: Modifier = Modifier
) {
    val tabs = listOf("Heart Rate", "Services")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                    text = { Text(title) }
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    HeartRateDisplay(
                        heartRate = heartRate,
                        isMonitoring = isMonitoring,
                        onStartMonitoring = onStartMonitoring,
                        onStopMonitoring = onStopMonitoring,
                        batteryLevel = batteryLevel
                    )
                    RrIntervalCard(
                        latestRrInterval = latestRrInterval,
                        rrHistory = rrIntervalHistory,
                        waitingMessage = "Waiting for heart rate monitoring\u2026"
                    )
                }
                1 -> Box(modifier = Modifier.padding(top = 16.dp)) {
                    BleServiceExplorer(services = services)
                }
            }
        }
    }
}

@Composable
private fun ConnectingCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Connecting...",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BleAlertDialog(
    state: BleDialogState,
    onDismiss: () -> Unit,
    onAction: (DialogAction) -> Unit
) {
    when (state) {
        is BleDialogState.LocationServicesRequired -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Location Services Required") },
                text = { Text("BLE scanning requires Location Services to be enabled on this device.") },
                confirmButton = {
                    TextButton(onClick = { onAction(DialogAction.OpenLocationSettings) }) {
                        Text("Open Settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            )
        }

        is BleDialogState.ScanTimeout -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("No Devices Found") },
                text = {
                    Text(
                        "No eSense devices found yet. Please check:\n\n" +
                            "\u2022 The device is powered on\n" +
                            "\u2022 The device is nearby (within 2 meters)\n" +
                            "\u2022 The device is not connected to another phone/tablet\n\n" +
                            "Scanning will continue in the background."
                    )
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("OK")
                    }
                }
            )
        }

        is BleDialogState.ConnectionTimeout -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Connection Failed") },
                text = { Text("Could not connect to ${state.deviceName}. The connection attempt timed out.") },
                confirmButton = {
                    TextButton(onClick = { onAction(DialogAction.RetryConnection) }) {
                        Text("Retry")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            )
        }

        is BleDialogState.LowBattery -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Low Battery") },
                text = { Text("The battery level is at ${state.percent}%. Consider replacing it in the near future.") },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("OK")
                    }
                }
            )
        }

        is BleDialogState.UnexpectedDisconnection -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Device Disconnected") },
                text = { Text("Connection to ${state.deviceName} was lost: ${state.reason}") },
                confirmButton = {
                    TextButton(onClick = { onAction(DialogAction.Reconnect) }) {
                        Text("Reconnect")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("OK")
                    }
                }
            )
        }
    }
}
