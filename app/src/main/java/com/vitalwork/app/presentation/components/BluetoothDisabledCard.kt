package com.vitalwork.app.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.vitalwork.app.ui.theme.AlertRed

@Composable
fun BluetoothDisabledCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertCard(
        title = "Bluetooth Disabled",
        description = "Tap here to enable Bluetooth.",
        icon = Icons.Default.BluetoothDisabled,
        severity = AlertSeverity.BLOCKING,
        onClick = onClick,
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = AlertRed,
                modifier = Modifier.size(18.dp)
            )
        },
        modifier = modifier
    )
}
