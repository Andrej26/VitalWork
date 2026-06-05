package com.biometrix.operator.presentation.screens.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import android.bluetooth.BluetoothAdapter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Vrpano
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.biometrix.operator.data.db.SessionStatus
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.recording.model.DataRecordingState
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import com.biometrix.operator.data.system.SessionPrerequisite
import com.biometrix.operator.presentation.components.ReadinessWarningCard
import com.biometrix.operator.presentation.components.onPermissionDenied
import com.biometrix.operator.service.BatteryOptimizationHelper
import com.biometrix.operator.service.SessionRecordingService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.biometrix.operator.data.sensor.audio.LowSignalWarning
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.biometrix.operator.presentation.components.BleDialogState
import com.biometrix.operator.presentation.components.ConnectionStatusBadge
import com.biometrix.operator.presentation.components.DialogAction
import com.biometrix.operator.presentation.components.LowSignalWarningBanner
import com.biometrix.operator.presentation.screens.sensors.components.BleDeviceItem
import com.biometrix.operator.presentation.screens.sensors.toConnectionState
import com.biometrix.operator.presentation.screens.sessions.components.DeviceSensorGroup
import com.biometrix.operator.presentation.screens.sessions.components.LiveSensorCard
import com.biometrix.operator.presentation.screens.sessions.components.SessionNotesField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionControlScreen(
    sessionId: Long,
    onNavigateBack: () -> Unit,
    onSessionEnded: (sessionId: Long) -> Unit,
    viewModel: SessionControlViewModel = hiltViewModel()
) {
    // Connection states
    val vrConnectionState by viewModel.vrConnectionState.collectAsState()
    val pulseSensorState by viewModel.bleConnectionState.collectAsState()
    val respirationSensorState by viewModel.respirationState.collectAsState()

    // Live sensor values
    val heartRate by viewModel.heartRate.collectAsState()
    val bleBatteryLevel by viewModel.bleBatteryLevel.collectAsState()
    val respirationRate by viewModel.respirationRate.collectAsState()
    val pulseLatestRr by viewModel.pulseLatestRr.collectAsState()

    // Recording state
    val recordingUiState by viewModel.recordingUiState.collectAsState()

    // Session state
    val session by viewModel.session.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val notesSaveStatus by viewModel.notesSaveStatus.collectAsState()
    val isEndingSession by viewModel.isEndingSession.collectAsState()
    val endSessionResult by viewModel.endSessionResult.collectAsState()

    // BLE scan state
    val showBleScanDialog by viewModel.showBleScanDialog.collectAsState()
    val bleDiscoveredDevices by viewModel.bleDiscoveredDevices.collectAsState()
    val bleIsScanning by viewModel.bleIsScanning.collectAsState()
    val bluetoothEnabled by viewModel.bluetoothEnabled.collectAsState()
    val scanTimeoutReached by viewModel.scanTimeoutReached.collectAsState()
    val bleDialogState by viewModel.bleDialogState.collectAsState()
    val blePermissionsGranted by viewModel.blePermissionsGranted.collectAsState()

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { /* result handled reactively via BleManager.bluetoothEnabled flow */ }

    val requiredBlePermissions = remember {
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

    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.setBlePermissionsGranted(permissions.values.all { it })
    }

    // VR auto-recording cue (set when the Quest's scenario_start triggers recording)
    val vrTriggeredRecording by viewModel.vrTriggeredRecording.collectAsState()

    // Low signal warning
    val respirationLowSignalWarning by viewModel.respirationLowSignalWarning.collectAsState()

    // Respiration disconnect reason (for error dialog)
    val respirationDisconnectReason by viewModel.respirationDisconnectReason.collectAsState()

    // Convert DeviceState to ConnectionState for UI consistency
    val respirationConnectionState = respirationSensorState.toConnectionState()

    val isAnySensorConnected = pulseSensorState == ConnectionState.CONNECTED ||
            respirationConnectionState == ConnectionState.CONNECTED

    val context = LocalContext.current

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialog states
    var showBackDialog by remember { mutableStateOf(false) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var showEndSessionConfirmation by remember { mutableStateOf(false) }


    // Setup auto-save for notes
    LaunchedEffect(Unit) {
        viewModel.setupNotesAutoSave()
    }

    // Keep the process alive (and mic/BLE/network legal) while the session is ACTIVE, so the
    // operator can lock the screen mid-session. Started from this foreground screen; the service
    // self-stops once the session is ended or discarded. Keyed on status because `session` loads
    // asynchronously and re-entry into a pre-existing active session must also start it.
    LaunchedEffect(session?.status) {
        if (session?.status == SessionStatus.ACTIVE) {
            SessionRecordingService.start(context)
        }
    }

    // Check BLE permissions on entry
    LaunchedEffect(Unit) {
        val allGranted = requiredBlePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        viewModel.setBlePermissionsGranted(allGranted)
    }

    // Readiness backup banner: collect missing prerequisites and re-derive on resume (catches a
    // silently revoked permission / battery setting while the operator is mid-session).
    val missingPrerequisites by viewModel.missingPrerequisites.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshReadiness()
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) onPermissionDenied(context, Manifest.permission.POST_NOTIFICATIONS)
        viewModel.refreshReadiness()
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) onPermissionDenied(context, Manifest.permission.RECORD_AUDIO)
        viewModel.refreshReadiness()
    }

    val onReadinessFix: (SessionPrerequisite) -> Unit = { prerequisite ->
        when (prerequisite) {
            SessionPrerequisite.NOTIFICATIONS ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            SessionPrerequisite.MICROPHONE ->
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            SessionPrerequisite.BLUETOOTH ->
                blePermissionLauncher.launch(requiredBlePermissions)
            SessionPrerequisite.BATTERY_OPTIMIZATION ->
                BatteryOptimizationHelper.openExemptionSettings(context)
        }
    }

    // Handle test end result
    LaunchedEffect(endSessionResult) {
        when (val result = endSessionResult) {
            is EndSessionResult.Success -> {
                viewModel.clearEndSessionResult()
                onSessionEnded(result.sessionId)
            }
            is EndSessionResult.Error -> {
                snackbarHostState.showSnackbar(
                    message = result.message,
                    duration = SnackbarDuration.Long
                )
                viewModel.clearEndSessionResult()
            }
            null -> {}
        }
    }

    // Back navigation protection
    BackHandler(enabled = recordingUiState.recordingState != DataRecordingState.IDLE) {
        showBackDialog = true
    }

    // Back confirmation dialog
    if (showBackDialog) {
        AlertDialog(
            onDismissRequest = { showBackDialog = false },
            title = { Text("Session in progress") },
            text = { Text("You have an active session. What would you like to do?") },
            confirmButton = {
                TextButton(onClick = {
                    showBackDialog = false
                    viewModel.endSessionAndSave()
                }) {
                    Text("Save & Exit")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showBackDialog = false
                        showDiscardConfirmation = true
                    }) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showBackDialog = false }) {
                        Text("Continue")
                    }
                }
            }
        )
    }

    // Discard confirmation
    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard session?") },
            text = { Text("This will permanently delete all recorded data for this session. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirmation = false
                    viewModel.discardSession()
                    onNavigateBack()
                }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // End session confirmation
    if (showEndSessionConfirmation) {
        AlertDialog(
            onDismissRequest = { showEndSessionConfirmation = false },
            title = { Text("End session?") },
            text = { Text("Are you sure you want to end this session?") },
            confirmButton = {
                TextButton(onClick = {
                    showEndSessionConfirmation = false
                    viewModel.endSessionAndSave()
                }) {
                    Text("End Session")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndSessionConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }


    // BLE scan dialog
    if (showBleScanDialog) {
        BleScanDialog(
            devices = bleDiscoveredDevices,
            isScanning = bleIsScanning,
            bleConnectionState = pulseSensorState,
            bluetoothEnabled = bluetoothEnabled,
            blePermissionsGranted = blePermissionsGranted,
            scanTimeoutReached = scanTimeoutReached,
            onDeviceSelected = { device -> viewModel.connectToBleDevice(device) },
            onDismiss = { viewModel.dismissBleScanDialog() },
            onEnableBluetooth = {
                if (blePermissionsGranted) {
                    @Suppress("DEPRECATION")
                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } else {
                    blePermissionLauncher.launch(requiredBlePermissions)
                }
            }
        )
    }

    // BLE warning dialogs (location services, connection timeout, low battery, unexpected disconnection)
    bleDialogState?.let { dialogState ->
        BleWarningDialog(
            state = dialogState,
            onDismiss = { viewModel.dismissBleDialog() },
            onAction = { action ->
                if (action == DialogAction.OpenLocationSettings) {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                viewModel.onBleDialogAction(action)
            }
        )
    }

    // Respiration sensor error dialog
    if (respirationDisconnectReason != null) {
        RespirationErrorDialog(
            reason = respirationDisconnectReason!!,
            onDismiss = { viewModel.clearRespirationDisconnectReason() }
        )
    }

    // Recording border animation
    val isRecording = recordingUiState.recordingState == DataRecordingState.RECORDING
    val borderTransition = rememberInfiniteTransition(label = "recording_border")
    val borderAlpha by borderTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "border_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isRecording) Modifier.border(
                    width = 4.dp,
                    color = Color(0xFFF44336).copy(alpha = borderAlpha)
                ) else Modifier
            )
    ) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = session?.sessionCode ?: "New Session",
                        fontWeight = FontWeight.SemiBold,
                        color = if (isRecording) Color.White
                            else MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (recordingUiState.recordingState != DataRecordingState.IDLE) {
                            showBackDialog = true
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = if (isRecording) Color.White
                                else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    RecordingBadge(
                        recordingState = recordingUiState.recordingState,
                        duration = recordingUiState.durationFormatted,
                        isVrTriggered = vrTriggeredRecording
                    )
                },
                colors = if (isRecording) {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFFF44336)
                    )
                } else {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Readiness backup banner (only shows when a prerequisite is missing)
            ReadinessWarningCard(
                missing = missingPrerequisites,
                onFix = onReadinessFix
            )

            // Low signal warning banner
            if (respirationLowSignalWarning != LowSignalWarning.NONE) {
                LowSignalWarningBanner(warningLevel = respirationLowSignalWarning)
            }

            // Sensor-lost-during-recording warning banner
            if (recordingUiState.isRecording) {
                val lostSensors = buildList {
                    if (recordingUiState.heartRateWasEnabled && !recordingUiState.isHeartRateConnected) add("eSense Pulse")
                    if (recordingUiState.respirationWasEnabled && !recordingUiState.isRespirationConnected) add("eSense Respiration")
                }
                if (lostSensors.isNotEmpty()) {
                    SensorLostDuringRecordingBanner(sensorNames = lostSensors)
                }
            }

            // Mindfield eSense device group
            val eSenseConnectionState = when {
                pulseSensorState == ConnectionState.CONNECTED ||
                        respirationConnectionState == ConnectionState.CONNECTED -> ConnectionState.CONNECTED
                pulseSensorState == ConnectionState.CONNECTING ||
                        respirationConnectionState == ConnectionState.CONNECTING -> ConnectionState.CONNECTING
                pulseSensorState == ConnectionState.ERROR ||
                        respirationConnectionState == ConnectionState.ERROR -> ConnectionState.ERROR
                else -> ConnectionState.DISCONNECTED
            }
            DeviceSensorGroup(
                deviceName = "Mindfield eSense",
                connectionState = eSenseConnectionState,
                footer = if (recordingUiState.isHeartRateConnected || recordingUiState.heartRateWasEnabled) {
                    {
                        PulseRrRow(
                            latestValue = pulseLatestRr,
                            sampleCount = recordingUiState.esenseRrIntervalSampleCount,
                            connectionState = pulseSensorState
                        )
                    }
                } else null
            ) {
                LiveSensorCard(
                    icon = Icons.Default.FavoriteBorder,
                    label = "Heart Rate",
                    value = when {
                        heartRate != null -> heartRate.toString()
                        pulseSensorState == ConnectionState.CONNECTED -> "..."
                        else -> "--"
                    },
                    unit = "BPM",
                    connectionState = pulseSensorState,
                    sampleCount = recordingUiState.heartRateSampleCount,
                    animate = heartRate != null && heartRate!! > 0,
                    onClick = { viewModel.onHeartRateCardClick() },
                    batteryLevel = bleBatteryLevel,
                    modifier = Modifier.weight(1f)
                )
                LiveSensorCard(
                    icon = Icons.Default.Mic,
                    label = "Respiration",
                    value = if (respirationConnectionState == ConnectionState.CONNECTED)
                        String.format(java.util.Locale.US, "%.1f", respirationRate)
                    else "--",
                    unit = "RA",
                    connectionState = respirationConnectionState,
                    sampleCount = recordingUiState.respirationSampleCount,
                    onClick = { viewModel.onRespirationCardClick(context) },
                    modifier = Modifier.weight(1f)
                )
            }

            // VR Controls Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Vrpano,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "VR Control",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        ConnectionStatusBadge(state = vrConnectionState)
                    }

                    // The tablet is now an HTTP server: the Quest connects to us and drives
                    // scenarios via POSTs. There is nothing to dial out to or command from here.
                    // Connection is inferred from recent VR events; setup diagnostics (tablet
                    // IP/port, event log) live on the dedicated VR screen.
                    Text(
                        text = when (vrConnectionState) {
                            ConnectionState.CONNECTED ->
                                "VR headset connected — scenarios are driven by the Quest."
                            else ->
                                "Waiting for the VR headset to connect. Open VR Control for the tablet address and event log."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Recording Control Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recording",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        // VR auto-control indicator
                        if (vrConnectionState == ConnectionState.CONNECTED) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Vrpano,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Color(0xFF2196F3)
                                )
                                Text(
                                    text = "VR Auto",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF2196F3)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Heart Rate",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (recordingUiState.isHeartRateConnected) {
                                val rrPart = if (recordingUiState.esenseRrIntervalSampleCount > 0)
                                    " · ${recordingUiState.esenseRrIntervalSampleCount} RR"
                                else ""
                                "${recordingUiState.heartRateSampleCount} HR$rrPart"
                            } else "Not connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Respiration",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (recordingUiState.isRespirationConnected)
                                "${recordingUiState.respirationSampleCount} samples"
                            else "Not connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider()

                    if (vrTriggeredRecording) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Vrpano,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFF2196F3)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Started by VR biofeedback command",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2196F3)
                            )
                        }
                    }

                }
            }

            // Notes section
            SessionNotesField(
                notes = notes,
                onNotesChange = { viewModel.updateNotes(it) },
                saveStatus = notesSaveStatus
            )

            // End Session button
            Button(
                onClick = { showEndSessionConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isEndingSession
            ) {
                if (isEndingSession) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("End Session & Save")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    } // Box
}

