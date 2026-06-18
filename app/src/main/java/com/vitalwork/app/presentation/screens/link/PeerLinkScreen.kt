package com.vitalwork.app.presentation.screens.link

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vitalwork.app.data.link.PeerRole
import com.vitalwork.app.data.link.model.PeerDevice
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.presentation.components.ConnectionStatusBadge
import com.vitalwork.app.service.BatteryOptimizationHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerLinkScreen(
    onNavigateBack: () -> Unit,
    viewModel: PeerLinkViewModel = hiltViewModel()
) {
    val role = viewModel.role
    val connectionState by viewModel.connectionState.collectAsState()
    val devices by viewModel.discoveredDevices.collectAsState()
    val logLines by viewModel.logLines.collectAsState()
    val peerLabel by viewModel.peerLabel.collectAsState()
    val isActive by viewModel.isActive.collectAsState()

    val context = LocalContext.current
    val batteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (role == PeerRole.SERVER) "Link — Server" else "Link — Client",
                        fontWeight = FontWeight.SemiBold
                    )
                },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!batteryExempt) {
                BatteryReminderCard(
                    onAllow = { BatteryOptimizationHelper.openExemptionSettings(context) }
                )
            }

            StatusCard(role = role, connectionState = connectionState, peerLabel = peerLabel)

            if (role == PeerRole.CLIENT && connectionState != ConnectionState.CONNECTED) {
                DiscoveredDevicesCard(devices = devices, onSelect = viewModel::onDeviceSelected)
            }

            Button(
                onClick = viewModel::onSendTest,
                enabled = connectionState == ConnectionState.CONNECTED,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Text("  Send test message")
            }

            if (role == PeerRole.SERVER) {
                // Server is started manually: Connect to host, Disconnect to stop.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = viewModel::connect,
                        enabled = !isActive,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Text("  Connect")
                    }
                    OutlinedButton(
                        onClick = viewModel::disconnect,
                        enabled = isActive,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null)
                        Text("  Disconnect")
                    }
                }
            } else if (isActive) {
                // Client connects by tapping a discovered device; only needs Disconnect.
                OutlinedButton(
                    onClick = viewModel::disconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.LinkOff, contentDescription = null)
                    Text("  Disconnect")
                }
            }

            LogCard(logLines = logLines, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun BatteryReminderCard(onAllow: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Allow background",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "This device may kill the connection when the screen is off. Exempt VitalWork " +
                    "from battery optimization to keep the link alive in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(onClick = onAllow) { Text("Allow") }
        }
    }
}

@Composable
private fun StatusCard(
    role: PeerRole,
    connectionState: ConnectionState,
    peerLabel: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Gray (disconnected) / green (connected) dot — same indicator the sensors use.
            ConnectionStatusBadge(state = connectionState)
            val labelPrefix = if (role == PeerRole.SERVER) "Advertising at" else "Peer"
            peerLabel?.let {
                Text(text = "$labelPrefix: $it", style = MaterialTheme.typography.bodyMedium)
            }
            if (role == PeerRole.SERVER) {
                Text(
                    text = "Open the Client on the other device to connect.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun DiscoveredDevicesCard(
    devices: List<PeerDevice>,
    onSelect: (PeerDevice) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Discovered peers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (devices.isEmpty()) {
                Text("Scanning…", style = MaterialTheme.typography.bodyMedium)
            } else {
                devices.forEach { device ->
                    Button(onClick = { onSelect(device) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${device.name}  (${device.host}:${device.port})")
                    }
                }
            }
        }
    }
}

@Composable
private fun LogCard(
    logLines: List<String>,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Log",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                reverseLayout = true
            ) {
                items(logLines.reversed()) { line ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
