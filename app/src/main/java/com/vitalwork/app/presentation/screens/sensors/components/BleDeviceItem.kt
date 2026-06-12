package com.vitalwork.app.presentation.screens.sensors.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.NetworkWifi3Bar
import androidx.compose.material.icons.filled.NetworkWifi2Bar
import androidx.compose.material.icons.filled.NetworkWifi1Bar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vitalwork.app.data.sensor.ble.model.BleDevice
import com.vitalwork.app.data.sensor.ble.model.SignalStrength

/**
 * Composable that displays a single BLE device item in a list.
 */
@Composable
fun BleDeviceItem(
    device: BleDevice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Bluetooth icon
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (device.isConnectable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Device info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Show first advertisement data entry if available
                device.advertisementData.entries.firstOrNull()?.let { entry ->
                    Text(
                        text = "${entry.key}: ${entry.value}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Signal strength
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SignalStrengthIcon(
                    signalStrength = device.signalStrength,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "${device.rssi} dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Tap affordance
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Tap to connect",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SignalStrengthIcon(
    signalStrength: SignalStrength,
    modifier: Modifier = Modifier
) {
    val icon = when (signalStrength) {
        SignalStrength.EXCELLENT -> Icons.Default.NetworkWifi
        SignalStrength.GOOD -> Icons.Default.NetworkWifi3Bar
        SignalStrength.FAIR -> Icons.Default.NetworkWifi2Bar
        SignalStrength.WEAK -> Icons.Default.NetworkWifi1Bar
    }

    val tint = when (signalStrength) {
        SignalStrength.EXCELLENT -> MaterialTheme.colorScheme.primary
        SignalStrength.GOOD -> MaterialTheme.colorScheme.primary
        SignalStrength.FAIR -> MaterialTheme.colorScheme.secondary
        SignalStrength.WEAK -> MaterialTheme.colorScheme.error
    }

    Icon(
        imageVector = icon,
        contentDescription = "Signal: ${signalStrength.name}",
        modifier = modifier,
        tint = tint
    )
}
