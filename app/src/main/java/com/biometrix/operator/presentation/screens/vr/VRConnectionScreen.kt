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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.biometrix.operator.presentation.log.LogType

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

    // Refresh the tablet address and make sure the VR link is running. The link is app-scoped and
    // deliberately NOT torn down when this screen closes (see VrLinkManager), so the headset stays
    // connected wherever the operator navigates — only the Stop button below disconnects it.
    LaunchedEffect(Unit) {
        viewModel.refreshAddress()
        viewModel.startVrLink()
    }

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
                    // Start/Stop the app-wide VR link. Stop is the clean way to end VR comms before
                    // quitting the headset app — it disconnects without tripping a "lost" warning.
                    if (uiState.linkActive) {
                        OutlinedButton(
                            onClick = viewModel::stopVrLink,
                            modifier = Modifier.fillMaxWidth(),
                            // Disabled during a session — stopping then would clear the bond the
                            // recording depends on. End the session first.
                            enabled = !uiState.sessionActive,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Stop VR link")
                        }
                        Text(
                            text = if (uiState.sessionActive) {
                                "A session is running — end it before stopping the VR link, so the " +
                                    "recording's connection isn't cut."
                            } else {
                                "Tap Stop before quitting the VR app to disconnect cleanly and " +
                                    "avoid a false \"connection lost\" warning."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Button(
                            onClick = viewModel::startVrLink,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start VR link")
                        }
                    }
                }
            }

            // Pairing: pending claim → Connect; bonded → connecting/bonded; lost heartbeat → warning.
            PairingCard(
                linkActive = uiState.linkActive,
                pairingState = uiState.pairingState,
                candidate = uiState.candidate,
                connectionState = uiState.connectionState,
                everConnectedSinceBond = uiState.everConnectedSinceBond,
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
    linkActive: Boolean,
    pairingState: VrPairingManager.PairingState,
    candidate: VrPairingManager.VrCandidate?,
    connectionState: ConnectionState,
    everConnectedSinceBond: Boolean,
    onConnect: () -> Unit
) {
    val bonded = pairingState == VrPairingManager.PairingState.BONDED
    val connected = connectionState == ConnectionState.CONNECTED
    // Just bonded but no heartbeat yet — normal, show a neutral "connecting" state (NOT red).
    val bondedConnecting = bonded && !connected && !everConnectedSinceBond
    // Was connected, then heartbeat went silent — a genuine lost-connection warning (red).
    val bondedButLost = bonded && !connected && everConnectedSinceBond

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
            // When the link is stopped the tablet isn't listening at all — say so plainly instead of
            // the "waiting for a headset" copy, which would imply discovery is running.
            if (!linkActive) {
                Text(
                    text = "VR link stopped",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "The tablet isn't listening for a headset. Tap Start VR link above to begin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
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
                VrPairingManager.PairingState.BONDED -> when {
                    bondedConnecting -> {
                        Text(
                            text = "Connecting to VR headset…",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Paired with ${candidate?.display() ?: "the headset"}. Waiting for " +
                                "it to start sending — this is normal for the first few seconds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    bondedButLost -> {
                        Text(
                            text = "Headset paused — reconnecting",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "No heartbeat right now — the headset is likely asleep. The pairing " +
                                "is kept and recording continues; it reconnects automatically the moment " +
                                "the headset wakes (no need to pair again).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    else -> {
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
    // Tone errors/successes so the operator can scan the log for trouble at a glance.
    val messageColor = when (entry.type) {
        LogType.ERROR -> MaterialTheme.colorScheme.error
        LogType.SUCCESS -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
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
                style = MaterialTheme.typography.bodySmall,
                color = messageColor
            )
        }
    }
}
