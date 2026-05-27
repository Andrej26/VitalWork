package com.biometrix.operator.presentation.screens.sessions.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.presentation.components.ConnectionStatusBadge

@Composable
fun DeviceSensorGroup(
    deviceName: String,
    connectionState: ConnectionState,
    modifier: Modifier = Modifier,
    batteryLevel: Int? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = when (connectionState) {
            ConnectionState.CONNECTED -> Color(0xFF4CAF50)
            ConnectionState.CONNECTING -> Color(0xFFFFA000)
            ConnectionState.ERROR -> Color(0xFFF44336)
            ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(300),
        label = "device_group_border"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.5.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (connectionState == ConnectionState.CONNECTED) {
                    batteryLevel?.let { level ->
                        Icon(
                            imageVector = batteryIcon(level),
                            contentDescription = "Battery $level%",
                            modifier = Modifier.size(14.dp),
                            tint = if (level < 20) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "$level%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (level < 20) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                ConnectionStatusBadge(state = connectionState)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
            footer?.invoke(this)
        }
    }
}

private fun batteryIcon(level: Int): ImageVector = when {
    level >= 95 -> Icons.Default.BatteryFull
    level >= 80 -> Icons.Default.Battery6Bar
    level >= 65 -> Icons.Default.Battery5Bar
    level >= 50 -> Icons.Default.Battery4Bar
    level >= 35 -> Icons.Default.Battery3Bar
    level >= 20 -> Icons.Default.Battery2Bar
    level >= 10 -> Icons.Default.Battery1Bar
    else        -> Icons.Default.Battery0Bar
}
