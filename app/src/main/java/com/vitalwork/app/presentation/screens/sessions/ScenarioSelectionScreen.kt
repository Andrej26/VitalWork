package com.vitalwork.app.presentation.screens.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vitalwork.app.data.db.ScenarioCode
import com.vitalwork.app.presentation.components.OutlineCard
import com.vitalwork.app.presentation.screens.sessions.components.EndSessionWatchDialog
import com.vitalwork.app.presentation.components.WatermarkedBackground
import com.vitalwork.app.ui.theme.BrandDeepTeal
import com.vitalwork.app.ui.theme.BrandFontFamily
import com.vitalwork.app.ui.theme.SuccessGreen
import com.vitalwork.app.ui.theme.SuccessGreenDeep

/**
 * Scenario picker that doubles as the session's home/hub: one vertically-centered button per
 * [ScenarioCode] (labelled with its `displayName`, e.g. "Scenario A – Reference State") opens the
 * session control screen for [sessionId] with the chosen scenario number (1-based, in declaration
 * order), and an **End Session & Save** action at the bottom finalizes the whole session
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
    val scenarios by viewModel.scenarios.collectAsState()
    val isEndingSession by viewModel.isEndingSession.collectAsState()
    val endSessionPhase by viewModel.endSessionPhase.collectAsState()
    val watchReconciliation by viewModel.watchReconciliation.collectAsState()

    // Scenario codes with at least one finished run — shown with a green check so the operator
    // sees at a glance what's still missing from the session.
    val recordedCodes = scenarios.filter { it.endedAt != null }.map { it.scenarioCode }.toSet()

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
        WatermarkedBackground {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp)
            ) {
                // Five scenario cards, vertically centered.
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 480.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ScenarioCode.entries.forEachIndexed { index, code ->
                        ScenarioCard(
                            code = code,
                            recorded = code in recordedCodes,
                            onClick = { onScenarioSelected(index + 1) }
                        )
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
}

/** Per-scenario icon: the visual cue for what kind of load the scenario applies. */
private fun scenarioIcon(code: ScenarioCode): ImageVector = when (code) {
    ScenarioCode.REFERENCE_STATE -> Icons.Default.Waves
    ScenarioCode.COGNITIVE_LOAD -> Icons.Default.Psychology
    ScenarioCode.DISTRACTING_ENVIRONMENT -> Icons.AutoMirrored.Filled.VolumeUp
    ScenarioCode.LONG_TERM_FATIGUE -> Icons.Default.Schedule
    ScenarioCode.REACTION_TASKS -> Icons.Default.Bolt
}

/**
 * One selectable scenario: outline card with a teal icon bubble carrying a small letter badge,
 * an eyebrow + full name, and a duration pill. A finished scenario shows a green check and a green
 * letter badge so the operator sees at a glance what's still missing.
 */
@Composable
private fun ScenarioCard(
    code: ScenarioCode,
    recorded: Boolean,
    onClick: () -> Unit
) {
    OutlineCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon bubble with the scenario letter tucked into the corner.
            Box(modifier = Modifier.size(46.dp)) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = scenarioIcon(code),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 3.dp, y = 3.dp)
                        .size(19.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .clip(CircleShape)
                            .background(if (recorded) SuccessGreenDeep else BrandDeepTeal),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = code.officialCode,
                            fontFamily = BrandFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            color = Color.White
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SCENARIO ${code.officialCode}",
                    fontFamily = BrandFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 0.9.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = code.displayName.substringAfter("– ").ifEmpty { code.displayName },
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            if (recorded) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Recorded",
                    modifier = Modifier.size(18.dp),
                    tint = SuccessGreen
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = "${code.countdownMinutes} min",
                    fontFamily = BrandFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                )
            }
        }
    }
}
