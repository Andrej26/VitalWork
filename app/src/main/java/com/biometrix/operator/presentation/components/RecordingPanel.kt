package com.biometrix.operator.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biometrix.operator.data.recording.model.DataRecordingState

@Composable
fun RecordingPanel(
    recordingState: DataRecordingState,
    durationFormatted: String,
    isHeartRateConnected: Boolean,
    isRespirationConnected: Boolean,
    heartRateSampleCount: Int,
    respirationSampleCount: Int,
    recordingIdentifier: String?,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dataRecording")
    val recordingAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recordingPulse"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with recording indicator
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recording",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                if (recordingState == DataRecordingState.RECORDING) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFF44336).copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .alpha(recordingAlpha)
                                    .clip(CircleShape)
                                    .background(Color(0xFFF44336))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = durationFormatted,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFF44336)
                            )
                        }
                    }
                }

                // Show recording identifier if available
                recordingIdentifier?.let { id ->
                    if (recordingState == DataRecordingState.IDLE) {
                        Text(
                            text = id,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Sensor status
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isHeartRateConnected) {
                    SensorRecordingRow(
                        icon = Icons.Default.Bluetooth,
                        label = "eSense Pulse",
                        sampleCount = heartRateSampleCount,
                        isRecording = recordingState == DataRecordingState.RECORDING
                    )
                }

                if (isRespirationConnected) {
                    SensorRecordingRow(
                        icon = Icons.Default.Mic,
                        label = "eSense Respiration",
                        sampleCount = respirationSampleCount,
                        isRecording = recordingState == DataRecordingState.RECORDING
                    )
                }

                if (!isHeartRateConnected && !isRespirationConnected) {
                    Text(
                        text = "No sensors connected. Connect a sensor to enable recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Start button
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                    enabled = canStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Start")
                }

                // Stop button
                FilledTonalButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    enabled = recordingState == DataRecordingState.RECORDING
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun SensorRecordingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    sampleCount: Int,
    isRecording: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = if (isRecording || sampleCount > 0) "$sampleCount samples" else "Ready",
            style = MaterialTheme.typography.labelSmall,
            color = if (isRecording) Color(0xFFF44336) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
