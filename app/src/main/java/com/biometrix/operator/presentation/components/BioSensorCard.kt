package com.biometrix.operator.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.biometrix.operator.data.sensor.DeviceState

@Composable
fun BioSensorCard(
    sensorName: String,
    state: DeviceState,
    rate: Float,
    stats: String,
    unit: String = "br/min",
    onToggle: () -> Unit,
    showStreamData: Boolean = false,
    onToggleStreamDisplay: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sensorName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                // Small badge for state
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (state == DeviceState.Streaming) Color(0xFF4CAF50) else Color.Gray.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = state.name.uppercase(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val displayRate = if (showStreamData) rate else 0f
            val displayStats = if (showStreamData) stats else "Data Hidden"

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format("%.1f", displayRate),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = " $unit",
                    modifier = Modifier.padding(bottom = 6.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = displayStats,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onToggle,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Text(if (state == DeviceState.Disconnected) "CONNECT" else "DISCONNECT")
                }

                if (state == DeviceState.Streaming) {
                    Button(onClick = onToggleStreamDisplay, modifier = Modifier.weight(1f)) {
                        Text(if (showStreamData) "HIDE DATA" else "SHOW DATA")
                    }
                }
            }
        }
    }
}
