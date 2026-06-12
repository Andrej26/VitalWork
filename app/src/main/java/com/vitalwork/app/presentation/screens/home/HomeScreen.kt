package com.vitalwork.app.presentation.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.Manifest
import com.vitalwork.app.data.system.SessionPrerequisite
import com.vitalwork.app.data.system.SystemReadinessChecker
import com.vitalwork.app.presentation.components.ConnectionStatusBadge
import com.vitalwork.app.presentation.components.ReadinessWarningCard
import com.vitalwork.app.presentation.components.WatchBatteryWarningCard
import com.vitalwork.app.presentation.components.onPermissionDenied
import com.vitalwork.app.service.BatteryOptimizationHelper
import com.vitalwork.app.presentation.screens.home.components.PrimaryActionButton
import com.vitalwork.app.presentation.screens.home.components.SecondaryNavRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToTutorial: () -> Unit,
    onNavigateToSensors: () -> Unit,
    onNavigateToVrControl: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToParticipantEntry: () -> Unit,
    onNavigateToSessionActive: (Long) -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateToSessionReview: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val vrConnectionState by viewModel.vrConnectionState.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()
    val isStarting by viewModel.isStarting.collectAsState()
    val shouldAutoShowTutorial by viewModel.shouldAutoShowTutorial.collectAsState()
    val missingPrerequisites by viewModel.missingPrerequisites.collectAsState()
    val watchBatteryAlert by viewModel.watchBatteryAlert.collectAsState()
    val watchBatteryLevel by viewModel.watchBatteryLevel.collectAsState()

    val context = LocalContext.current

    // Re-derive readiness whenever the screen resumes — catches a silently revoked permission or a
    // battery-optimization setting that an update/OEM flipped, and clears the card after a Fix.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) onPermissionDenied(context, Manifest.permission.POST_NOTIFICATIONS)
        viewModel.refresh()
    }
    val microphoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) onPermissionDenied(context, Manifest.permission.RECORD_AUDIO)
        viewModel.refresh()
    }
    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { !it }) {
            // Surface settings if any BLE permission is permanently denied (use the first as probe).
            onPermissionDenied(context, SystemReadinessChecker.requiredBluetoothPermissions().first())
        }
        viewModel.refresh()
    }

    val onFix: (SessionPrerequisite) -> Unit = { prerequisite ->
        when (prerequisite) {
            SessionPrerequisite.NOTIFICATIONS ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            SessionPrerequisite.MICROPHONE ->
                microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
            SessionPrerequisite.BLUETOOTH ->
                bluetoothLauncher.launch(SystemReadinessChecker.requiredBluetoothPermissions())
            SessionPrerequisite.BATTERY_OPTIMIZATION ->
                BatteryOptimizationHelper.openExemptionSettings(context)
        }
    }

    LaunchedEffect(shouldAutoShowTutorial) {
        if (shouldAutoShowTutorial) {
            viewModel.onTutorialAutoShown()
            onNavigateToTutorial()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "VitalWork Operator",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    ConnectionStatusBadge(
                        state = vrConnectionState,
                        label = "VR",
                        modifier = Modifier.padding(end = 16.dp)
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
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            val currentActive = activeSession

            // While a session is active, tick once a second so the button shows live elapsed time.
            var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(currentActive?.id) {
                if (currentActive != null) {
                    while (true) {
                        nowMs = System.currentTimeMillis()
                        delay(1000L)
                    }
                }
            }
            val elapsedLabel = currentActive?.let { formatElapsed(nowMs - it.startedAt) }

            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ReadinessWarningCard(
                    missing = missingPrerequisites,
                    onFix = onFix
                )

                WatchBatteryWarningCard(
                    alert = watchBatteryAlert,
                    level = watchBatteryLevel
                )

                PrimaryActionButton(
                    title = if (currentActive != null) "Resume Active Session" else "Start New Session",
                    subtitle = elapsedLabel,
                    enabled = !isStarting,
                    containerColor = if (currentActive != null) ActiveSessionOrange
                        else MaterialTheme.colorScheme.primary,
                    contentColor = if (currentActive != null) Color.White
                        else MaterialTheme.colorScheme.onPrimary,
                    onClick = {
                        if (currentActive != null) {
                            onNavigateToSessionActive(currentActive.id)
                        } else {
                            viewModel.beginSession(
                                onResumeActive = onNavigateToSessionActive,
                                onStartNewParticipantFlow = onNavigateToParticipantEntry
                            )
                        }
                    }
                )

                PrimaryActionButton(
                    title = "Completed Sessions",
                    subtitle = "Browse and export past sessions",
                    onClick = onNavigateToSessions,
                    icon = Icons.Default.Folder,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )

                HorizontalDivider()

                SecondaryNavRow(
                    onSensors = onNavigateToSensors,
                    onVrControl = onNavigateToVrControl,
                    onTutorial = onNavigateToTutorial,
                    onSettings = onNavigateToSettings
                )
            }
        }
    }
}

/** Amber/orange used to make an in-progress session stand out — matches the ACTIVE accent
 *  already used in [com.vitalwork.app.presentation.screens.sessions.components.ActiveSessionBanner]. */
private val ActiveSessionOrange = Color(0xFFCC8A52)

/** Formats an elapsed duration as H:MM:SS (or M:SS under an hour). */
private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
