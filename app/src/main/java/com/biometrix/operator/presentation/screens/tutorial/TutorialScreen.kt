package com.biometrix.operator.presentation.screens.tutorial

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
import androidx.compose.material.icons.filled.Vrpano
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
import com.biometrix.operator.R
import com.biometrix.operator.data.prefs.HeartRateDevice
import com.biometrix.operator.data.model.BloodPressureReading
import com.biometrix.operator.data.model.ConnectionState
import com.biometrix.operator.data.sensor.DeviceState
import com.biometrix.operator.data.sensor.ble.Bc87State
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import com.biometrix.operator.data.vr.model.DiscoveredVrDevice
import com.biometrix.operator.presentation.screens.sensors.components.BleDeviceItem
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.viewinterop.AndroidView

// ─────────────────────────────────────────────────────────────────────────────
// Slide data model
// ─────────────────────────────────────────────────────────────────────────────

private enum class SlideType {
    WELCOME, DEVICE_SELECTION, INFO, INTERACTIVE_BLE, INTERACTIVE_RESP, INTERACTIVE_BC87, INTERACTIVE_FIBION, INTERACTIVE_VR, COMPLETE
}

private enum class SlidePhase {
    WELCOME, HEART_RATE, RESPIRATION, BLOOD_PRESSURE, FIBION_FLASH, VR, COMPLETE
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

private val ALL_TUTORIAL_SLIDES = listOf(
    TutorialSlide(
        type = SlideType.WELCOME,
        phase = SlidePhase.WELCOME,
        title = "Welcome to BioMetrix Operator",
        body = "This tutorial walks you through the complete setup for a claustrophobia exposure therapy session."
    ),

    // ── Device Selection — 1 slide (shown only on first use) ────────────────
    TutorialSlide(
        type = SlideType.DEVICE_SELECTION,
        phase = SlidePhase.WELCOME,
        title = "Choose Your Heart Rate Sensor",
        body = "Select which chest strap sensor you will use. The tutorial will adapt to show setup instructions for your device."
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

    // ── Fibion Flash — 3 slides ──────────────────────────────────────────────
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.FIBION_FLASH,
        title = "Position the Fibion Flash Belt",
        body = "Have the patient lift their shirt. Wrap the belt horizontally just below the breast tissue, with the sensor module slightly left of centre (towards the heart). The belt must sit directly on bare skin — adjust the tension so it is snug but does not restrict breathing.",
        imageRes = R.drawable.tutorial_fibion_belt_position,
        imageCaption = "Fibion Flash belt on bare skin, wrapped below breast tissue with sensor module left of centre"
    ),
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.FIBION_FLASH,
        title = "Moisten the Electrode Contacts",
        body = "If the signal is weak, gently moisten the electrode contact pads on the inside of the belt with water. This will improve electrical conductivity.",
        imageRes = R.drawable.tutorial_fibion_moisten_contacts,
        imageCaption = "Fingertip moistening the inner electrode pads on the belt"
    ),
    TutorialSlide(
        type = SlideType.INTERACTIVE_FIBION,
        phase = SlidePhase.FIBION_FLASH,
        title = "Connect Fibion Flash",
        body = "Scan for and connect to the Fibion Flash (Movesense) over Bluetooth."
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

    // ── Blood Pressure — 4 slides ────────────────────────────────────────────
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.BLOOD_PRESSURE,
        title = "Prepare the BP Monitor",
        body = "Insert the two AAA batteries — keep a spare set on hand, as the device will display a low battery warning and refuse to take a measurement if they run out. With the device off, hold the OK button for approximately 5 seconds to open the settings menu. Use M1/M2 to change each value and OK to confirm and advance:\n\n1. Hour format — 12h or 24h\n2–6. Date and time — year, month, day, hour, minutes\n7. Bluetooth — set to ON (required for the app to find the device)\n8. User — select M1 or M2 (the memory slot where readings will be saved)\n\nThe device saves all settings and returns to standby automatically after step 8.",
        imageRes = R.drawable.tutorial_bc87_prepare,
        imageCaption = "BC 87 device with battery compartment open and Bluetooth settings menu visible"
    ),
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.BLOOD_PRESSURE,
        title = "Wear the Wrist BP Monitor",
        body = "Place the BC 87 on your left wrist, 1–2 cm above the wrist bone, display facing up. Fasten the strap snugly — one finger should fit underneath.\n\nRaise your forearm to heart level until the OK button lights up green, confirming the correct setting. Keep your arm still and relaxed throughout the measurement.",
        imageRes = R.drawable.tutorial_bc87_wear,
        imageCaption = "BC 87 on inside of wrist, 1–2 cm above wrist bone, display facing upward"
    ),
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.BLOOD_PRESSURE,
        title = "Take a Blood Pressure Reading",
        body = "Press OK to start. Keep your arm still while the cuff inflates and deflates (~30–60 s).\n\nWhen the result appears, press OK again — this saves the reading to device memory and activates Bluetooth transmission (limited to 30 seconds), after which it will turn itself off.\n\nIn the next step, you can try out how this whole process works.",
        imageRes = R.drawable.tutorial_bc87_reading,
        imageCaption = "BC 87 display showing systolic/diastolic reading; OK button highlighted"
    ),
    TutorialSlide(
        type = SlideType.INTERACTIVE_BC87,
        phase = SlidePhase.BLOOD_PRESSURE,
        title = "Connect Blood Pressure Monitor",
        body = "Test the BC 87 connection by taking a measurement and letting the app retrieve it."
    ),

    // ── VR Headset — 4 slides ───────────────────────────────────────────────
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.VR,
        title = "Launch the StressChamber App",
        body = "Make sure both this mobile device and the VR headset are connected to the same Wi-Fi network. Then put the VR headset on your head and find the StressChamber application in the headset app library. Launch it and wait for it to fully load before proceeding.",
        videoRawRes = R.raw.tutorial_vr_launch_app,
        imageCaption = "StressChamber in app launcher, or app loading screen"
    ),
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.VR,
        title = "Patient Fits the Headset",
        body = "Take off the headset and hand it to the patient. The patient puts it on and adjusts the head strap for a comfortable, stable fit.",
        imageRes = R.drawable.tutorial_vr_patient_headset,
        imageCaption = "Patient putting on Meta Quest, adjusting the head strap"
    ),
    TutorialSlide(
        type = SlideType.INFO,
        phase = SlidePhase.VR,
        title = "Recenter the View",
        body = "Ask the patient to press and hold the Meta button on the right controller until the view resets to the forward-facing position.",
        imageRes = R.drawable.tutorial_vr_recenter,
        imageCaption = "Right controller with Meta (Oculus) button highlighted"
    ),
    TutorialSlide(
        type = SlideType.INTERACTIVE_VR,
        phase = SlidePhase.VR,
        title = "Connect to VR Headset",
        body = "The app will automatically scan for VR headsets on the network. Select your headset from the list to connect."
    ),

    // ── Complete ──────────────────────────────────────────────────────────────
    TutorialSlide(
        type = SlideType.COMPLETE,
        phase = SlidePhase.COMPLETE,
        title = "All Set!",
        body = "Sensors and VR are configured. You're ready to start a therapy session."
    )
)

