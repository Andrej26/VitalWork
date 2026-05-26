package com.biometrix.operator.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class RecordingState {
    IDLE,      // No active test
    ARMED,     // Test ready, waiting for VR trigger
    RECORDING  // Active data collection
}

@Composable
fun RecordingIndicator(
    state: RecordingState,
    duration: String = "00:00:00",
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")

    // Fast pulse for recording (500ms)
    val recordingAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recordingPulse"
    )

    // Slow pulse for armed (1000ms)
    val armedAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "armedPulse"
    )

    val (backgroundColor, dotColor, textColor, statusText, dotAlpha) = when (state) {
        RecordingState.IDLE -> StatusConfig(
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
            dotColor = Color.Gray,
            textColor = Color.Gray,
            statusText = "IDLE",
            dotAlpha = 1f
        )
        RecordingState.ARMED -> StatusConfig(
            backgroundColor = Color(0xFFFFA000).copy(alpha = 0.15f),
            dotColor = Color(0xFFFFA000),
            textColor = Color(0xFFFFA000),
            statusText = "ARMED",
            dotAlpha = armedAlpha
        )
        RecordingState.RECORDING -> StatusConfig(
            backgroundColor = Color(0xFFF44336).copy(alpha = 0.15f),
            dotColor = Color(0xFFF44336),
            textColor = Color(0xFFF44336),
            statusText = "RECORDING",
            dotAlpha = recordingAlpha
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(dotColor)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = textColor
            )

            if (state == RecordingState.ARMED) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "- Waiting for VR",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                    color = textColor.copy(alpha = 0.7f)
                )
            }

            if (state == RecordingState.RECORDING) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = duration,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private data class StatusConfig(
    val backgroundColor: Color,
    val dotColor: Color,
    val textColor: Color,
    val statusText: String,
    val dotAlpha: Float
)
