package com.biometrix.operator.presentation.screens.vr

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.vr.model.DiscoveredVrDevice
import com.biometrix.operator.presentation.log.LogEntry
import com.biometrix.operator.presentation.log.LogType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VRConnectionScreen(
    onNavigateBack: (() -> Unit)? = null,
    viewModel: VRConnectionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val wifiSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* NetworkCallback in MdnsDiscoveryService handles auto-restart */ }

    @Suppress("DEPRECATION")
    val wifiSettingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Intent(Settings.Panel.ACTION_WIFI)
    } else {
        Intent(Settings.ACTION_WIFI_SETTINGS)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "VR Control",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = {
                    ConnectionStatusIndicator(
                        state = uiState.connectionState,
                        isReconnecting = uiState.isReconnecting
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val hPad = if (maxWidth >= 600.dp) 24.dp else 16.dp

            Column(modifier = Modifier.fillMaxSize()) {
                ConnectionSection(
                    connectionState = uiState.connectionState,
                    discoveredDevices = uiState.discoveredDevices,
                    isDiscovering = uiState.isDiscovering,
                    selectedDevice = uiState.selectedDevice,
                    isWifiAvailable = uiState.isWifiAvailable,
                    onSelectDevice = viewModel::selectAndConnect,
                    onRescan = viewModel::rescan,
                    onDisconnect = viewModel::disconnect,
                    onOpenWifiSettings = { wifiSettingsLauncher.launch(wifiSettingsIntent) }
                )

                CommandsSection(
                    enabled = uiState.connectionState == ConnectionState.CONNECTED,
                    sceneName = uiState.sceneName,
                    triggerTarget = uiState.triggerTarget,
                    triggerEventName = uiState.triggerEventName,
                    onSceneNameChange = viewModel::updateSceneName,
                    onTriggerTargetChange = viewModel::updateTriggerTarget,
                    onTriggerEventNameChange = viewModel::updateTriggerEventName,
                    onReloadScene = viewModel::sendReloadSceneCommand,
                    onLoadScene = viewModel::sendLoadSceneCommand,
                    onTriggerEvent = viewModel::sendTriggerEventCommand
                )

                LogSection(
                    logEntries = uiState.logEntries,
                    onClearLog = viewModel::clearLog,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusIndicator(
    state: ConnectionState,
    isReconnecting: Boolean = false
) {
    val color by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.DISCONNECTED -> Color.Gray
            ConnectionState.CONNECTING -> Color(0xFFFFA000)
            ConnectionState.CONNECTED -> Color(0xFF4CAF50)
            ConnectionState.ERROR -> Color(0xFFF44336)
        },
        animationSpec = tween(300),
        label = "statusColor"
    )

    val statusText = when {
        isReconnecting -> "Reconnecting..."
        else -> when (state) {
            ConnectionState.DISCONNECTED -> "Disconnected"
            ConnectionState.CONNECTING -> "Connecting"
            ConnectionState.CONNECTED -> "Connected"
            ConnectionState.ERROR -> "Error"
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp)
    ) {
        if (state == ConnectionState.CONNECTING) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = color
            )
        } else {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSection(
    connectionState: ConnectionState,
    discoveredDevices: List<DiscoveredVrDevice>,
    isDiscovering: Boolean,
    selectedDevice: DiscoveredVrDevice?,
    isWifiAvailable: Boolean,
    onSelectDevice: (DiscoveredVrDevice) -> Unit,
    onRescan: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenWifiSettings: () -> Unit
) {
    val isConnectedOrConnecting = connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.CONNECTING

    if (!isWifiAvailable && !isConnectedOrConnecting) {
        Card(
            onClick = onOpenWifiSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.WifiOff,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Wi-Fi Disabled",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "Tap here to enable Wi-Fi.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "VR Headset",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isConnectedOrConnecting) {
                ConnectedState(
                    device = selectedDevice,
                    connectionState = connectionState,
                    onDisconnect = onDisconnect
                )
            } else {
                ScanningState(
                    discoveredDevices = discoveredDevices,
                    isDiscovering = isDiscovering,
                    onSelectDevice = onSelectDevice,
                    onRescan = onRescan
                )
            }
        }
    }
}

@Composable
private fun ConnectedState(
    device: DiscoveredVrDevice?,
    connectionState: ConnectionState,
    onDisconnect: () -> Unit
) {
    if (device != null) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${device.host}:${device.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    Button(
        onClick = onDisconnect,
        modifier = Modifier.fillMaxWidth(),
        enabled = connectionState != ConnectionState.CONNECTING,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error
        )
    ) {
        Icon(
            imageVector = Icons.Default.LinkOff,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = "DISCONNECT", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ScanningState(
    discoveredDevices: List<DiscoveredVrDevice>,
    isDiscovering: Boolean,
    onSelectDevice: (DiscoveredVrDevice) -> Unit,
    onRescan: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (isDiscovering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Scanning for VR devices...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "No devices found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        FilledTonalButton(
            onClick = onRescan,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Rescan")
        }
    }

    if (discoveredDevices.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        discoveredDevices.forEachIndexed { index, device ->
            DiscoveredDeviceCard(
                device = device,
                onClick = { onSelectDevice(device) }
            )
            if (index < discoveredDevices.lastIndex) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveredDeviceCard(
    device: DiscoveredVrDevice,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${device.host}:${device.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = "Connect",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CommandsSection(
    enabled: Boolean,
    sceneName: String,
    triggerTarget: String,
    triggerEventName: String,
    onSceneNameChange: (String) -> Unit,
    onTriggerTargetChange: (String) -> Unit,
    onTriggerEventNameChange: (String) -> Unit,
    onReloadScene: () -> Unit,
    onLoadScene: () -> Unit,
    onTriggerEvent: () -> Unit
) {
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.5f,
        label = "commandsAlpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Commands",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Reload Scene
            FilledTonalButton(
                onClick = onReloadScene,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Reload Scene")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Load Scene
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = sceneName,
                    onValueChange = onSceneNameChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Scene Name") },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = onLoadScene,
                    enabled = enabled && sceneName.isNotBlank(),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Load")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Trigger Event
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = triggerTarget,
                    onValueChange = onTriggerTargetChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Target") },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = triggerEventName,
                    onValueChange = onTriggerEventNameChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Event") },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = onTriggerEvent,
                    enabled = enabled && triggerTarget.isNotBlank(),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Trigger")
                }
            }
        }
    }
}

@Composable
private fun LogSection(
    logEntries: List<LogEntry>,
    onClearLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Event Log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                if (logEntries.isNotEmpty()) {
                    TextButton(onClick = onClearLog) {
                        Text("Clear")
                    }
                }
            }

            HorizontalDivider()

            if (logEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No events yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(logEntries) { entry ->
                        LogEntryItem(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryItem(entry: LogEntry) {
    val icon: ImageVector
    val color: Color
    when (entry.type) {
        LogType.SENT -> {
            icon = Icons.AutoMirrored.Outlined.ArrowForward
            color = MaterialTheme.colorScheme.primary
        }
        LogType.RECEIVED -> {
            icon = Icons.AutoMirrored.Filled.ArrowBack
            color = MaterialTheme.colorScheme.secondary
        }
        LogType.SUCCESS -> {
            icon = Icons.Outlined.CheckCircle
            color = Color(0xFF4CAF50)
        }
        LogType.ERROR -> {
            icon = Icons.Outlined.Error
            color = Color(0xFFF44336)
        }
        LogType.INFO -> {
            icon = Icons.Outlined.Info
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
        LogType.NOTIFICATION -> {
            icon = Icons.Outlined.Notifications
            color = Color(0xFF9C27B0)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.timestamp,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = color
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
