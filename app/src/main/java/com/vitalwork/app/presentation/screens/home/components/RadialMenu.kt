package com.vitalwork.app.presentation.screens.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalwork.app.ui.theme.BrandFontFamily

/** Radius from the cluster center to each satellite's circle center. */
val RadialRingRadius = 116.dp

private val SatelliteBorder = Color(0xFFC9DFE9)

/** Dashed guide ring the satellites sit on — the quiet visual echo of the gear logo. */
@Composable
fun RingGuide(modifier: Modifier = Modifier) {
    val ringColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        drawCircle(
            color = ringColor,
            radius = RadialRingRadius.toPx(),
            center = Offset(size.width / 2f, size.height / 2f),
            style = Stroke(
                width = 1.4.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(2.5.dp.toPx(), 8.dp.toPx())
                )
            )
        )
    }
}

/** The big circular primary action at the center of the ring. */
@Composable
fun RadialCenterButton(
    title: String,
    icon: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 8.dp,
        modifier = modifier.size(150.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = title,
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * A satellite action on the ring: a small elevated circle with the icon, and the label in a
 * white chip below (so the dashed ring never runs through the text). [dotColor] adds the same
 * connection-status dot the sensor cards use.
 */
@Composable
fun RadialSatellite(
    label: String,
    icon: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dotColor: Color? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.width(96.dp)
    ) {
        Box {
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                border = BorderStroke(1.4.dp, SatelliteBorder),
                shadowElevation = 3.dp,
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = icon,
                        contentDescription = label,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        // Nudged inward so the dot straddles the circle's rim instead of floating
                        // beside it — reads as part of the button.
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ) {
            Text(
                text = label,
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

/** A quieter, smaller circle for the secondary nav row under the ring (Sensors/Tutorial/Settings). */
@Composable
fun NavCircle(
    label: String,
    icon: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            border = BorderStroke(1.4.dp, SatelliteBorder),
            modifier = Modifier.size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = icon,
                    contentDescription = label,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
        Text(
            text = label,
            fontFamily = BrandFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Bottom status card: readiness/link state on the left, device mode + prefix chip on the right. */
@Composable
fun HomeStatusCard(
    text: String,
    dotColor: Color,
    modeLabel: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = modeLabel,
                    fontFamily = BrandFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                )
            }
        }
    }
}

/** Offset of a satellite center from the cluster center for a screen-space [angleDegrees]
 *  (0° = right, 90° = down — matches the design mock's coordinate system). */
fun radialOffset(angleDegrees: Double, radius: Dp = RadialRingRadius): Pair<Dp, Dp> {
    val rad = Math.toRadians(angleDegrees)
    return Pair(radius * kotlin.math.cos(rad).toFloat(), radius * kotlin.math.sin(rad).toFloat())
}
