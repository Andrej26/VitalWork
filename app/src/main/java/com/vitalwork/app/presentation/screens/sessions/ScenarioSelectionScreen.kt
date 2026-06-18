package com.vitalwork.app.presentation.screens.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vitalwork.app.presentation.screens.sessions.components.EndSessionWatchDialog

/**
 * Scenario picker that doubles as the session's home/hub: five vertically-centered buttons
 * ("Scenario 01"…"Scenario 05") open the session control screen for [sessionId] with the chosen
 * scenario number, and an **End Session & Save** action at the bottom finalizes the whole session
 * (with the watch-transfer handshake) and leaves for review. This is the screen operators return to
 * most often, so end/save lives here rather than inside each scenario run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioSelectionScreen(
    sessionId: Long,
    onScenarioSelected: (scenarioNumber: Int) -> Unit,
    onSessionEnded: (sessionId: Long) -> Unit,
    viewModel: SessionControlViewModel = hiltViewModel()
) {
    val session by viewModel.session.collectAsState()
    val isEndingSession by viewModel.isEndingSession.collectAsState()
    val endSessionPhase by viewModel.endSessionPhase.collectAsState()
    val watchReconciliation by viewModel.watchReconciliation.collectAsState()

    var showEndSessionConfirmation by remember { mutableStateOf(false) }

    // End-Session watch handshake (wake → transfer → green check → finalize), shared with the
    // session control screen via the same ViewModel.
    EndSessionWatchDialog(
        phase = endSessionPhase,
        onEndWithoutWatchData = { viewModel.endWithoutWatchData() },
        onRetry = { viewModel.retryWatchTransfer() },
        onComplete = { sid -> onSessionEnded(sid) },
        reconciliation = watchReconciliation
    )

    if (showEndSessionConfirmation) {
        AlertDialog(
            onDismissRequest = { showEndSessionConfirmation = false },
            title = { Text("End session?") },
            text = { Text("Are you sure you want to end this session?") },
            confirmButton = {
                TextButton(onClick = {
                    showEndSessionConfirmation = false
                    viewModel.requestEndSession()
                }) {
                    Text("End Session")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndSessionConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = session?.sessionCode ?: "Select Scenario",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
        ) {
            // Five scenario buttons, vertically centered.
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                (1..5).forEach { number ->
                    Button(
                        onClick = { onScenarioSelected(number) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Scenario %02d".format(number),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            // End Session & Save, pinned to the bottom of the hub.
            Button(
                onClick = { showEndSessionConfirmation = true },
                enabled = !isEndingSession,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                if (isEndingSession) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("End Session & Save")
            }
        }
    }
}
