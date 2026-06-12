package com.vitalwork.app.presentation.screens.sensors.mindfield.respiration

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.sensor.DeviceState
import com.vitalwork.app.data.sensor.audio.LowSignalWarning
import com.vitalwork.app.presentation.components.BioSensorCard
import com.vitalwork.app.presentation.components.ConnectionStatusBadge
import com.vitalwork.app.presentation.components.LowSignalWarningBanner
import com.vitalwork.app.presentation.log.LogEntry
import com.vitalwork.app.presentation.log.LogType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EsenseRespirationScreen(
    onNavigateBack: () -> Unit,
    viewModel: EsenseRespirationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Permission launcher for audio recording
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        viewModel.setPermissionsGranted(isGranted)
    }

    // Check permissions on launch
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setPermissionsGranted(granted)
    }

    // Respiration sensor error dialog
    if (uiState.disconnectReason != null) {
        RespirationErrorDialog(
            reason = uiState.disconnectReason!!,
            onDismiss = { viewModel.clearDisconnectReason() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "eSense Respiration",
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val hPad = if (maxWidth >= 600.dp) 24.dp else 16.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = hPad)
            ) {
                // Scrollable upper section (sensor info + controls)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Sensor Info Card
                    SensorInfoCard(state = uiState.state)

                    // Low signal warning banner
                    if (uiState.lowSignalWarning != LowSignalWarning.NONE) {
                        LowSignalWarningBanner(warningLevel = uiState.lowSignalWarning)
                    }

                    // Permission Card (if not granted)
                    if (!uiState.permissionsGranted) {
                        PermissionRequestCard(
                            onRequestPermissions = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        )
                    }

                    // Sensor Control Card (only if permissions granted)
                    if (uiState.permissionsGranted) {
                        Text(
                            text = "Sensor Control",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        BioSensorCard(
                            sensorName = "Mindfield Respiration",
                            state = uiState.state,
                            rate = uiState.rate,
                            stats = uiState.stats,
                            unit = "RA",
                            onToggle = viewModel::toggleConnection,
                            showStreamData = uiState.showStreamData,
                            onToggleStreamDisplay = viewModel::toggleStreamDisplay
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Fixed-height log section at the bottom
                LogSection(
                    logEntries = uiState.logEntries,
                    onClearLog = viewModel::clearLog,
                    modifier = Modifier
                        .height(220.dp)
                        .padding(bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SensorInfoCard(
    state: DeviceState,
    modifier: Modifier = Modifier
) {
    // Convert DeviceState to ConnectionState for the badge
    val connectionState = when (state) {
        DeviceState.Connected, DeviceState.Streaming -> ConnectionState.CONNECTED
        DeviceState.Connecting -> ConnectionState.CONNECTING
        DeviceState.Error -> ConnectionState.ERROR
        DeviceState.Disconnected -> ConnectionState.DISCONNECTED
    }

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
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "eSense Respiration",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Audio Jack Breathing Sensor",
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
                text = "Microphone permission is required to connect to the eSense Respiration sensor via audio jack.",
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

@Composable
private fun RespirationErrorDialog(
    reason: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = if (reason.contains("No Signal", ignoreCase = true) ||
                    reason.contains("Signal Out of Range", ignoreCase = true) ||
                    reason.contains("Init Failed", ignoreCase = true)
                ) "Respiration Connection Failed"
                else "Respiration Sensor Disconnected"
            )
        },
        text = {
            Text(
                text = respirationErrorMessage(reason),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

private fun respirationErrorMessage(reason: String): String = when {
    reason.contains("No Signal", ignoreCase = true) ->
        "No signal was detected from the sensor. Make sure the eSense Respiration cable is fully plugged into the audio jack and the chest strap is being worn."
    reason.contains("Signal Out of Range", ignoreCase = true) ->
        "The sensor signal is outside the expected range. Check that the chest strap is properly positioned and the cable is fully inserted."
    reason.contains("No Data", ignoreCase = true) ->
        "The sensor stopped sending data. Check that the audio cable is still connected to the device."
    reason.contains("abnormality", ignoreCase = true) ->
        "The audio cable appears to have been disconnected. The device microphone started capturing ambient sound instead of breathing data."
    reason.contains("Init Failed", ignoreCase = true) ->
        "Failed to initialize the sensor. Try disconnecting and reconnecting the audio cable."
    else -> reason
}
