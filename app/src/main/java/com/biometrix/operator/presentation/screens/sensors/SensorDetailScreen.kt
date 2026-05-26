package com.biometrix.operator.presentation.screens.sensors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biometrix.operator.presentation.screens.sensors.beurer.bc87.BeurerBc87Screen
import com.biometrix.operator.presentation.screens.sensors.fibion.flash.FibionFlashScreen
import com.biometrix.operator.presentation.screens.sensors.mindfield.pulse.EsensePulseScreen
import com.biometrix.operator.presentation.screens.sensors.mindfield.respiration.EsenseRespirationScreen

/**
 * Router screen that dispatches to the correct sensor detail screen based on sensorId.
 * This allows each sensor to have its own dedicated screen and ViewModel.
 */
@Composable
fun SensorDetailScreen(
    sensorId: String,
    onNavigateBack: () -> Unit
) {
    when (sensorId) {
        // Mindfield eSense sensors
        "esense_pulse" -> EsensePulseScreen(onNavigateBack = onNavigateBack)
        "esense_respiration" -> EsenseRespirationScreen(onNavigateBack = onNavigateBack)
        // Fibion sensors
        "fibion_flash" -> FibionFlashScreen(onNavigateBack = onNavigateBack)
        // Beurer sensors
        "beurer_bc87" -> BeurerBc87Screen(onNavigateBack = onNavigateBack)
        else -> UnknownSensorScreen(sensorId = sensorId, onNavigateBack = onNavigateBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnknownSensorScreen(
    sensorId: String,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Unknown Sensor",
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = "Unknown Sensor",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Sensor ID: $sensorId",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "This sensor type is not supported yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(onClick = onNavigateBack) {
                Text("Go Back")
            }
        }
    }
}
