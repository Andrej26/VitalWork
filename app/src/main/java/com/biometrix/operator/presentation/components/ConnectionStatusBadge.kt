package com.biometrix.operator.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.biometrix.operator.data.model.ConnectionState

@Composable
fun ConnectionStatusBadge(
    state: ConnectionState,
    label: String? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.DISCONNECTED -> Color.Gray
            ConnectionState.CONNECTING -> Color(0xFFFFA000)
            ConnectionState.CONNECTED -> Color(0xFF4CAF50)
            ConnectionState.ERROR -> Color(0xFFF44336)
        },
        animationSpec = tween(300),
        label = "statusColor"
    )

    val statusText = label ?: when (state) {
        ConnectionState.DISCONNECTED -> "Disconnected"
        ConnectionState.CONNECTING -> "Connecting"
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.ERROR -> "Error"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        if (state == ConnectionState.CONNECTING) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = color
            )
        } else {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )

        // Show retry button only in ERROR state when callback is provided
        if (state == ConnectionState.ERROR && onRetry != null) {
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(
                onClick = onRetry,
                modifier = Modifier.padding(0.dp)
            ) {
                Text(
                    text = "Retry",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
