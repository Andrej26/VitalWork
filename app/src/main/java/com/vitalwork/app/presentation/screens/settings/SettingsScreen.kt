package com.vitalwork.app.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Per-device settings. The device prefix (A/B/C/D) tags every participant code (`A-001`) and session
 * code (`VW-A-…`) generated on this tablet, and also scopes the device-to-device link to one pair.
 * Rule: **both devices of a pair (server + client) use the same letter**; different pairs use
 * different letters, so codes don't collide and each client only links to its own server. Operators
 * must agree beforehand which letter each pair owns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val devicePrefix by viewModel.devicePrefix.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
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
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Device prefix",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "This letter is added to every participant code (e.g. $devicePrefix-001) " +
                            "and session code on this device, and links it to its pair. Both devices " +
                            "in a set — the server and its client — use the same letter; a second " +
                            "set uses a different letter. That way each client connects only to its " +
                            "own server, and codes never collide. Agree beforehand which letter each " +
                            "set owns.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    val prefixes = viewModel.devicePrefixes
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        prefixes.forEachIndexed { index, prefix ->
                            SegmentedButton(
                                selected = prefix == devicePrefix,
                                onClick = { viewModel.onPrefixSelected(prefix) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = prefixes.size
                                )
                            ) {
                                Text(prefix)
                            }
                        }
                    }

                    Text(
                        text = "Selected: $devicePrefix",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
