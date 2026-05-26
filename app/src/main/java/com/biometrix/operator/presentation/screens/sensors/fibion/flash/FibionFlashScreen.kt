package com.biometrix.operator.presentation.screens.sensors.fibion.flash

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.ui.graphics.vector.ImageVector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import com.biometrix.operator.data.sensor.fibion.model.FibionFlashDeviceInfo
import com.biometrix.operator.presentation.components.BluetoothDisabledCard
import com.biometrix.operator.presentation.components.ConnectionStatusBadge
import com.biometrix.operator.presentation.screens.sensors.components.BleDeviceItem
import com.biometrix.operator.presentation.screens.sensors.components.RrIntervalCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FibionFlashScreen(
    onNavigateBack: () -> Unit,
    viewModel: FibionFlashViewModel = hiltViewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Fibion Flash",
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
        FibionFlashContent(
            viewModel = viewModel,
            modifier = Modifier.padding(paddingValues)
        )
    }
}

@Composable
private fun FibionFlashContent(
    viewModel: FibionFlashViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Dialogs
    uiState.dialogState?.let { dialogState ->
        FfAlertDialog(
            state = dialogState,
            onDismiss = { viewModel.dismissDialog() },
            onAction = { action ->
                when (action) {
                    FfDialogAction.OpenLocationSettings -> {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        viewModel.dismissDialog()
                    }
                    FfDialogAction.RetryConnection -> viewModel.onRetryConnection()
                    FfDialogAction.Dismiss -> viewModel.dismissDialog()
                }
            }
        )
    }

    // Permission handling
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
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
    ) { /* result handled reactively via connectionRepository.bluetoothEnabled flow */ }

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
        FfSensorInfoCard(connectionState = uiState.connectionState)

        // Permission Card
        if (!uiState.permissionsGranted) {
            FfPermissionRequestCard(
                onRequestPermissions = { permissionLauncher.launch(requiredPermissions) }
            )
        }

        // Bluetooth Disabled Warning
        if (!uiState.bluetoothEnabled) {
            BluetoothDisabledCard(
                onClick = { enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            )
        }

        // Scan/Connection Section
        if (uiState.permissionsGranted && uiState.bluetoothEnabled) {
            when (uiState.connectionState) {
                ConnectionState.CONNECTED -> {
                    // Connected device info
                    FfConnectedDeviceCard(
                        device = uiState.connectedDevice,
                        deviceSerial = uiState.deviceSerial,
                        batteryLevel = uiState.batteryLevel,
                        batteryLastUpdated = uiState.batteryLastUpdated,
                        onSubscribeAll = { viewModel.subscribeAll() },
                        onDisconnect = { viewModel.disconnect() },
                        onReadBattery = { viewModel.readBatteryLevel() }
                    )

                    // Live sensor data cards
                    FfHeartRateCard(
                        heartRate = uiState.heartRate,
                        onSubscribe = { viewModel.subscribeHeartRate() }
                    )

                    RrIntervalCard(
                        latestRrInterval = uiState.latestRrInterval,
                        rrHistory = uiState.rrIntervalHistory
                    )

                    FfEcgCard(isEcgSubscribed = uiState.isEcgSubscribed)

                    // Unsubscribe All button
                    OutlinedButton(
                        onClick = { viewModel.unsubscribeAll() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Unsubscribe All")
                    }
                }

                ConnectionState.CONNECTING -> {
                    FfConnectingCard()
                }

                else -> {
                    // Scan controls
                    FfScanControlCard(
                        isScanning = uiState.isScanning,
                        onToggleScan = { viewModel.toggleScan() }
                    )

                    // Device list
                    if (uiState.discoveredDevices.isNotEmpty() || uiState.isScanning) {
                        FfDeviceListSection(
                            devices = uiState.discoveredDevices,
                            isScanning = uiState.isScanning,
                            onDeviceClick = { device -> viewModel.connectToDevice(device) }
                        )
                    }
                }
            }
        }

        // Device Info Card (shown when available, even after disconnect if cached)
        uiState.deviceInfo?.let { info ->
            FfDeviceInfoCard(info = info)
        }

        // Debug Log
        FfDebugLog(
            logEntries = uiState.logEntries,
            onClearLog = { viewModel.clearLog() }
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// region Sensor Info Card

@Composable
private fun FfSensorInfoCard(
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
                imageVector = Icons.Default.Watch,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Fibion Flash",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Wearable Sensor (BLE)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ConnectionStatusBadge(state = connectionState)
        }
    }
}

// endregion

// region Permission & Bluetooth Cards

@Composable
private fun FfPermissionRequestCard(
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
                text = "Bluetooth permissions are required to scan for and connect to BLE devices.",
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


// endregion

// region Connected Device Card

@Composable
private fun FfConnectedDeviceCard(
    device: BleDevice?,
    deviceSerial: String?,
    batteryLevel: Int?,
    batteryLastUpdated: Long?,
    onSubscribeAll: () -> Unit,
    onDisconnect: () -> Unit,
    onReadBattery: () -> Unit,
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

                batteryLevel?.let { level ->
                    val isLow = level <= 30
                    val batteryColor = if (isLow) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onPrimaryContainer
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = fibionBatteryIcon(level),
                            contentDescription = "Battery $level%",
                            modifier = Modifier.size(20.dp),
                            tint = batteryColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$level%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = batteryColor
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

            deviceSerial?.let { serial ->
                Text(
                    text = "Serial: $serial",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSubscribeAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Subscribe All")
                }

                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disconnect")
                }
            }

            val lastReadText = remember(batteryLastUpdated) {
                batteryLastUpdated?.let {
                    "Last read: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))}"
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TextButton(
                    onClick = onReadBattery,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (batteryLevel == null) "Read Battery Level" else "Refresh Battery")
                }
                lastReadText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}

// endregion

// region Live Sensor Data Cards

@Composable
private fun FfHeartRateCard(
    heartRate: Int?,
    onSubscribe: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Heart Rate",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }

                if (heartRate == null) {
                    TextButton(onClick = onSubscribe) {
                        Text("Subscribe")
                    }
                }
            }

            if (heartRate != null) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = heartRate.toString(),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = "BPM",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            } else {
                Text(
                    text = "Not subscribed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun FfEcgCard(
    isEcgSubscribed: Boolean,
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            var showEcgInfoDialog by remember { mutableStateOf(false) }
            if (showEcgInfoDialog) {
                AlertDialog(
                    onDismissRequest = { showEcgInfoDialog = false },
                    title = { Text("What is ECG?") },
                    text = {
                        Text(
                            "ECG records the raw electrical heart signal at 125 Hz. " +
                            "Enables HRV (Heart Rate Variability) analysis post-session \u2014 " +
                            "a direct measure of stress and autonomic balance, " +
                            "significantly more sensitive than average heart rate."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showEcgInfoDialog = false }) {
                            Text("Got it")
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "ECG",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    IconButton(
                        onClick = { showEcgInfoDialog = true },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "What is ECG?",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Text(
                    text = if (isEcgSubscribed) "Recording" else "Not subscribed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isEcgSubscribed)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                )
            }
        }
    }
}

// endregion

// region Device Info Card

@Composable
private fun FfDeviceInfoCard(
    info: FibionFlashDeviceInfo,
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Device Info",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
            )

            FfInfoRow(label = "Product", value = info.productName)
            FfInfoRow(label = "Serial", value = info.serial)
            FfInfoRow(label = "SW Version", value = info.swVersion)
            FfInfoRow(label = "HW Version", value = info.hwVersion)
        }
    }
}

@Composable
private fun FfInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

// endregion

// region Scan & Connect

@Composable
private fun FfScanControlCard(
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
                text = "Scan for nearby BLE devices.",
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
private fun FfDeviceListSection(
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
private fun FfConnectingCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
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

            Text(
                text = "Establishing connection via Movesense MDS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// endregion

// region Dialogs

private sealed class FfDialogAction {
    data object OpenLocationSettings : FfDialogAction()
    data object RetryConnection : FfDialogAction()
    data object Dismiss : FfDialogAction()
}

@Composable
private fun FfAlertDialog(
    state: FfDialogState,
    onDismiss: () -> Unit,
    onAction: (FfDialogAction) -> Unit
) {
    when (state) {
        is FfDialogState.LocationServicesRequired -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Location Services Required") },
                text = { Text("BLE scanning requires Location Services to be enabled on this device.") },
                confirmButton = {
                    TextButton(onClick = { onAction(FfDialogAction.OpenLocationSettings) }) {
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

        is FfDialogState.ScanTimeout -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("No Devices Found") },
                text = {
                    Text(
                        "No Fibion Flash devices found yet.\n\n" +
                            "Please check:\n" +
                            "\u2022 The device is powered on and nearby\n" +
                            "\u2022 The device is not connected to another phone/tablet\n" +
                            "\u2022 Bluetooth is enabled\n\n" +
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

        is FfDialogState.ConnectionTimeout -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Connection Failed") },
                text = { Text("Could not connect to ${state.deviceName}. The connection attempt timed out.") },
                confirmButton = {
                    TextButton(onClick = { onAction(FfDialogAction.RetryConnection) }) {
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

        is FfDialogState.LowBattery -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Low Battery") },
                text = { Text("Device battery is at ${state.percent}%. Consider replacing the battery soon.") },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("OK")
                    }
                }
            )
        }

        is FfDialogState.UnexpectedDisconnection -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Device Disconnected") },
                text = { Text("Connection lost: ${state.reason}") },
                confirmButton = {
                    TextButton(onClick = { onAction(FfDialogAction.RetryConnection) }) {
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

// endregion

// region Debug Log

@Composable
private fun FfDebugLog(
    logEntries: List<FfLogEntry>,
    onClearLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logEntries.firstOrNull()) {
        if (logEntries.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Debug Log (${logEntries.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )

            IconButton(
                onClick = onClearLog,
                enabled = logEntries.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear log",
                    tint = if (logEntries.isNotEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 0.dp
        ) {
            if (logEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No log entries yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(
                        items = logEntries,
                        key = { index, entry -> "$index-${entry.timestamp}" }
                    ) { _, entry ->
                        FfLogEntryItem(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun FfLogEntryItem(
    entry: FfLogEntry,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (entry.isError) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                }
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = entry.timestamp,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (entry.isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
    }
}

private fun fibionBatteryIcon(level: Int): ImageVector = when {
    level >= 95 -> Icons.Default.BatteryFull
    level >= 80 -> Icons.Default.Battery6Bar
    level >= 65 -> Icons.Default.Battery5Bar
    level >= 50 -> Icons.Default.Battery4Bar
    level >= 35 -> Icons.Default.Battery3Bar
    level >= 20 -> Icons.Default.Battery2Bar
    level >= 10 -> Icons.Default.Battery1Bar
    else        -> Icons.Default.Battery0Bar
}

// endregion
