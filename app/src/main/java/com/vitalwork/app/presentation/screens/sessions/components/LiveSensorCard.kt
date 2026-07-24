package com.vitalwork.app.presentation.screens.sessions.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalwork.app.presentation.components.ConnectionStatusBadge
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.ui.theme.SuccessGreen
import com.vitalwork.app.ui.theme.WarningAmber
import com.vitalwork.app.ui.theme.ErrorRed

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
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Compact mode roughly halves the card height for screens that must fit without scrolling
    // (e.g. the transient post-scenario SessionControlScreen on a phone). Same content, smaller.
    val cardPadding = if (compact) 8.dp else 16.dp
    val iconSize = if (compact) 18.dp else 32.dp
    val valueFontSize = if (compact) 24.sp else 48.sp
    val gap = if (compact) 1.dp else 4.dp
    val isClickable = onClick != null &&
            (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.ERROR)
    val borderColor by animateColorAsState(
        targetValue = when (connectionState) {
            ConnectionState.CONNECTED -> SuccessGreen
            ConnectionState.CONNECTING -> WarningAmber
            ConnectionState.RECONNECTING -> WarningAmber
            ConnectionState.ERROR -> ErrorRed
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

    // A disconnected, tappable card gets a dashed primary border (the familiar "empty slot to fill"
    // affordance) so the whole card reads as tappable — the tap target is the whole card, not just the
    // small hint text. Connected/connecting/error states keep the solid status-colored border.
    val cardShape = MaterialTheme.shapes.medium
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isClickable) Modifier.clickable { onClick?.invoke() }
                    else Modifier
                )
                .then(
                    if (isClickable) Modifier.dashedBorder(
                        color = MaterialTheme.colorScheme.primary,
                        shape = cardShape,
                        strokeWidth = 2.dp,
                        dashLength = 6.dp,
                        gapLength = 4.dp
                    ) else Modifier
                ),
            shape = cardShape,
            color = MaterialTheme.colorScheme.surface,
            // The solid border is suppressed while clickable so the dashed overlay above stands alone.
            border = if (isClickable) null else BorderStroke(2.dp, borderColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(cardPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier
                        .size(iconSize)
                        .scale(iconScale),
                    tint = if (connectionState == ConnectionState.CONNECTED)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(gap))

                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(gap))

                // Large value display
                Text(
                    text = value,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = valueFontSize,
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

                Spacer(modifier = Modifier.height(if (compact) gap else 8.dp))

                ConnectionStatusBadge(state = connectionState)

                if (isClickable) {
                    Spacer(modifier = Modifier.height(gap))
                    TapActionPill(text = "Tap to connect", compact = compact)
                }

                if (sampleCount > 0) {
                    Spacer(modifier = Modifier.height(gap))
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

/**
 * The tap affordance for a disconnected, clickable sensor card/group: a small pill with a touch icon in
 * full primary color, so it reads as an action rather than the old faint hint text. The whole card/group
 * is the tap target — this pill just names the action ([text], e.g. "Tap to connect"). Shared by
 * [LiveSensorCard] and [DeviceSensorGroup] so the "tap here" language is identical across the screen.
 */
@Composable
fun TapActionPill(text: String, compact: Boolean) {
    val vPad = if (compact) 3.dp else 4.dp
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.TouchApp,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Draws a dashed [color] border along the [shape]'s outline. Used to mark a disconnected sensor
 * card/group as tappable (the "empty slot to fill" affordance) without changing its fill, so it stays
 * distinct from the solid status-colored borders of connected/connecting/error states. Shared by
 * [LiveSensorCard] and [DeviceSensorGroup].
 */
fun Modifier.dashedBorder(
    color: Color,
    shape: Shape,
    strokeWidth: Dp,
    dashLength: Dp,
    gapLength: Dp
): Modifier = this.drawWithContent {
    drawContent()
    val stroke = Stroke(
        width = strokeWidth.toPx(),
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(dashLength.toPx(), gapLength.toPx()), 0f
        )
    )
    val inset = strokeWidth.toPx() / 2f
    val outline = shape.createOutline(
        size = Size(size.width - strokeWidth.toPx(), size.height - strokeWidth.toPx()),
        layoutDirection = layoutDirection,
        density = this
    )
    translate(left = inset, top = inset) {
        drawOutline(outline = outline, color = color, style = stroke)
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