@Composable
private fun RecordingBadge(
    recordingState: DataRecordingState,
    duration: String,
    isVrTriggered: Boolean = false
) {
    if (recordingState == DataRecordingState.IDLE) return

    val infiniteTransition = rememberInfiniteTransition(label = "rec_badge_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (recordingState == DataRecordingState.RECORDING) 0.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_badge_alpha"
    )

    Row(
        modifier = Modifier.padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // VR indicator when recording was triggered by VR
        if (isVrTriggered && recordingState == DataRecordingState.RECORDING) {
            Icon(
                imageVector = Icons.Default.Vrpano,
                contentDescription = "VR triggered",
                modifier = Modifier.size(16.dp),
                tint = Color.White.copy(alpha = 0.9f)
            )
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(if (recordingState == DataRecordingState.RECORDING) pulseAlpha else 1f)
                .background(
                    color = if (recordingState == DataRecordingState.RECORDING)
                        Color.White
                    else
                        Color(0xFFFFA000),
                    shape = CircleShape
                )
        )
        Text(
            text = if (recordingState == DataRecordingState.RECORDING) "REC" else "IDLE",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (recordingState == DataRecordingState.RECORDING)
                Color.White
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = duration,
            style = MaterialTheme.typography.labelSmall,
            color = if (recordingState == DataRecordingState.RECORDING)
                Color.White.copy(alpha = 0.9f)
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BleScanDialog(
    devices: List<BleDevice>,
    isScanning: Boolean,
    bleConnectionState: ConnectionState,
    bluetoothEnabled: Boolean,
    blePermissionsGranted: Boolean,
    scanTimeoutReached: Boolean,
    onDeviceSelected: (BleDevice) -> Unit,
    onDismiss: () -> Unit,
    onEnableBluetooth: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Connect Heart Rate Sensor")
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!bluetoothEnabled) {
                    Row(
                        modifier = Modifier.clickable { onEnableBluetooth() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BluetoothDisabled,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = if (blePermissionsGranted)
                                "Bluetooth is disabled. Tap here to enable it."
                            else
                                "Bluetooth permissions required. Tap to grant.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (isScanning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Scanning for eSense Pulse devices...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (bleConnectionState == ConnectionState.CONNECTING) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Connecting...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (bluetoothEnabled) {
                    if (devices.isEmpty() && isScanning) {
                        Text(
                            text = "Searching for nearby devices...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (scanTimeoutReached) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No eSense devices found yet. Please check:\n\n" +
                                    "\u2022 The device is powered on\n" +
                                    "\u2022 The device is nearby (within 2 meters)\n" +
                                    "\u2022 The device is not connected to another phone/tablet\n\n" +
                                    "Scanning will continue in the background.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else if (devices.isEmpty() && !isScanning) {
                        Text(
                            text = "No devices found. Make sure eSense Pulse is turned on and nearby.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Found ${devices.size} device(s):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        devices.forEach { device ->
                            BleDeviceItem(
                                device = device,
                                onClick = { onDeviceSelected(device) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun BleWarningDialog(
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
            // Handled inline in BleScanDialog, not as a separate dialog
            onDismiss()
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
                title = { Text("Sensor Disconnected") },
                text = {
                    Text(
                        if (state.reason == "No sensor data received")
                            "The sensor connected but isn't sending data. " +
                            "Make sure the chest strap is properly positioned and in full contact with skin."
                        else
                            "Lost connection to ${state.deviceName}. " +
                            "Make sure the sensor is powered on and within range."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { onAction(DialogAction.Reconnect) }) {
                        Text("Reconnect")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Dismiss")
                    }
                }
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
        "No sensor signal detected. Check cable and chest strap connection."
    reason.contains("Signal Out of Range", ignoreCase = true) ->
        "Sensor signal out of range. Check chest strap position and cable connection."
    reason.contains("No Data", ignoreCase = true) ->
        "The sensor stopped sending data. Check that the audio cable is still connected to the device."
    reason.contains("abnormality", ignoreCase = true) ->
        "Cable disconnected. Device using microphone instead of sensor."
    reason.contains("Init Failed", ignoreCase = true) ->
        "Failed to initialize the sensor. Try disconnecting and reconnecting the audio cable."
    else -> reason
}

@Composable
private fun SensorLostDuringRecordingBanner(sensorNames: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "${sensorNames.joinToString(" & ")} disconnected",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Recording continues — reconnect to resume data capture",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun PulseRrRow(
    latestValue: Int?,
    sampleCount: Int,
    connectionState: ConnectionState,
    modifier: Modifier = Modifier
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "R-R",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (connectionState == ConnectionState.CONNECTED)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (latestValue != null) "$latestValue ms" else "--",
                style = MaterialTheme.typography.bodyMedium,
                color = if (latestValue != null)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (sampleCount > 0) {
            SuggestionChip(
                onClick = {},
                label = {
                    Text(
                        text = "$sampleCount samples",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        } else {
            Text(
                text = if (connectionState == ConnectionState.CONNECTED) "Waiting" else "--",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

