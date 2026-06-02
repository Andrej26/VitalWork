package com.biometrix.operator.presentation.screens.sensors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Watch
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.biometrix.operator.presentation.components.SensorTypeCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSensorDetail: (String) -> Unit,
    viewModel: SensorsViewModel = hiltViewModel()
) {
    val bleConnectionState by viewModel.bleConnectionState.collectAsState()
    val respirationState by viewModel.respirationState.collectAsState()
    val watchConnectionState by viewModel.watchConnectionState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Sensors",
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val hPad = if (maxWidth >= 600.dp) 24.dp else 16.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = hPad, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Available Sensors",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                SensorTypeCard(
                    name = "eSense Pulse",
                    description = "Heart Rate Monitor (BLE)",
                    icon = Icons.Default.Bluetooth,
                    connectionState = bleConnectionState,
                    onClick = { onNavigateToSensorDetail("esense_pulse") }
                )

                SensorTypeCard(
                    name = "eSense Respiration",
                    description = "Breathing Sensor (Audio Jack)",
                    icon = Icons.Default.Mic,
                    connectionState = respirationState.toConnectionState(),
                    onClick = { onNavigateToSensorDetail("esense_respiration") }
                )

                SensorTypeCard(
                    name = "Galaxy Watch",
                    description = "HR / IBI / EDA (Wear OS)",
                    icon = Icons.Default.Watch,
                    connectionState = watchConnectionState,
                    onClick = { onNavigateToSensorDetail("galaxy_watch") }
                )
            }
        }
    }
}
