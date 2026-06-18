package com.vitalwork.app.presentation.screens.tutorial

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LinkOff
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vitalwork.app.R
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.sensor.DeviceState
import com.vitalwork.app.data.sensor.ble.model.BleDevice
import com.vitalwork.app.data.sensor.watch.WatchLinkStatus
import com.vitalwork.app.presentation.screens.sensors.components.BleDeviceItem
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.viewinterop.AndroidView

// ─────────────────────────────────────────────────────────────────────────────
// Slide data model
// ─────────────────────────────────────────────────────────────────────────────

private enum class SlideType {
    WELCOME, INFO, INTERACTIVE_BLE, INTERACTIVE_RESP, INTERACTIVE_WATCH, COMPLETE
}

private enum class SlidePhase {
    WELCOME, HEART_RATE, RESPIRATION, GALAXY_WATCH, COMPLETE
}

private data class TutorialSlide(
    val type: SlideType,
    val phase: SlidePhase,
    val title: String,
    val body: String,
    @DrawableRes val imageRes: Int? = null,
    val imageCaption: String = "",
    @RawRes val videoRawRes: Int? = null
)

private val TUTORIAL_SLIDES = listOf(
    TutorialSlide(
        type = SlideType.WELCOME,
        phase = SlidePhase.WELCOME,
        title = "Welcome to VitalWork Operator",
        body = "This tutorial walks you through the complete setup for a claustrophobia exposure therapy session."
    ),

    // ── Heart Rate Sensor (eSense Pulse) — 3 slides ─────────────────────────
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.HEART_RATE,
        title = "Position the ECG Strap",
        body = "Have the patient lift their shirt. Wrap the strap horizontally just below the breast tissue, with the sensor pod slightly left of centre (towards the heart). The strap must sit directly on bare skin — adjust the tension so it is snug but does not restrict breathing.",
        imageRes = R.drawable.tutorial_hr_strap_position,
        imageCaption = "ECG strap on bare skin, wrapped below breast tissue with sensor pod left of centre"
    ),
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.HEART_RATE,
        title = "Moisten the Electrode Contacts",
        body = "If the signal is weak, gently moisten the electrode contact pads on the inside of the belt with the enclosed solution. This will improve electrical conductivity.\n",
        imageRes = R.drawable.tutorial_hr_moisten_contacts,
        imageCaption = "Fingertip moistening the inner electrode pads on the strap"
    ),
    TutorialSlide(
        type = SlideType.INTERACTIVE_BLE,
        phase = SlidePhase.HEART_RATE,
        title = "Connect Heart Rate Sensor",
        body = "Scan for and connect to the eSense Pulse over Bluetooth."
    ),

    // ── Breathing Sensor — 4 slides ──────────────────────────────────────────
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.RESPIRATION,
        title = "Plug in the Sensor Cable",
        body = "Insert the eSense Respiration cable firmly into the audio jack of the Android tablet. The sensor will not be detected unless the cable is fully seated.",
        imageRes = R.drawable.tutorial_resp_plug_cable,
        imageCaption = "Audio jack cable being plugged into the Android device"
    ),
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.RESPIRATION,
        title = "Position the Breathing Belt",
        body = "Wrap the belt around the upper abdomen, just above the navel, with the sensor box on the front of the body. The belt should be snug enough to move visibly with each breath, but not tight enough to restrict breathing or cause discomfort.",
        imageRes = R.drawable.tutorial_resp_belt_position,
        imageCaption = "Breathing belt on upper abdomen, snug but not compressing the torso"
    ),
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.RESPIRATION,
        title = "Keep Both Sensors Spaced Apart",
        body = "Leave at least a hand's width of space between the breathing belt and the ECG strap. Placing them too close causes signal interference in both sensors.",
        imageRes = R.drawable.tutorial_resp_spacing,
        imageCaption = "Both straps on body with a visible gap between them"
    ),
    TutorialSlide(
        type = SlideType.INTERACTIVE_RESP,
        phase = SlidePhase.RESPIRATION,
        title = "Connect Breathing Sensor",
        body = "Connect to the eSense Respiration sensor via the audio jack."
    ),

    // ── Galaxy Watch — 4 slides ────────────────────────────────────────────────
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.GALAXY_WATCH,
        title = "Put on the Galaxy Watch",
        body = "Make sure the VitalWork Watch app is installed on the watch, then fasten the watch snugly on the patient's wrist, a finger's width above the wrist bone. A loose fit lets the sensors lose skin contact, giving noisy heart-rate and skin-conductance readings.",
        imageRes = R.drawable.tutorial_watch_wear_position,
        imageCaption = "Watch snug, a finger above the wrist bone"
    ),
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.GALAXY_WATCH,
        title = "Turn on the Tablet's Bluetooth",
        body = "You need to pair the watch with your mobile device and connect it via Bluetooth. Turn on Bluetooth on the mobile device where you have the app, and pair the watch using the Galaxy Wearable app, which you’ll also need to download.",
        imageRes = R.drawable.tutorial_watch_bluetooth_pair,
        imageCaption = "Tablet Bluetooth on, watch paired"
    ),
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.GALAXY_WATCH,
        title = "Start Tracking on the Watch",
        body = "Open the VitalWork app on the watch and tap Start. The first time, allow Body Sensors \"all the time\". The watch then streams heart rate, inter-beat interval, and skin conductance to this tablet.",
        imageRes = R.drawable.tutorial_watch_start_tracking,
        imageCaption = "Tap Start in the VitalWork watch app"
    ),
    TutorialSlide(
        type = SlideType.INTERACTIVE_WATCH,
        phase = SlidePhase.GALAXY_WATCH,
        title = "Confirm the Watch Link",
        body = "Check that the tablet is receiving data from the watch."
    ),

    // ── Complete ──────────────────────────────────────────────────────────────
    TutorialSlide(
        type = SlideType.COMPLETE,
        phase = SlidePhase.COMPLETE,
        title = "All Set!",
        body = "Sensors are configured. You're ready to start a therapy session."
    )
)

