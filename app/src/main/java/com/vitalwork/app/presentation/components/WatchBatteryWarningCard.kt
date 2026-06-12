package com.vitalwork.app.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vitalwork.app.data.sensor.watch.WatchBatteryAlert

/**
 * Persistent (while shown) low-battery banner for the Galaxy Watch, hosted at the top of Home so
 * the operator sees it at the start-a-new-session decision point. Renders nothing when [alert] is
 * [WatchBatteryAlert.NONE]. WARNING is a static amber banner; CRITICAL is red and pulses to draw
 * attention. No vibration/sound — purely a visual cue between sessions.
 */
@Composable
fun WatchBatteryWarningCard(
    alert: WatchBatteryAlert,
    level: Int?,
    modifier: Modifier = Modifier
) {
    if (alert == WatchBatteryAlert.NONE) return

    val isCritical = alert == WatchBatteryAlert.CRITICAL
    val backgroundColor = if (isCritical) MaterialTheme.colorScheme.error else Color(0xFFFFA000)
    val contentColor = if (isCritical) MaterialTheme.colorScheme.onError else Color.White
    val pct = level?.let { "$it%" } ?: ""
    val text = if (isCritical) {
        "Galaxy Watch battery critically low${if (pct.isEmpty()) "" else " ($pct)"} — at this level the watch may stop recording sensor data while the screen is off and that data will be lost. Do not start a new session without charging the watch."
    } else {
        "Galaxy Watch battery low${if (pct.isEmpty()) "" else " ($pct)"} — charge as soon as possible. If it drops further the watch can stop recording during sleep and lose that data."
    }

    // Critical pulses to grab attention; warning is static.
    val infiniteTransition = rememberInfiniteTransition(label = "watchBatteryPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isCritical) 0.6f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "watchBatteryAlpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.BatteryAlert,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
