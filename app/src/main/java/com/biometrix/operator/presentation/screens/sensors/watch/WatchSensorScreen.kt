package com.biometrix.operator.presentation.screens.sensors.watch

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.presentation.components.BluetoothDisabledCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchSensorScreen(
    onNavigateBack: () -> Unit,
    viewModel: WatchSensorViewModel = hiltViewModel()
) {
    val connection by viewModel.connectionState.collectAsState()
    val readings by viewModel.latestByType.collectAsState()
    val trackers by viewModel.availableTrackers.collectAsState()
    val battery by viewModel.batteryLevel.collectAsState()
    val bluetoothEnabled by viewModel.bluetoothEnabled.collectAsState()
    val context = LocalContext.current

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* adapter state is observed reactively via viewModel.bluetoothEnabled */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Galaxy Watch", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Bluetooth Disabled Warning — the watch link runs over direct Bluetooth, so without it
            // data can't arrive reliably (the cloud relay dies when the phone sleeps). Same card and
            // behaviour as the eSense Pulse screen.
            if (!bluetoothEnabled) {
                BluetoothDisabledCard(
                    onClick = {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }
                )
            }

            // Connection status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Channel", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = when (connection) {
                            ConnectionState.CONNECTED -> "Connected"
                            ConnectionState.CONNECTING -> "Connecting…"
                            ConnectionState.ERROR -> "Error"
                            ConnectionState.DISCONNECTED -> "Disconnected"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    battery?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("Watch battery: $it%", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Available trackers (what this watch can give us)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Supported trackers", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    if (trackers.isEmpty()) {
                        Text("—", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        trackers.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            // Live readings per type
            Text("Live readings", style = MaterialTheme.typography.titleMedium)
            if (readings.isEmpty()) {
                Text("Waiting for data… (start tracking on the watch)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                readings.toSortedMap().forEach { (type, r) ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(watchSignalLabel(type), style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = watchValueText(type, r.value),
                                style = MaterialTheme.typography.titleLarge,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Friendly display name for a watch signal type (the wire types are terse). */
private fun watchSignalLabel(type: String): String = when (type) {
    "HR" -> "Heart rate"
    "IBI" -> "Inter-beat interval"
    "EDA" -> "Skin conductance (EDA)"
    "BATTERY" -> "Watch battery"
    else -> type
}

/** Value text with the signal's unit so a bare number isn't ambiguous. */
private fun watchValueText(type: String, value: Float): String = when (type) {
    "HR" -> "${value.toInt()} bpm"
    "IBI" -> "${value.toInt()} ms"
    "EDA" -> "%.2f µS".format(value)
    "BATTERY" -> "${value.toInt()} %"
    else -> value.toString()
}
