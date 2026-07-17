package com.vitalwork.app.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalwork.app.ui.theme.AlertAmberContainer
import com.vitalwork.app.ui.theme.AlertAmberDeep
import com.vitalwork.app.ui.theme.AlertRed
import com.vitalwork.app.ui.theme.AlertRedBorder
import com.vitalwork.app.ui.theme.AlertRedContainer
import com.vitalwork.app.ui.theme.AlertRedDeep
import com.vitalwork.app.ui.theme.BrandFontFamily

/**
 * Severity of an [AlertCard]. BLOCKING (red) marks states that must be resolved for the app to do
 * its job (missing permission, lost sensor mid-recording); ADVISORY (amber) marks conditions worth
 * attention that don't stop the work (low signal, battery getting low).
 */
enum class AlertSeverity { BLOCKING, ADVISORY }

/**
 * The app-wide outline-style alert card: white ground, hairline outline, and a colored icon bubble
 * carrying the severity — red for [AlertSeverity.BLOCKING] (plus red outline + dark-red title),
 * amber bubble on a neutral outline for [AlertSeverity.ADVISORY].
 *
 * @param pulse animates the card's alpha to draw the eye — reserve it for urgent states.
 * @param onClick makes the whole card one tap target (e.g. "tap to enable Bluetooth").
 * @param actionLabel optional pill button under the text (red for blocking, teal for advisory).
 * @param trailing optional slot at the row's end (chevron, button, percentage…).
 */
@Composable
fun AlertCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    severity: AlertSeverity = AlertSeverity.ADVISORY,
    description: String? = null,
    pulse: Boolean = false,
    onClick: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val blocking = severity == AlertSeverity.BLOCKING

    val infiniteTransition = rememberInfiniteTransition(label = "alertPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (pulse) 0.6f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alertAlpha"
    )

    val shape = RoundedCornerShape(14.dp)
    val border = BorderStroke(
        1.4.dp,
        if (blocking) AlertRedBorder else MaterialTheme.colorScheme.outlineVariant
    )
    val cardModifier = modifier
        .fillMaxWidth()
        .alpha(alpha)

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (blocking) AlertRedContainer else AlertAmberContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (blocking) AlertRed else AlertAmberDeep,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (blocking) AlertRedDeep else MaterialTheme.colorScheme.onSurface
                    )
                    if (description != null) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (trailing != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    trailing()
                }
            }
            if (actionLabel != null && onAction != null) {
                Surface(
                    onClick = onAction,
                    shape = RoundedCornerShape(999.dp),
                    color = if (blocking) AlertRed else MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = actionLabel,
                        fontFamily = BrandFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = cardModifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border = border,
            content = content
        )
    } else {
        Surface(
            modifier = cardModifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border = border,
            content = content
        )
    }
}
