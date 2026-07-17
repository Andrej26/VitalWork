package com.vitalwork.app.presentation.screens.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vitalwork.app.R
import com.vitalwork.app.data.link.PeerRole
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.system.SessionPrerequisite
import com.vitalwork.app.data.system.SystemReadinessChecker
import com.vitalwork.app.presentation.components.ReadinessWarningCard
import com.vitalwork.app.presentation.components.VitalWorkLockup
import com.vitalwork.app.presentation.components.WatchBatteryWarningCard
import com.vitalwork.app.presentation.components.WatermarkedBackground
import com.vitalwork.app.presentation.components.connectionStatusColor
import com.vitalwork.app.presentation.components.onPermissionDenied
import com.vitalwork.app.presentation.screens.home.components.HomeStatusCard
import com.vitalwork.app.presentation.screens.home.components.NavCircle
import com.vitalwork.app.presentation.screens.home.components.RadialCenterButton
import com.vitalwork.app.presentation.screens.home.components.RadialSatellite
import com.vitalwork.app.presentation.screens.home.components.RingGuide
import com.vitalwork.app.service.BatteryOptimizationHelper
import com.vitalwork.app.ui.theme.SuccessGreen
import com.vitalwork.app.ui.theme.WarningAmber
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onNavigateToTutorial: () -> Unit,
    onNavigateToSensors: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToParticipantEntry: () -> Unit,
    onNavigateToSessionActive: (Long) -> Unit,
    onNavigateToLinkServer: () -> Unit,
    onNavigateToLinkClient: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val activeSession by viewModel.activeSession.collectAsState()
    val isStarting by viewModel.isStarting.collectAsState()
    val shouldAutoShowTutorial by viewModel.shouldAutoShowTutorial.collectAsState()
    val missingPrerequisites by viewModel.missingPrerequisites.collectAsState()
    val watchBatteryAlert by viewModel.watchBatteryAlert.collectAsState()
    val watchBatteryLevel by viewModel.watchBatteryLevel.collectAsState()
    val linkConnectionState by viewModel.linkConnectionState.collectAsState()
    val linkActiveRole by viewModel.linkActiveRole.collectAsState()
    val deviceMode by viewModel.deviceMode.collectAsState()
    val devicePrefix by viewModel.devicePrefix.collectAsState()
    val connectedSensorCount by viewModel.connectedSensorCount.collectAsState()

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

    Scaffold { paddingValues ->
        WatermarkedBackground(modifier = Modifier.padding(paddingValues)) {
            val currentActive = activeSession
            val isServer = deviceMode == PeerRole.SERVER

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

            // A link runs in one role at a time: show the live status dot on that role's control,
            // gray on the other. Same gray/green indicator the sensors use.
            val serverDotColor = connectionStatusColor(
                if (linkActiveRole == PeerRole.SERVER) linkConnectionState else ConnectionState.DISCONNECTED
            )
            val clientDotColor = connectionStatusColor(
                if (linkActiveRole == PeerRole.CLIENT) linkConnectionState else ConnectionState.DISCONNECTED
            )

            Column(modifier = Modifier.fillMaxSize()) {
                // ── Center zone (scrolls if warnings make it tall): lockup + radial cluster ──
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Brand lockup — the gear logo stands in for the "o" of "Work".
                        VitalWorkLockup(
                            fontSize = 30.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Spacer(modifier = Modifier.height(2.dp))
                            ReadinessWarningCard(
                                missing = missingPrerequisites,
                                onFix = onFix
                            )
                            WatchBatteryWarningCard(
                                alert = watchBatteryAlert,
                                level = watchBatteryLevel
                            )
                        }

                        // ── Radial cluster: primary action in the center, satellites on the ring ──
                        Box(modifier = Modifier.size(320.dp)) {
                            RingGuide(modifier = Modifier.matchParentSize())

                            if (isServer) {
                                // Server mode is intentionally bare: hosting is the only job, Settings
                                // (with the device-mode switch) is the lone satellite.
                                RadialCenterButton(
                                    title = "Connect as Server",
                                    icon = rememberVectorPainter(Icons.Default.Wifi),
                                    onClick = onNavigateToLinkServer,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                                RadialSatellite(
                                    label = "Settings",
                                    icon = rememberVectorPainter(Icons.Default.Settings),
                                    onClick = onNavigateToSettings,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .offset(x = 0.dp, y = 126.dp)
                                )
                            } else {
                                RadialCenterButton(
                                    title = if (currentActive != null) "Resume Active Session" else "Start New Session",
                                    subtitle = elapsedLabel,
                                    icon = painterResource(R.drawable.ic_ecg),
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
                                    },
                                    modifier = Modifier.align(Alignment.Center)
                                )
                                if (deviceMode == PeerRole.CLIENT) {
                                    RadialSatellite(
                                        label = "Connect as Client",
                                        icon = rememberVectorPainter(Icons.Default.WifiFind),
                                        onClick = onNavigateToLinkClient,
                                        dotColor = clientDotColor,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .offset(x = (-68).dp, y = 110.dp)
                                    )
                                }
                                RadialSatellite(
                                    label = "Completed Sessions",
                                    icon = rememberVectorPainter(Icons.Default.Folder),
                                    onClick = onNavigateToSessions,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .offset(x = 68.dp, y = 110.dp)
                                )
                            }
                        }
                    }
                }

                // ── Bottom zone, anchored to the screen edge: quiet nav + status card ──
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!isServer) {
                        Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                            NavCircle(
                                label = "Sensors",
                                icon = rememberVectorPainter(Icons.Default.Sensors),
                                onClick = onNavigateToSensors
                            )
                            NavCircle(
                                label = "Tutorial",
                                icon = rememberVectorPainter(Icons.Default.School),
                                onClick = onNavigateToTutorial
                            )
                            NavCircle(
                                label = "Settings",
                                icon = rememberVectorPainter(Icons.Default.Settings),
                                onClick = onNavigateToSettings
                            )
                        }
                        Spacer(modifier = Modifier.height(22.dp))
                    }

                    // Status at a glance: is the device ready to measure, and which mode/prefix
                    // this tablet holds (matters when several prefixed tablets test in parallel).
                    val statusText: String
                    val statusDot: Color
                    if (isServer) {
                        when (if (linkActiveRole == PeerRole.SERVER) linkConnectionState else ConnectionState.DISCONNECTED) {
                            ConnectionState.CONNECTED -> {
                                statusText = "Monitored device connected"
                                statusDot = SuccessGreen
                            }
                            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                                statusText = "Connecting…"
                                statusDot = WarningAmber
                            }
                            else -> {
                                statusText = "Waiting for monitored device"
                                statusDot = serverDotColor
                            }
                        }
                    } else if (currentActive != null) {
                        statusText = "Session in progress — ${currentActive.sessionCode}"
                        statusDot = ActiveSessionOrange
                    } else if (missingPrerequisites.isNotEmpty()) {
                        statusText = "Setup needed — see warnings above"
                        statusDot = WarningAmber
                    } else if (connectedSensorCount == 0) {
                        statusText = "No sensors connected"
                        statusDot = MaterialTheme.colorScheme.outline
                    } else {
                        statusText = if (connectedSensorCount == 1) "1 sensor connected"
                            else "$connectedSensorCount sensors connected"
                        statusDot = SuccessGreen
                    }
                    HomeStatusCard(
                        text = statusText,
                        dotColor = statusDot,
                        modeLabel = "${if (isServer) "Server" else "Client"} · $devicePrefix",
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 18.dp)
                    )
                }
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
