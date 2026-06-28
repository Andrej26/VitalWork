package com.vitalwork.app.presentation.screens.link

import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.vitalwork.app.data.link.PeerRole
import com.vitalwork.app.data.link.model.PeerDevice
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.webrtc.model.ShareState
import com.vitalwork.app.presentation.components.ConnectionStatusBadge
import com.vitalwork.app.service.BatteryOptimizationHelper
import com.vitalwork.app.service.ScreenDimController
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerLinkScreen(
    onNavigateBack: () -> Unit,
    viewModel: PeerLinkViewModel = hiltViewModel()
) {
    val role = viewModel.role
    val connectionState by viewModel.connectionState.collectAsState()
    val devices by viewModel.discoveredDevices.collectAsState()
    val peerLabel by viewModel.peerLabel.collectAsState()
    val isActive by viewModel.isActive.collectAsState()
    val shareState by viewModel.shareState.collectAsState()
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsState()

    val context = LocalContext.current
    val batteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)

    // Client: when the server requests this device's screen, launch Android's consent dialog.
    if (role == PeerRole.CLIENT) {
        val projectionManager = remember {
            context.getSystemService(MediaProjectionManager::class.java)
        }
        val consentLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                viewModel.onScreenConsent(result.resultCode, data)
            } else {
                viewModel.onScreenConsentDenied()
            }
        }
        LaunchedEffect(Unit) {
            viewModel.screenRequested.collect {
                viewModel.consumeScreenRequest()
                consentLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (role == PeerRole.SERVER) "Link — Server" else "Link — Client",
                        fontWeight = FontWeight.SemiBold
                    )
                },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!batteryExempt) {
                BatteryReminderCard(
                    onAllow = { BatteryOptimizationHelper.openExemptionSettings(context) }
                )
            }

            StatusCard(role = role, connectionState = connectionState, peerLabel = peerLabel)

            if (role == PeerRole.SERVER) {
                ScreenMonitorCard(
                    // While viewing, let the card grow to fill the screen so the mirror is as large
                    // as possible; collapse to natural height when there's nothing to show.
                    modifier = if (remoteVideoTrack != null) Modifier.weight(1f) else Modifier,
                    connected = connectionState == ConnectionState.CONNECTED,
                    shareState = shareState,
                    remoteVideoTrack = remoteVideoTrack,
                    eglBase = viewModel.eglBase,
                    onView = viewModel::requestScreen,
                    onStop = viewModel::stopShare
                )
            } else if (shareState == ShareState.SHARING) {
                SharingCard(onStop = viewModel::stopShare)
            }

            if (role == PeerRole.CLIENT && connectionState != ConnectionState.CONNECTED) {
                DiscoveredDevicesCard(devices = devices, onSelect = viewModel::onDeviceSelected)
            }

            if (role == PeerRole.SERVER) {
                // Server is started manually: Connect to host, Disconnect to stop.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = viewModel::connect,
                        enabled = !isActive,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Text("  Connect")
                    }
                    OutlinedButton(
                        onClick = viewModel::disconnect,
                        enabled = isActive,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null)
                        Text("  Disconnect")
                    }
                }
            } else if (isActive) {
                // Client connects by tapping a discovered device; only needs Disconnect.
                OutlinedButton(
                    onClick = viewModel::disconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.LinkOff, contentDescription = null)
                    Text("  Disconnect")
                }
            }
        }
    }
}

@Composable
private fun BatteryReminderCard(onAllow: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Allow background",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "This device may kill the connection when the screen is off. Exempt VitalWork " +
                    "from battery optimization to keep the link alive in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(onClick = onAllow) { Text("Allow") }
        }
    }
}

