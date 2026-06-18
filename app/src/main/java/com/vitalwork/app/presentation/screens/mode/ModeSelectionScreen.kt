package com.vitalwork.app.presentation.screens.mode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vitalwork.app.data.link.PeerRole
import com.vitalwork.app.presentation.screens.home.components.PrimaryActionButton

/**
 * First-launch picker for the device's link role. Persists the choice (so later launches skip
 * straight to Home) and reports it so navigation can land on Home in the chosen mode. Also reachable
 * later from Home to switch modes.
 */
@Composable
fun ModeSelectionScreen(
    onModeSelected: () -> Unit,
    viewModel: ModeSelectionViewModel = hiltViewModel()
) {
    Scaffold { paddingValues ->
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
                Text(
                    text = "Choose this device's mode",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Pick how this device participates in the device-to-device link. " +
                        "You can change it later from the home screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                PrimaryActionButton(
                    title = "Server",
                    subtitle = "Host the device link (other device connects)",
                    icon = Icons.Default.Wifi,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    onClick = {
                        viewModel.selectMode(PeerRole.SERVER)
                        onModeSelected()
                    }
                )

                PrimaryActionButton(
                    title = "Client",
                    subtitle = "Find and connect to a hosting device",
                    icon = Icons.Default.WifiFind,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    onClick = {
                        viewModel.selectMode(PeerRole.CLIENT)
                        onModeSelected()
                    }
                )
            }
        }
    }
}
