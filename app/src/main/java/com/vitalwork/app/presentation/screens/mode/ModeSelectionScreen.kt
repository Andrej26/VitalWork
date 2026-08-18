package com.vitalwork.app.presentation.screens.mode

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vitalwork.app.data.link.PeerRole
import com.vitalwork.app.presentation.components.OutlineCard
import com.vitalwork.app.presentation.components.VitalWorkLockup
import com.vitalwork.app.presentation.components.WatermarkedBackground

/**
 * First-launch picker for the device's link role. Persists the choice (so later launches skip
 * straight to Home) and reports it so navigation can land on Home in the chosen mode. Later mode
 * changes are made in Settings.
 */
@Composable
fun ModeSelectionScreen(
    onModeSelected: () -> Unit,
    viewModel: ModeSelectionViewModel = hiltViewModel()
) {
    Scaffold { paddingValues ->
        WatermarkedBackground {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Brand lockup — the first thing a fresh install shows.
                    VitalWorkLockup(fontSize = 30.sp)
                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Choose this device's mode",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Pick how this device participates in the device-to-device link. " +
                            "You can change it later in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    ModeChoiceCard(
                        title = "Server",
                        description = "Host the device link (other device connects)",
                        icon = Icons.Default.Wifi,
                        onClick = {
                            viewModel.selectMode(PeerRole.SERVER)
                            onModeSelected()
                        }
                    )

                    ModeChoiceCard(
                        title = "Client",
                        description = "Find and connect to a hosting device",
                        icon = Icons.Default.WifiFind,
                        onClick = {
                            viewModel.selectMode(PeerRole.CLIENT)
                            onModeSelected()
                        }
                    )
                }
            }
        }
    }
}

/** One selectable role: outline card with a teal icon bubble, in the Home visual language. */
@Composable
private fun ModeChoiceCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    OutlineCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
