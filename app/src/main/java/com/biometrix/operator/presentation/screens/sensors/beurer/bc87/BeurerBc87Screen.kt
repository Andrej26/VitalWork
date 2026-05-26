package com.biometrix.operator.presentation.screens.sensors.beurer.bc87

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.biometrix.operator.data.model.BloodPressureReading
import com.biometrix.operator.data.sensor.ble.Bc87State
import com.biometrix.operator.presentation.components.BluetoothDisabledCard
import com.biometrix.operator.presentation.screens.sensors.components.BleDebugLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeurerBc87Screen(
    onNavigateBack: () -> Unit,
    viewModel: BeurerBc87ViewModel = hiltViewModel()
) {
    val state by viewModel.bc87State.collectAsState()
    val lastReading by viewModel.lastReading.collectAsState()
    val recentReadings by viewModel.recentReadings.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val bluetoothEnabled by viewModel.bluetoothEnabled.collectAsState()

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { /* result handled reactively via bluetoothEnabled flow */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Beurer BC 87",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // State indicator
            StateCard(state = state)

            // Latest reading
            lastReading?.let { reading ->
                ReadingCard(reading = reading, title = "Latest Reading")
            }

            // Bluetooth Disabled Warning
            if (!bluetoothEnabled) {
                BluetoothDisabledCard(
                    onClick = {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }
                )
            }

            // Scan controls (only when Bluetooth is on)
            if (bluetoothEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val isIdle = state is Bc87State.Idle || state is Bc87State.Error
                    val isActive = state is Bc87State.Scanning || state is Bc87State.Connecting || state is Bc87State.Receiving

                    Button(
                        onClick = { viewModel.startScanning() },
                        enabled = isIdle,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Start Scan")
                    }

                    OutlinedButton(
                        onClick = { viewModel.stopScanning() },
                        enabled = isActive,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop Scan")
                    }
                }
            }

            // Recent readings history
            if (recentReadings.isNotEmpty()) {
                Text(
                    text = "Recent Readings (${recentReadings.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                recentReadings.forEach { reading ->
                    CompactReadingRow(reading = reading)
                }
            }

            // Debug log
            BleDebugLog(
                logEntries = logEntries,
                onClearLog = { viewModel.clearLog() }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StateCard(state: Bc87State) {
    val borderColor by animateColorAsState(
        targetValue = when (state) {
            is Bc87State.Idle -> MaterialTheme.colorScheme.outline
            is Bc87State.Scanning -> MaterialTheme.colorScheme.primary
            is Bc87State.Connecting -> MaterialTheme.colorScheme.tertiary
            is Bc87State.Receiving -> MaterialTheme.colorScheme.secondary
            is Bc87State.Error -> MaterialTheme.colorScheme.error
        },
        label = "border_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val isAnimating = state is Bc87State.Scanning || state is Bc87State.Connecting || state is Bc87State.Receiving
    val alpha = if (isAnimating) pulseAlpha else 1f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, MaterialTheme.shapes.medium)
            .alpha(alpha),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state is Bc87State.Scanning || state is Bc87State.Connecting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = borderColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column {
                Text(
                    text = "Blood Pressure Monitor",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when (state) {
                        is Bc87State.Idle -> "Idle — not scanning"
                        is Bc87State.Scanning -> "Scanning for BC87..."
                        is Bc87State.Connecting -> "Connecting..."
                        is Bc87State.Receiving -> "Receiving data..."
                        is Bc87State.Error -> "Error: ${state.message}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is Bc87State.Error)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReadingCard(reading: BloodPressureReading, title: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                // Systolic / Diastolic
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${reading.systolicMmHg}/${reading.diastolicMmHg}",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "mmHg",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }

                // Pulse
                reading.pulseRateBpm?.let { pulse ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$pulse",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Pulse bpm",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }

                // MAP
                reading.meanArterialMmHg?.let { map ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$map",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "MAP mmHg",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactReadingRow(reading: BloodPressureReading) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${reading.systolicMmHg}/${reading.diastolicMmHg} mmHg",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                reading.pulseRateBpm?.let { pulse ->
                    Text(
                        text = "Pulse: $pulse",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                reading.meanArterialMmHg?.let { map ->
                    Text(
                        text = "MAP: $map",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