private fun filteredTutorialSlides(
    device: HeartRateDevice,
    includeDeviceSelection: Boolean
): List<TutorialSlide> =
    ALL_TUTORIAL_SLIDES.filter { slide ->
        when {
            slide.type == SlideType.DEVICE_SELECTION && !includeDeviceSelection -> false
            device == HeartRateDevice.ESENSE_PULSE -> slide.phase != SlidePhase.FIBION_FLASH
            device == HeartRateDevice.FIBION_FLASH -> slide.phase != SlidePhase.HEART_RATE
            else -> true
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Phase accent colors
// ─────────────────────────────────────────────────────────────────────────────

private val PhaseColorHR      = Color(0xFFE57373)   // Red 300        — Heart Rate
private val PhaseColorResp    = Color(0xFF4DB6AC)   // Teal 300       — Respiration
private val PhaseColorBP      = Color(0xFF7986CB)   // Indigo 300     — Blood Pressure
private val PhaseColorFibion  = Color(0xFF81C784)   // Green 300      — Fibion Flash
private val PhaseColorVR      = Color(0xFFFFB74D)   // Orange 300     — VR
private val PhaseColorDefault = Color(0xFF9575CD)   // Deep Purple 300 — Welcome / Complete

private fun phaseAccentColor(phase: SlidePhase): Color = when (phase) {
    SlidePhase.HEART_RATE      -> PhaseColorHR
    SlidePhase.RESPIRATION     -> PhaseColorResp
    SlidePhase.BLOOD_PRESSURE  -> PhaseColorBP
    SlidePhase.FIBION_FLASH    -> PhaseColorFibion
    SlidePhase.VR              -> PhaseColorVR
    SlidePhase.WELCOME, SlidePhase.COMPLETE -> PhaseColorDefault
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTests: () -> Unit,
    viewModel: TutorialViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedHrDevice by viewModel.selectedHeartRateDevice.collectAsState()
    val showDeviceSelection = remember { viewModel.showDeviceSelectionSlide }
    val slides = remember(selectedHrDevice, showDeviceSelection) {
        filteredTutorialSlides(selectedHrDevice, includeDeviceSelection = showDeviceSelection)
    }
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

    // WiFi settings launcher — NetworkCallback in MdnsDiscoveryService handles auto-restart
    val wifiSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* NetworkCallback handles auto-restart */ }

    @Suppress("DEPRECATION")
    val wifiSettingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Intent(Settings.Panel.ACTION_WIFI)
    } else {
        Intent(Settings.ACTION_WIFI_SETTINGS)
    }

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
                    SlideType.DEVICE_SELECTION -> TutorialDeviceSelectionStep(
                        currentDevice = selectedHrDevice,
                        onSelectDevice = viewModel::selectHeartRateDevice
                    )
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
                    SlideType.INTERACTIVE_BC87 -> TutorialBc87ConnectStep(
                        uiState = uiState,
                        onRequestPermissions = { blePermissionLauncher.launch(blePermissions) },
                        onEnableBluetooth = {
                            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        },
                        onOpenLocationSettings = {
                            locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        },
                        onStartScan = viewModel::startBc87Scanning,
                        onStopScan = viewModel::stopBc87Scanning
                    )
                    SlideType.INTERACTIVE_FIBION -> TutorialFibionFlashConnectStep(
                        uiState = uiState,
                        onRequestPermissions = { blePermissionLauncher.launch(blePermissions) },
                        onEnableBluetooth = {
                            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        },
                        onOpenLocationSettings = {
                            locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        },
                        onToggleScan = viewModel::toggleFibionScan,
                        onConnectDevice = viewModel::connectFibionDevice,
                        onDisconnect = viewModel::disconnectFibion
                    )
                    SlideType.INTERACTIVE_VR -> TutorialVrConnectStep(
                        connectionState = uiState.vrConnectionState,
                        discoveredDevices = uiState.discoveredVrDevices,
                        isDiscovering = uiState.isVrDiscovering,
                        selectedDevice = uiState.selectedVrDevice,
                        isWifiAvailable = uiState.isWifiAvailable,
                        onSelectDevice = viewModel::selectAndConnectVrDevice,
                        onRescan = viewModel::rescanVrDevices,
                        onDisconnect = viewModel::disconnectVr,
                        onOpenWifiSettings = { wifiSettingsLauncher.launch(wifiSettingsIntent) }
                    )
                    SlideType.COMPLETE -> TutorialCompleteStep(onGoToTests = onNavigateToTests)
                }
            }

            // Bottom navigation bar
            val currentSlide = slides.getOrNull(uiState.currentStep)
            val isConnectionStep = currentSlide?.type in setOf(
                SlideType.INTERACTIVE_BLE, SlideType.INTERACTIVE_RESP,
                SlideType.INTERACTIVE_BC87, SlideType.INTERACTIVE_FIBION,
                SlideType.INTERACTIVE_VR
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
        SlidePhase.BLOOD_PRESSURE  -> "Blood Pressure"
        SlidePhase.FIBION_FLASH    -> "Fibion Flash"
        SlidePhase.VR              -> "VR Headset"
        SlidePhase.COMPLETE        -> "Complete"
    }
    val phaseIcon = when (currentPhase) {
        SlidePhase.VR       -> Icons.Default.Vrpano
        SlidePhase.COMPLETE -> Icons.Default.CheckCircle
        SlidePhase.WELCOME  -> Icons.Default.School
        else                -> Icons.Default.Sensors
    }

    // Compute step label within the current phase
    val phaseSlidesIndices = slides.indices.filter { slides[it].phase == currentPhase }
    val positionInPhase = phaseSlidesIndices.indexOf(currentStep)
    val phaseSize = phaseSlidesIndices.size
    val isInteractive = currentSlide?.type in setOf(
        SlideType.INTERACTIVE_BLE, SlideType.INTERACTIVE_RESP,
        SlideType.INTERACTIVE_BC87, SlideType.INTERACTIVE_FIBION,
        SlideType.INTERACTIVE_VR
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
                text = "Welcome to BioMetrix Operator",
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
                text = "A session consists of three phases:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )

            SessionOverviewItem(
                number = "1",
                icon = Icons.Default.Sensors,
                label = "Prepare & connect biosensors",
                detail = "eSense Pulse, eSense Respiration, Beurer BC 87, and Fibion Flash"
            )
            SessionOverviewItem(
                number = "2",
                icon = Icons.Default.Vrpano,
                label = "Set up & connect the VR headset",
                detail = "Meta Quest running the BioMetrix VR application"
            )
            SessionOverviewItem(
                number = "3",
                icon = Icons.Default.Folder,
                label = "Start a therapy test session",
                detail = "Record physiological data while running VR scenarios"
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
// Device selection step (first-time users)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialDeviceSelectionStep(
    currentDevice: HeartRateDevice,
    onSelectDevice: (HeartRateDevice) -> Unit
) {
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
                imageVector = Icons.Default.FavoriteBorder,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = PhaseColorHR
            )

            Text(
                text = "Choose Your Heart Rate Sensor",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Select which chest strap sensor you will use. The tutorial will adapt to show setup instructions for your device.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            DeviceSelectionCard(
                label = "eSense Pulse",
                description = "Chest strap — Mindfield (BLE)",
                isSelected = currentDevice == HeartRateDevice.ESENSE_PULSE,
                onClick = { onSelectDevice(HeartRateDevice.ESENSE_PULSE) }
            )

            DeviceSelectionCard(
                label = "Fibion Flash",
                description = "Chest strap — Fibion / Movesense (BLE)",
                isSelected = currentDevice == HeartRateDevice.FIBION_FLASH,
                onClick = { onSelectDevice(HeartRateDevice.FIBION_FLASH) }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "You can change this anytime from the Home screen using the HR Device button.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DeviceSelectionCard(
    label: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        label = "cardBorder"
    )
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = isSelected, onClick = onClick)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
// Step 11 – Connect BC 87 Blood Pressure Monitor (BLE)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialBc87ConnectStep(
    uiState: TutorialUiState,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit
) {
    // Stop scanning automatically once a reading is received
    LaunchedEffect(uiState.bc87LastReading) {
        if (uiState.bc87LastReading != null && uiState.bc87State !is Bc87State.Idle) {
            onStopScan()
        }
    }

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
                text = "Connect Blood Pressure Monitor",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // BC 87 status card
            Bc87StatusCard(state = uiState.bc87State)

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
                            text = "Grant Bluetooth and Location permissions so the app can scan for and connect to the BC 87.",
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
                                text = "Bluetooth must be enabled to scan for the BC 87.",
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

            // 4. Scan control (only when prerequisites are met)
            if (uiState.blePermissionsGranted && uiState.bluetoothEnabled && uiState.locationEnabled) {
                when (uiState.bc87State) {
                    is Bc87State.Idle -> {
                        // Instructions card
                        if (uiState.bc87LastReading == null) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "How it works",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = "Press Start Scanning below, then take a measurement on the BC 87 and press OK to save. The device will briefly activate Bluetooth and the app will retrieve your reading automatically.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = onStartScan,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Scanning")
                        }
                    }

                    is Bc87State.Scanning -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Waiting for BC 87 to transmit...\nTake a measurement on the device and press OK to save.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedButton(
                            onClick = onStopScan
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Stop Scanning")
                        }
                    }

                    is Bc87State.Connecting -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Connecting to BC 87...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    is Bc87State.Receiving -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Receiving blood pressure data...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    is Bc87State.Error -> {
                        Text(
                            text = "Error: ${uiState.bc87State.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = onStartScan,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Scan")
                        }
                    }
                }
            }

            // 5. Reading display (shown whenever a reading exists)
            uiState.bc87LastReading?.let { reading ->
                Bc87ReadingCard(reading = reading)

                if (uiState.bc87State is Bc87State.Idle) {
                    OutlinedButton(
                        onClick = onStartScan,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Again")
                    }
                }
            }
        }
    }
}