// ─────────────────────────────────────────────────────────────────────────────
// Phase accent colors
// ─────────────────────────────────────────────────────────────────────────────

private val PhaseColorHR      = Color(0xFFE57373)   // Red 300        — Heart Rate
private val PhaseColorResp    = Color(0xFF4DB6AC)   // Teal 300       — Respiration
private val PhaseColorWatch   = Color(0xFF64B5F6)   // Blue 300       — Galaxy Watch
private val PhaseColorDefault = Color(0xFF9575CD)   // Deep Purple 300 — Welcome / Complete

private fun phaseAccentColor(phase: SlidePhase): Color = when (phase) {
    SlidePhase.HEART_RATE      -> PhaseColorHR
    SlidePhase.RESPIRATION     -> PhaseColorResp
    SlidePhase.GALAXY_WATCH    -> PhaseColorWatch
    SlidePhase.WELCOME, SlidePhase.COMPLETE -> PhaseColorDefault
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSessions: () -> Unit,
    viewModel: TutorialViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val slides = TUTORIAL_SLIDES
    val totalSteps = slides.size
    val pagerState = rememberPagerState(pageCount = { totalSteps })
    val context = LocalContext.current

    // Keep pager in sync with ViewModel step
    LaunchedEffect(uiState.currentStep) {
        pagerState.animateScrollToPage(uiState.currentStep)
    }

    // Keep ViewModel in sync when user swipes the pager
    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != uiState.currentStep) {
            viewModel.goToStep(pagerState.settledPage)
        }
    }

    // BLE permission launcher
    val blePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.setBlePermissionsGranted(results.values.all { it })
    }

    // Check BLE permissions at startup so returning users see the correct state
    LaunchedEffect(Unit) {
        val allGranted = blePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        viewModel.setBlePermissionsGranted(allGranted)
    }

    // Check audio permission at startup so returning users see the correct state
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setAudioPermissionGranted(granted)
    }

    // Audio (RECORD_AUDIO) permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setAudioPermissionGranted(granted)
    }

    // Bluetooth enable launcher
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { /* result handled reactively via BleManager.bluetoothEnabled flow */ }

    // Location settings launcher — rechecks location state when user returns
    val locationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { viewModel.recheckLocationEnabled() }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tutorial") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Progress bar
            PhaseProgressHeader(
                currentStep = uiState.currentStep,
                slides = slides,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // Page content
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = true,
                modifier = Modifier.weight(1f)
            ) { page ->
                val slide = slides[page]
                when (slide.type) {
                    SlideType.WELCOME -> TutorialWelcomeStep()
                    SlideType.INFO -> TutorialSinglePointStep(
                        title = slide.title,
                        body = slide.body,
                        imageRes = slide.imageRes,
                        imageCaption = slide.imageCaption,
                        videoRawRes = slide.videoRawRes
                    )
                    SlideType.INTERACTIVE_BLE -> TutorialPulseConnectStep(
                        uiState = uiState,
                        onRequestPermissions = { blePermissionLauncher.launch(blePermissions) },
                        onEnableBluetooth = {
                            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        },
                        onOpenLocationSettings = {
                            locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        },
                        onToggleScan = viewModel::toggleBleScan,
                        onConnectDevice = viewModel::connectBleDevice,
                        onDisconnect = viewModel::disconnectBle
                    )
                    SlideType.INTERACTIVE_RESP -> TutorialRespirationConnectStep(
                        state = uiState.respirationState,
                        audioPermissionGranted = uiState.audioPermissionGranted,
                        onRequestAudioPermission = {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onToggle = viewModel::toggleRespirationConnection
                    )
                    SlideType.INTERACTIVE_WATCH -> TutorialWatchConnectStep(
                        linkStatus = uiState.watchLinkStatus,
                        batteryLevel = uiState.watchBatteryLevel,
                        bluetoothEnabled = uiState.bluetoothEnabled,
                        onEnableBluetooth = {
                            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }
                    )
                    SlideType.COMPLETE -> TutorialCompleteStep(onGoToTests = onNavigateToSessions)
                }
            }

            // Bottom navigation bar
            val currentSlide = slides.getOrNull(uiState.currentStep)
            val isConnectionStep = currentSlide?.type in setOf(
                SlideType.INTERACTIVE_BLE, SlideType.INTERACTIVE_RESP, SlideType.INTERACTIVE_WATCH
            )
            TutorialNavigationBar(
                currentStep = uiState.currentStep,
                totalSteps = totalSteps,
                currentPhase = currentSlide?.phase ?: SlidePhase.WELCOME,
                isConnectionStep = isConnectionStep,
                onPrevious = viewModel::previousStep,
                onNext = viewModel::nextStep
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Progress indicator
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PhaseProgressHeader(
    currentStep: Int,
    slides: List<TutorialSlide>,
    modifier: Modifier = Modifier
) {
    val currentSlide = slides.getOrNull(currentStep)
    val currentPhase = currentSlide?.phase ?: SlidePhase.WELCOME
    val phaseColor = phaseAccentColor(currentPhase)
    val animatedPhaseColor by animateColorAsState(
        targetValue = phaseColor,
        animationSpec = tween(400),
        label = "phase_chip_color"
    )

    // Compute phase name, icon, and step label from slide data
    val phaseName = when (currentPhase) {
        SlidePhase.WELCOME         -> "Overview"
        SlidePhase.HEART_RATE      -> "Heart Rate Sensor"
        SlidePhase.RESPIRATION     -> "Breathing Sensor"
        SlidePhase.GALAXY_WATCH    -> "Galaxy Watch"
        SlidePhase.COMPLETE        -> "Complete"
    }
    val phaseIcon = when (currentPhase) {
        SlidePhase.COMPLETE -> Icons.Default.CheckCircle
        SlidePhase.WELCOME  -> Icons.Default.School
        else                -> Icons.Default.Sensors
    }

    // Compute step label within the current phase
    val phaseSlidesIndices = slides.indices.filter { slides[it].phase == currentPhase }
    val positionInPhase = phaseSlidesIndices.indexOf(currentStep)
    val phaseSize = phaseSlidesIndices.size
    val isInteractive = currentSlide?.type in setOf(
        SlideType.INTERACTIVE_BLE, SlideType.INTERACTIVE_RESP, SlideType.INTERACTIVE_WATCH
    )
    val phaseStepLabel = when {
        currentPhase == SlidePhase.WELCOME || currentPhase == SlidePhase.COMPLETE -> ""
        isInteractive -> "Connect"
        else -> "${positionInPhase + 1} of ${phaseSize - 1}"  // -1 to exclude the interactive step from count
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Phase chip
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(animatedPhaseColor.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = phaseIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = animatedPhaseColor
                )
                Text(
                    text = if (phaseStepLabel.isEmpty()) phaseName
                           else "$phaseName  ·  $phaseStepLabel",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = animatedPhaseColor
                )
            }
            // Overall counter
            Text(
                text = "${currentStep + 1} / ${slides.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        // Dynamic phase segments — all turn purple on the Complete step
        val isComplete = currentPhase == SlidePhase.COMPLETE
        val sensorPhases = slides
            .map { it.phase }
            .filter { it != SlidePhase.WELCOME && it != SlidePhase.COMPLETE }
            .distinct()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (phase in sensorPhases) {
                val phaseSlideCount = slides.count { it.phase == phase }
                val firstIndex = slides.indexOfFirst { it.phase == phase }
                val progress = (currentStep - firstIndex + 1).coerceIn(0, phaseSlideCount).toFloat() / phaseSlideCount
                PhaseSegment(
                    progress = progress,
                    color = if (isComplete) PhaseColorDefault else phaseAccentColor(phase),
                    modifier = Modifier.weight(phaseSlideCount.toFloat())
                )
            }
        }
    }
}

@Composable
private fun PhaseSegment(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(300),
        label = "segment_progress"
    )
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(500),
        label = "segment_color"
    )
    Box(
        modifier = modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(animatedColor.copy(alpha = 0.18f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedProgress)
                .clip(RoundedCornerShape(3.dp))
                .background(animatedColor)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Navigation bar (Previous / Skip / Next)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialNavigationBar(
    currentStep: Int,
    totalSteps: Int,
    currentPhase: SlidePhase,
    isConnectionStep: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val animatedPhaseColor by animateColorAsState(
        targetValue = phaseAccentColor(currentPhase),
        animationSpec = tween(400),
        label = "nav_phase_color"
    )
    val pillShape = RoundedCornerShape(50)

    Surface(shadowElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentStep > 0) {
                OutlinedButton(
                    onClick = onPrevious,
                    shape = pillShape,
                    border = BorderStroke(1.dp, animatedPhaseColor.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = animatedPhaseColor)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Back")
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            if (currentStep < totalSteps - 1) {
                Button(
                    onClick = onNext,
                    shape = pillShape,
                    colors = ButtonDefaults.buttonColors(containerColor = animatedPhaseColor)
                ) {
                    Text(
                        when {
                            currentStep == 0 -> "Begin Setup"
                            isConnectionStep -> "Continue"
                            else             -> "Next Step"
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 0 – Welcome
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialWelcomeStep() {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pad = if (maxWidth < 600.dp) 16.dp else 24.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Icon(
                imageVector = Icons.Default.School,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Welcome to VitalWork Operator",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "This tutorial will walk you through the complete setup for a claustrophobia exposure therapy session.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            HorizontalDivider()

            Text(
                text = "A session consists of two phases:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )

            SessionOverviewItem(
                number = "1",
                icon = Icons.Default.Sensors,
                label = "Prepare & connect biosensors",
                detail = "eSense Pulse, eSense Respiration, and the Galaxy Watch"
            )
            SessionOverviewItem(
                number = "2",
                icon = Icons.Default.Folder,
                label = "Start a therapy test session",
                detail = "Record physiological data during the session"
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SessionOverviewItem(
    number: String,
    icon: ImageVector,
    label: String,
    detail: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Looping video player (muted) for raw resource videos
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VideoLoopPlayer(@RawRes rawRes: Int, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also { surfaceView ->
                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    private var player: MediaPlayer? = null

                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val mp = MediaPlayer()
                        val afd = ctx.resources.openRawResourceFd(rawRes)
                        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                        mp.setDisplay(holder)
                        mp.isLooping = true
                        mp.setVolume(0f, 0f)
                        mp.prepare()
                        mp.start()
                        player = mp
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        player?.release()
                        player = null
                    }
                })
            }
        },
        modifier = modifier
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Single-point info step – large image + title + one instruction
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialSinglePointStep(
    title: String,
    body: String,
    @DrawableRes imageRes: Int?,
    imageCaption: String,
    @RawRes videoRawRes: Int? = null
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pad = if (maxWidth < 600.dp) 16.dp else 24.dp
        val imageHeight = if (maxHeight > 500.dp) 260.dp else 180.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = pad, vertical = pad),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(imageHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                when {
                    videoRawRes != null -> VideoLoopPlayer(
                        rawRes = videoRawRes,
                        modifier = Modifier.fillMaxSize()
                    )
                    imageRes != null -> Image(
                        painter = painterResource(id = imageRes),
                        contentDescription = imageCaption,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        )
                        Text(
                            text = imageCaption,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 2 – Connect eSense Pulse (BLE)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialPulseConnectStep(
    uiState: TutorialUiState,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onToggleScan: () -> Unit,
    onConnectDevice: (BleDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pad = if (maxWidth < 600.dp) 16.dp else 24.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Connect Heart Rate Sensor",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            DeviceStatusCard(
                label = "eSense Pulse",
                connectionState = uiState.bleConnectionState,
                deviceState = null
            )

            // 1. Permissions status
            if (uiState.blePermissionsGranted) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Bluetooth & Location permissions already granted",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Bluetooth & Location permissions required",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Grant Bluetooth and Location permissions so the app can scan for and connect to the eSense Pulse sensor.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(
                            onClick = onRequestPermissions,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Grant Permissions")
                        }
                    }
                }
            }

            // 2. Bluetooth disabled warning
            if (uiState.blePermissionsGranted && !uiState.bluetoothEnabled) {
                Card(
                    onClick = onEnableBluetooth,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.BluetoothDisabled,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bluetooth Disabled",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Bluetooth must be enabled to scan for the eSense Pulse sensor.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // 3. Location disabled warning
            if (uiState.blePermissionsGranted && !uiState.locationEnabled) {
                Card(
                    onClick = onOpenLocationSettings,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOff,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Location Services Disabled",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Location Services must be enabled for BLE scanning to work.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // 4. Connection / scan UI (only when permissions granted and BT enabled)
            if (uiState.blePermissionsGranted && uiState.bluetoothEnabled) {
                when (uiState.bleConnectionState) {
                    ConnectionState.CONNECTED -> {
                        Text(
                            text = "Heart rate sensor is connected. You can proceed to the next step or disconnect.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Disconnect")
                        }
                    }

                    ConnectionState.CONNECTING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = "Connecting to device…",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    else -> {
                        Button(
                            onClick = onToggleScan,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (uiState.isScanning) Icons.Default.Stop else Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (uiState.isScanning) "Stop Scanning" else "Scan for Devices")
                        }

                        if (uiState.isScanning && uiState.scannedDevices.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "Scanning for eSense devices…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (uiState.scannedDevices.isNotEmpty()) {
                            Text(
                                text = "Found devices — tap to connect:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            uiState.scannedDevices.forEach { device ->
                                BleDeviceItem(
                                    device = device,
                                    onClick = { onConnectDevice(device) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 4 – Connect eSense Respiration (audio jack)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialRespirationConnectStep(
    state: DeviceState,
    audioPermissionGranted: Boolean,
    onRequestAudioPermission: () -> Unit,
    onToggle: () -> Unit
) {
    val isConnected = state == DeviceState.Connected || state == DeviceState.Streaming
    val isConnecting = state == DeviceState.Connecting

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pad = if (maxWidth < 600.dp) 16.dp else 24.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Connect Breathing Sensor",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            DeviceStatusCard(
                label = "eSense Respiration",
                connectionState = null,
                deviceState = state
            )

            if (audioPermissionGranted) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Microphone permission already granted",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Microphone permission required",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "The audio jack sensor needs microphone access to receive breathing data.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(
                            onClick = onRequestAudioPermission,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
            }

            Text(
                text = "Make sure the eSense Respiration sensor is plugged into the audio jack before tapping Connect.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnecting,
                colors = if (isConnected) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting…")
                } else {
                    Text(if (isConnected) "Disconnect" else "Connect")
                }
            }

            if (isConnected) {
                Text(
                    text = "Breathing sensor connected. You can proceed to the next step.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 6 – Confirm Galaxy Watch link (passive — no tablet-side connect)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialWatchConnectStep(
    linkStatus: WatchLinkStatus,
    batteryLevel: Int?,
    bluetoothEnabled: Boolean,
    onEnableBluetooth: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pad = if (maxWidth < 600.dp) 16.dp else 24.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Confirm the Watch Link",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            WatchStatusCard(linkStatus = linkStatus, batteryLevel = batteryLevel)

            // Bluetooth disabled warning — the watch link runs over direct Bluetooth; without it the
            // link falls back to a cloud relay that can't deliver to a sleeping tablet. Same card and
            // behaviour as the heart-rate step.
            if (!bluetoothEnabled) {
                Card(
                    onClick = onEnableBluetooth,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.BluetoothDisabled,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bluetooth Disabled",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Turn on Bluetooth so the watch can stream over the direct link.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            when (linkStatus) {
                WatchLinkStatus.LIVE -> Text(
                    text = "The watch is connected and streaming. You can proceed to the next step.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                WatchLinkStatus.DOZING -> Text(
                    text = "Connected but dozing — readings are buffering and will catch up. Normal when the watch screen is off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                WatchLinkStatus.DISCONNECTED -> Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "There's no connect button here — open the VitalWork app on the watch and tap Start, and readings will arrive automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Listening for the watch…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Watch link status card — mirrors [DeviceStatusCard] but renders the three-way LIVE/DOZING/gone state. */
@Composable
private fun WatchStatusCard(
    linkStatus: WatchLinkStatus,
    batteryLevel: Int?
) {
    val statusText = when (linkStatus) {
        WatchLinkStatus.LIVE -> "Connected"
        WatchLinkStatus.DOZING -> "Dozing — buffering"
        WatchLinkStatus.DISCONNECTED -> "Waiting for data…"
    }
    val dotColor by animateColorAsState(
        targetValue = when (linkStatus) {
            WatchLinkStatus.LIVE -> Color(0xFF4CAF50)
            WatchLinkStatus.DOZING -> Color(0xFFFFA000)
            WatchLinkStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        },
        animationSpec = tween(300),
        label = "watch_dot_color"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(dotColor, CircleShape)
                )
                Icon(
                    imageVector = Icons.Default.Watch,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Galaxy Watch",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            batteryLevel?.let {
                Text(
                    text = "Watch battery: $it%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 7 – Complete
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialCompleteStep(onGoToTests: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pad = if (maxWidth < 600.dp) 16.dp else 24.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = Color(0xFF4CAF50)
            )

            Text(
                text = "All Set!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Sensors are configured. You're ready to start a therapy session.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onGoToTests,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Go to Tests")
            }

            Text(
                text = "You can always return to this tutorial from the Home screen. To switch heart rate sensor, tap the HR Device button on the Home screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared – device status card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeviceStatusCard(
    label: String,
    connectionState: ConnectionState?,
    deviceState: DeviceState?
) {
    val isConnected = connectionState == ConnectionState.CONNECTED ||
        deviceState == DeviceState.Connected ||
        deviceState == DeviceState.Streaming

    val isConnecting = connectionState == ConnectionState.CONNECTING ||
        deviceState == DeviceState.Connecting

    val statusText = when {
        connectionState != null -> when (connectionState) {
            ConnectionState.CONNECTED -> "Connected"
            ConnectionState.CONNECTING -> "Connecting…"
            ConnectionState.RECONNECTING -> "Reconnecting"
            ConnectionState.ERROR -> "Error"
            ConnectionState.DISCONNECTED -> "Disconnected"
        }
        deviceState != null -> when (deviceState) {
            DeviceState.Connected, DeviceState.Streaming -> "Connected"
            DeviceState.Connecting -> "Connecting…"
            DeviceState.Error -> "Error"
            DeviceState.Disconnected -> "Disconnected"
        }
        else -> "Unknown"
    }

    val dotColor by animateColorAsState(
        targetValue = when {
            isConnected -> Color(0xFF4CAF50)
            isConnecting -> Color(0xFFFFA000)
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        },
        animationSpec = tween(300),
        label = "dot_color"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(dotColor, CircleShape)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
