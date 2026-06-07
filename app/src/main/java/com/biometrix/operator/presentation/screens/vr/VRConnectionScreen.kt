package com.biometrix.operator.presentation.screens.vr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.vr.VrPairingManager
import com.biometrix.operator.presentation.components.ConnectionStatusBadge
import com.biometrix.operator.presentation.log.LogEntry

/**
 * Read-only VR link diagnostics: the tablet's address (to read to the VR colleague), the inferred
 * connection state, and a live log of received VR events. The tablet is the HTTP server now, so
 * there are no connect/command controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VRConnectionScreen(
    onNavigateBack: () -> Unit,
    viewModel: VRConnectionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshAddress() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VR Control") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
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
            // Status + tablet address
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "VR Headset",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        ConnectionStatusBadge(state = uiState.connectionState)
                    }
                    Text(
                        text = "Launch the VR app and leave it in its menu. When the headset appears " +
                            "below, tap Connect to bond this tablet to it; after that the tablet " +
                            "accepts data only from that headset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Tablet address (diagnostics): " +
                            (uiState.tabletIpAddress?.let { "$it:${uiState.httpPort}" }
                                ?: "no Wi-Fi connection"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.connectionState == ConnectionState.CONNECTED) {
                        Text(
                            text = "VR headset connected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Pairing: pending claim → Connect; bonded → bond info; lost heartbeat → warning.
            PairingCard(
                pairingState = uiState.pairingState,
                candidate = uiState.candidate,
                connectionState = uiState.connectionState,
                onConnect = viewModel::confirmPairing
            )

            // Event log
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Event log",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = viewModel::clearLog) { Text("Clear") }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(uiState.logEntries) { entry -> LogRow(entry) }
            }
        }
    }
}

/** Operator-facing description of a candidate: prefer the human label, fall back to the id. */
private fun VrPairingManager.VrCandidate.display(): String {
    val name = label?.takeIf { it.isNotBlank() } ?: questId
    return "$name at $sourceIp"
}

@Composable
private fun PairingCard(
    pairingState: VrPairingManager.PairingState,
    candidate: VrPairingManager.VrCandidate?,
    connectionState: ConnectionState,
    onConnect: () -> Unit
) {
    // While bonded, surface a lost-connection warning if the heartbeat has gone silent.
    val bondedButLost = pairingState == VrPairingManager.PairingState.BONDED &&
        connectionState != ConnectionState.CONNECTED

    val containerColor = when {
        bondedButLost -> MaterialTheme.colorScheme.errorContainer
        pairingState == VrPairingManager.PairingState.PENDING ->
            MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (pairingState) {
                VrPairingManager.PairingState.UNPAIRED -> {
                    Text(
                        text = "Waiting for a VR headset…",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "No headset is broadcasting yet. Start the VR app to see it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                VrPairingManager.PairingState.PENDING -> {
                    Text(
                        text = "VR headset wants to connect",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = candidate?.display() ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                        Text("Connect")
                    }
                }
                VrPairingManager.PairingState.BONDED -> {
                    if (bondedButLost) {
                        Text(
                            text = "VR connection lost",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "No heartbeat from the headset. Recording continues; the tablet " +
                                "will reconnect automatically when the headset comes back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else {
                        Text(
                            text = "Bonded to VR headset",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = candidate?.display() ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(modifier = Modifier.padding(8.dp)) {
            Text(
                text = entry.timestamp,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