@Composable
private fun ScreenMonitorCard(
    connected: Boolean,
    shareState: ShareState,
    remoteVideoTrack: VideoTrack?,
    eglBase: EglBase,
    onView: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Screen monitor",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (remoteVideoTrack != null) {
                // Fills the space the card was given; the renderer sizes itself to the *real* incoming
                // frame aspect (portrait phone → tall rectangle) and is centered, so the whole screen
                // shows. weight(1f) lets the mirror grow to fill the available height.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    ScreenVideoView(
                        track = remoteVideoTrack,
                        eglBase = eglBase
                    )
                }
            } else {
                Text(
                    text = when (shareState) {
                        ShareState.REQUESTING -> "Waiting for the other device to allow screen sharing…"
                        ShareState.ERROR -> "Screen sharing failed."
                        else -> "Not viewing. Tap \"View screen\" to monitor the connected device."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (shareState == ShareState.IDLE || shareState == ShareState.ERROR) {
                Button(
                    onClick = onView,
                    enabled = connected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    Icon(Icons.Default.ScreenShare, contentDescription = null)
                    Text(
                        text = "  View screen",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.StopScreenShare, contentDescription = null)
                    Text("  Stop viewing")
                }
            }
        }
    }
}

@Composable
private fun SharingCard(onStop: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Sharing your screen",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = "The screen stays on (dimmed) while sharing, so the operator keeps seeing it " +
                    "even when it looks off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            // Dimming the backlight to near-black needs the "Modify system settings" special access.
            // Without it the screen still stays on, just at normal brightness.
            val context = LocalContext.current
            if (!ScreenDimController.canDim(context)) {
                Text(
                    text = "To dim this screen to near-black while sharing, allow \"Modify system " +
                        "settings\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                OutlinedButton(
                    onClick = { ScreenDimController.openWriteSettings(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Allow dark screen")
                }
            }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.StopScreenShare, contentDescription = null)
                Text("  Stop sharing")
            }
        }
    }
}

/** Renders a remote [VideoTrack] in a WebRTC [SurfaceViewRenderer]; keyed on the track so a new
 *  session recreates the surface, and the sink is removed + the renderer released on dispose. */
@Composable
private fun ScreenVideoView(track: VideoTrack, eglBase: EglBase, modifier: Modifier = Modifier) {
    // Aspect ratio (w/h) of the incoming stream. Starts at 9:16 portrait (the common phone case) and
    // is corrected from the first frame's real resolution, so the renderer matches the phone's shape
    // exactly — combined with SCALE_ASPECT_FIT the entire screen is shown with no crop.
    var videoAspect by remember(track) { mutableFloatStateOf(9f / 16f) }
    key(track) {
        AndroidView(
            modifier = modifier.aspectRatio(videoAspect),
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() {}
                        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                            // rotation 90/270 means the frame is delivered sideways — swap to get the
                            // displayed dimensions.
                            val w = if (rotation % 180 == 0) width else height
                            val h = if (rotation % 180 == 0) height else width
                            if (w > 0 && h > 0) videoAspect = w.toFloat() / h.toFloat()
                        }
                    })
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                    track.addSink(this)
                }
            },
            onRelease = { renderer ->
                runCatching { track.removeSink(renderer) }
                renderer.release()
            }
        )
    }
}

@Composable
private fun StatusCard(
    role: PeerRole,
    connectionState: ConnectionState,
    peerLabel: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Gray (disconnected) / green (connected) dot — same indicator the sensors use.
            ConnectionStatusBadge(state = connectionState)
            val labelPrefix = if (role == PeerRole.SERVER) "Advertising at" else "Peer"
            peerLabel?.let {
                Text(text = "$labelPrefix: $it", style = MaterialTheme.typography.bodyMedium)
            }
            if (role == PeerRole.SERVER) {
                Text(
                    text = "Open the Client on the other device to connect.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun DiscoveredDevicesCard(
    devices: List<PeerDevice>,
    onSelect: (PeerDevice) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Discovered peers",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (devices.isEmpty()) {
                Text("Scanning…", style = MaterialTheme.typography.bodyMedium)
            } else {
                devices.forEach { device ->
                    Button(onClick = { onSelect(device) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${device.name}  (${device.host}:${device.port})")
                    }
                }
            }
        }
    }
}

