package com.biometrix.operator.presentation.screens.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.biometrix.operator.presentation.screens.sessions.UploadState

private const val SUCCESS_AUTO_DISMISS_MS = 1500L

/**
 * Upload-to-server progress dialog shown on the Review screen.
 * - [UploadState.Uploading]: spinner + "Uploading…", not dismissable.
 * - [UploadState.Success]: green check, auto-dismisses after a short delay.
 * - [UploadState.Failed]: red ✕ + message, with Retry / Continue anyway.
 */
@Composable
fun UploadProgressDialog(
    state: UploadState,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    if (state is UploadState.Idle) return

    if (state is UploadState.Success) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(SUCCESS_AUTO_DISMISS_MS)
            onContinue()
        }
    }

    Dialog(onDismissRequest = { if (state is UploadState.Failed) onContinue() }) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (state) {
                    is UploadState.Uploading -> {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Uploading…", style = MaterialTheme.typography.titleMedium)
                    }

                    is UploadState.Success -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Everything uploaded successfully",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                    }

                    is UploadState.Failed -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Upload failed",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${state.message}\n\nThe session is saved on this device and stays flagged for upload.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onContinue) {
                                Text("Continue anyway")
                            }
                            TextButton(onClick = onRetry) {
                                Text("Retry")
                            }
                        }
                    }

                    UploadState.Idle -> Unit
                }
            }
        }
    }
}
