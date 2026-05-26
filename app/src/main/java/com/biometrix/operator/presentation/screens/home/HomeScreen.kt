package com.biometrix.operator.presentation.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Vrpano
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.biometrix.operator.presentation.components.NavigationCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToTutorial: () -> Unit,
    onNavigateToSensors: () -> Unit,
    onNavigateToVrControl: () -> Unit,
    onNavigateToTests: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val vrConnectionState by viewModel.vrConnectionState.collectAsState()
    val hasActiveTest by viewModel.hasActiveTest.collectAsState()
    val shouldAutoShowTutorial by viewModel.shouldAutoShowTutorial.collectAsState()

    // Auto-navigate to Tutorial on first launch
    LaunchedEffect(shouldAutoShowTutorial) {
        if (shouldAutoShowTutorial) {
            viewModel.onTutorialAutoShown()
            onNavigateToTutorial()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "BioMetrix Operator",
                        fontWeight = FontWeight.SemiBold
                    )
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
            val isWide = maxWidth >= 600.dp
            val hPad = if (isWide) 24.dp else 16.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = hPad, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Welcome",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Configure your sensors and VR connection, then start a therapy test.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isWide) {
                    // Tablet: 2-column grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            NavigationCard(
                                title = "Tutorial",
                                description = "Step-by-step guide to connect sensors, set up VR, and run your first session",
                                icon = Icons.Default.School,
                                onClick = onNavigateToTutorial
                            )
                            NavigationCard(
                                title = "Sensors",
                                description = "Configure BLE and audio jack sensors for physiological data collection",
                                icon = Icons.Default.Sensors,
                                onClick = onNavigateToSensors
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            NavigationCard(
                                title = "VR Control",
                                description = "Connect to Meta Quest headset and test VR commands",
                                icon = Icons.Default.Vrpano,
                                onClick = onNavigateToVrControl,
                                connectionState = vrConnectionState
                            )
                            NavigationCard(
                                title = "Tests",
                                description = if (hasActiveTest) "Test in progress" else "Start therapy tests, monitor status, and view recording history",
                                icon = Icons.Default.Folder,
                                onClick = onNavigateToTests,
                                connectionState = if (hasActiveTest) com.biometrix.operator.data.model.ConnectionState.CONNECTING else null,
                                connectionLabel = if (hasActiveTest) "Running" else null
                            )
                        }
                    }
                } else {
                    // Phone: single column
                    NavigationCard(
                        title = "Tutorial",
                        description = "Step-by-step guide to connect sensors, set up VR, and run your first session",
                        icon = Icons.Default.School,
                        onClick = onNavigateToTutorial
                    )

                    NavigationCard(
                        title = "Sensors",
                        description = "Configure BLE and audio jack sensors for physiological data collection",
                        icon = Icons.Default.Sensors,
                        onClick = onNavigateToSensors
                    )

                    NavigationCard(
                        title = "VR Control",
                        description = "Connect to Meta Quest headset and test VR commands",
                        icon = Icons.Default.Vrpano,
                        onClick = onNavigateToVrControl,
                        connectionState = vrConnectionState
                    )

                    NavigationCard(
                        title = "Tests",
                        description = if (hasActiveTest) "Test in progress" else "Start therapy tests, monitor status, and view recording history",
                        icon = Icons.Default.Folder,
                        onClick = onNavigateToTests,
                        connectionState = if (hasActiveTest) com.biometrix.operator.data.model.ConnectionState.CONNECTING else null,
                        connectionLabel = if (hasActiveTest) "Running" else null
                    )
                }
            }
        }
    }
}