@Composable
private fun Bc87StatusCard(state: Bc87State) {
    val (statusText, dotColor) = when (state) {
        is Bc87State.Idle -> "Ready" to Color.Gray
        is Bc87State.Scanning -> "Scanning..." to Color(0xFFFFA000)
        is Bc87State.Connecting -> "Connecting..." to Color(0xFFFFA000)
        is Bc87State.Receiving -> "Receiving..." to Color(0xFF2196F3)
        is Bc87State.Error -> "Error" to Color(0xFFF44336)
    }

    val isAnimating = state is Bc87State.Scanning || state is Bc87State.Connecting || state is Bc87State.Receiving
    val infiniteTransition = rememberInfiniteTransition(label = "bc87_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bc87_dot_pulse"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isAnimating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = dotColor
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(dotColor.copy(alpha = if (isAnimating) pulseAlpha else 1f), CircleShape)
                )
            }
            Column {
                Text(
                    text = "Beurer BC 87",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is Bc87State.Error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Bc87ReadingCard(reading: BloodPressureReading) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF4CAF50).copy(alpha = 0.12f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Reading Received",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF2E7D32)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${reading.systolicMmHg}/${reading.diastolicMmHg}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "mmHg",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                reading.pulseRateBpm?.let { pulse ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$pulse",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Pulse bpm",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                reading.meanArterialMmHg?.let { map ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$map",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "MAP mmHg",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step 13 – Connect Fibion Flash (BLE via MDS)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TutorialFibionFlashConnectStep(
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
                text = "Connect Fibion Flash",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            DeviceStatusCard(
                label = "Fibion Flash" + (uiState.fibionDeviceSerial?.let { " · $it" } ?: ""),
                connectionState = uiState.fibionConnectionState,
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
                            text = "Grant Bluetooth and Location permissions so the app can scan for and connect to the Fibion Flash.",
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
                                text = "Bluetooth must be enabled to scan for the Fibion Flash.",
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
                when (uiState.fibionConnectionState) {
                    ConnectionState.CONNECTED -> {
                        Text(
                            text = "Fibion Flash connected. You can proceed to the next step or disconnect.",
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
                                if (uiState.fibionIsScanning) Icons.Default.Stop else Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (uiState.fibionIsScanning) "Stop Scanning" else "Scan for Devices")
                        }

                        if (uiState.fibionIsScanning && uiState.fibionScannedDevices.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "Scanning for Fibion Flash (Movesense) devices…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (uiState.fibionScannedDevices.isNotEmpty()) {
                            Text(
                                text = "Found devices — tap to connect:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            uiState.fibionScannedDevices.forEach { device ->
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
// Step 6 – Connect VR Headset
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TutorialVrConnectStep(
    connectionState: ConnectionState,
    discoveredDevices: List<DiscoveredVrDevice>,
    isDiscovering: Boolean,
    selectedDevice: DiscoveredVrDevice?,
    isWifiAvailable: Boolean,
    onSelectDevice: (DiscoveredVrDevice) -> Unit,
    onRescan: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenWifiSettings: () -> Unit
) {
    val isConnectedOrConnecting = connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.CONNECTING

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
                text = "Connect to VR Headset",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            if (!isWifiAvailable && !isConnectedOrConnecting) {
                Card(
                    onClick = onOpenWifiSettings,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiOff,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Wi-Fi Disabled",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Tap here to enable Wi-Fi.",
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

            DeviceStatusCard(
                label = "Meta Quest (StressChamber)",
                connectionState = connectionState,
                deviceState = null
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (isConnectedOrConnecting) {
                        if (selectedDevice != null) {
                            Text(
                                text = selectedDevice.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${selectedDevice.host}:${selectedDevice.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        Button(
                            onClick = onDisconnect,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = connectionState != ConnectionState.CONNECTING,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.LinkOff,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "DISCONNECT", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isDiscovering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Scanning for VR devices...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = "No devices found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = onRescan,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = "Rescan")
                            }
                        }
                        if (discoveredDevices.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            discoveredDevices.forEachIndexed { index, device ->
                                Card(
                                    onClick = { onSelectDevice(device) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = device.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "${device.host}:${device.port}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Connect",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (index < discoveredDevices.lastIndex) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (connectionState == ConnectionState.CONNECTED) {
                Text(
                    text = "VR headset connected. You can proceed to the final step.",
                    style = MaterialTheme.typography.bodyMedium,
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
                text = "Sensors and VR are configured. You're ready to start a therapy session.",
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
