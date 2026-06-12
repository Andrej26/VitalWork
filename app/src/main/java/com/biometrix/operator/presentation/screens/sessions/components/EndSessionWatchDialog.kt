package com.biometrix.operator.presentation.screens.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.biometrix.operator.presentation.screens.sessions.EndSessionPhase
import kotlinx.coroutines.delay

/** Orange used for the "Reconnecting" connection indicator — reused here so the transfer spinner
 *  looks identical to the link-reconnecting animation the operator already knows. */
private val ReconnectingOrange = Color(0xFFFFA000)

/** Green used for the upload-complete check (matches UploadProgressDialog). */
private val SuccessGreen = Color(0xFF2E7D32)

/** How long the green "Watch data saved" check stays up before navigating to review. */
private const val COMPLETE_HOLD_MS = 1_500L

/**
 * End-Session watch-transfer dialog, driven by [EndSessionPhase]:
 *
 *  wake the watch  →  "Receiving data from watch…" (reconnecting-style orange spinner)  →
 *  green check "Watch data saved"  →  auto-navigate to review.
 *
 * Renders nothing for [EndSessionPhase.Idle]. The dialog is non-cancellable (no tap-outside dismiss):
 * the only ways out are the watch completing the transfer, "End without watch data", or Retry.
 */
@Composable
fun EndSessionWatchDialog(
    phase: EndSessionPhase,
    onEndWithoutWatchData: () -> Unit,
    onRetry: () -> Unit,
    onComplete: (sessionId: Long) -> Unit
) {
    when (phase) {
        EndSessionPhase.Idle -> Unit

        EndSessionPhase.AwaitingWatchWake -> AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Default.Watch, contentDescription = null) },
            title = { Text("Wake your watch") },
            text = {
                SpinnerColumn(
                    message = "Tap your watch screen so it can send the recorded data to the tablet. " +
                        "Waiting for the watch to come online…"
                )
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onEndWithoutWatchData) {
                    Text("End without watch data", color = MaterialTheme.colorScheme.error)
                }
            }
        )

        is EndSessionPhase.Transferring -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Receiving data from watch…") },
            text = {
                val progress = phase.expected?.let { "Received ${phase.received} of $it" }
                    ?: "Starting transfer…"
                SpinnerColumn(message = progress)
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onEndWithoutWatchData) {
                    Text("End without watch data", color = MaterialTheme.colorScheme.error)
                }
            }
        )

        EndSessionPhase.Finalizing -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Saving…") },
            text = { SpinnerColumn(message = "Splitting and saving the recorded data.") },
            confirmButton = {}
        )

        is EndSessionPhase.Complete -> {
            AlertDialog(
                onDismissRequest = {},
                icon = {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(48.dp)
                    )
                },
                title = { Text("Watch data saved") },
                text = { Text("Finishing up…", textAlign = TextAlign.Center) },
                confirmButton = {}
            )
            // Hold the green check briefly, then navigate to the session review.
            LaunchedEffect(phase.sessionId) {
                delay(COMPLETE_HOLD_MS)
                onComplete(phase.sessionId)
            }
        }

        is EndSessionPhase.Failed -> AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Default.Watch, contentDescription = null) },
            title = { Text("Watch transfer didn't finish") },
            text = {
                Text(
                    "${phase.reason} The watch keeps its data, so you can retry (wake the watch first), " +
                        "or end now and pull it in a later session."
                )
            },
            confirmButton = {
                TextButton(onClick = onRetry) { Text("Retry") }
            },
            dismissButton = {
                TextButton(onClick = onEndWithoutWatchData) {
                    Text("End without watch data", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

/** Reconnecting-style orange spinner above a centered message. */
@Composable
private fun SpinnerColumn(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
            color = ReconnectingOrange
        )
        Spacer(Modifier.height(16.dp))
        Text(message, textAlign = TextAlign.Center)
    }
}
