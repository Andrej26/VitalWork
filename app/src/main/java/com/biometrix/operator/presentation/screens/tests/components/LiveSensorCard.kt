package com.biometrix.operator.presentation.screens.tests.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biometrix.operator.presentation.components.ConnectionStatusBadge
import com.biometrix.operator.data.model.ConnectionState

@Composable
fun LiveSensorCard(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    connectionState: ConnectionState,
    sampleCount: Int,
    animate: Boolean = false,
    onClick: (() -> Unit)? = null,
    batteryLevel: Int? = null,
    modifier: Modifier = Modifier
) {
    val isClickable = onClick != null &&
            (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.ERROR)
    val borderColor by animateColorAsState(
        targetValue = when (connectionState) {
            ConnectionState.CONNECTED -> Color(0xFF4CAF50)
            ConnectionState.CONNECTING -> Color(0xFFFFA000)
            ConnectionState.ERROR -> Color(0xFFF44336)
            ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(300),
        label = "border_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "sensor_pulse")
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (animate && connectionState == ConnectionState.CONNECTED) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(300),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_scale"
    )

    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isClickable) Modifier.clickable { onClick?.invoke() }
                    else Modifier
                ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            border = BorderStroke(2.dp, borderColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier
                        .size(32.dp)
                        .scale(iconScale),
                    tint = if (connectionState == ConnectionState.CONNECTED)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Large value display
                Text(
                    text = value,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (connectionState == ConnectionState.CONNECTED)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )

                Text(
                    text = unit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                ConnectionStatusBadge(state = connectionState)

                if (isClickable) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap to connect",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }

                if (sampleCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$sampleCount samples",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Battery badge — top-right corner, only when connected and level known
        if (connectionState == ConnectionState.CONNECTED) {
            batteryLevel?.let { level ->
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = batteryIcon(level),
                        contentDescription = "Battery $level%",
                        modifier = Modifier.size(14.dp),
                        tint = if (level < 20) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "$level%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (level < 20) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
