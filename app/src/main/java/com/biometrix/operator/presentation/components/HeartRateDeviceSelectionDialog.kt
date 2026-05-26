package com.biometrix.operator.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.biometrix.operator.data.prefs.HeartRateDevice

@Composable
fun HeartRateDeviceSelectionDialog(
    currentDevice: HeartRateDevice,
    onSelect: (HeartRateDevice) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(currentDevice) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Heart Rate Device") },
        text = {
            Column {
                Text(
                    text = "Select which heart rate sensor to use throughout the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                DeviceOption(
                    label = "eSense Pulse",
                    description = "Chest strap — Mindfield (BLE)",
                    isSelected = selected == HeartRateDevice.ESENSE_PULSE,
                    onClick = { selected = HeartRateDevice.ESENSE_PULSE }
                )

                DeviceOption(
                    label = "Fibion Flash",
                    description = "Chest strap — Fibion / Movesense (BLE)",
                    isSelected = selected == HeartRateDevice.FIBION_FLASH,
                    onClick = { selected = HeartRateDevice.FIBION_FLASH }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSelect(selected)
                onDismiss()
            }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DeviceOption(
    label: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
