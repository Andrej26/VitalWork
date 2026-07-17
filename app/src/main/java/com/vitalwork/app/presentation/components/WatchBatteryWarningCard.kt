package com.vitalwork.app.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.vitalwork.app.data.sensor.watch.WatchBatteryAlert
import com.vitalwork.app.ui.theme.AlertAmberContainer
import com.vitalwork.app.ui.theme.AlertAmberDeep
import com.vitalwork.app.ui.theme.AlertRed
import com.vitalwork.app.ui.theme.AlertRedBorder
import com.vitalwork.app.ui.theme.AlertRedContainer
import com.vitalwork.app.ui.theme.AlertRedDeep

/**
 * Persistent (while shown) low-battery banner for the Galaxy Watch, hosted at the top of Home so
 * the operator sees it at the start-a-new-session decision point. Renders nothing when [alert] is
 * [WatchBatteryAlert.NONE]. Outline-style alert card: WARNING is advisory (amber bubble, neutral
 * outline); CRITICAL is blocking-level (red outline + bubble) and pulses to draw attention.
 * No vibration/sound — purely a visual cue between sessions.
 */
@Composable
fun WatchBatteryWarningCard(
    alert: WatchBatteryAlert,
    level: Int?,
    modifier: Modifier = Modifier
) {
    if (alert == WatchBatteryAlert.NONE) return

    val isCritical = alert == WatchBatteryAlert.CRITICAL
    val pct = level?.let { " ($it%)" } ?: ""
    val title = if (isCritical) "Galaxy Watch battery critically low$pct"
        else "Galaxy Watch battery low$pct"
    val description = if (isCritical) {
        "At this level the watch may stop recording sensor data while the screen is off and that " +
            "data will be lost. Do not start a new session without charging the watch."
    } else {
        "Charge as soon as possible. If it drops further the watch can stop recording during " +
            "sleep and lose that data."
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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.4.dp,
            if (isCritical) AlertRedBorder else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (isCritical) AlertRedContainer else AlertAmberContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.BatteryAlert,
                    contentDescription = null,
                    tint = if (isCritical) AlertRed else AlertAmberDeep,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCritical) AlertRedDeep else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
