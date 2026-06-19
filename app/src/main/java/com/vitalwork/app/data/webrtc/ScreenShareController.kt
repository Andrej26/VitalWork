package com.vitalwork.app.data.webrtc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import com.vitalwork.app.data.link.PeerLinkManager
import com.vitalwork.app.data.link.model.PeerMessage
import com.vitalwork.app.data.model.ConnectionState
import com.vitalwork.app.data.system.KeepAliveCoordinator
import com.vitalwork.app.data.system.KeepAliveReason
import com.vitalwork.app.data.webrtc.model.ShareState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.webrtc.CapturerObserver
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the WebRTC screen-share session over the existing WebSocket link (used purely for signaling).
 *
 * - **Server (viewer):** [requestScreen] → ask the client to share, receive its offer, render the
 *   incoming [remoteVideoTrack].
 * - **Client (sharer):** on an incoming `request_screen` it emits [screenRequested] so the UI can show
 *   the system consent prompt; after consent the service calls [beginCapture] (which must run only
 *   once the `mediaProjection` foreground type is active — see BackgroundConnectionService).
 *
 * All session mutations run on a single-worker scope so create/dispose never race; WebRTC callbacks
 * (ICE/track) only push to flows or send signals, which are thread-safe.
 */
@Singleton
class ScreenShareController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: WebRtcEngine,
    private val peerLinkManager: PeerLinkManager,
    private val keepAlive: KeepAliveCoordinator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val _shareState = MutableStateFlow(ShareState.IDLE)
    val shareState: StateFlow<ShareState> = _shareState.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    /**
     * Fires when the server requests this (client) device's screen — UI must launch consent.
     *
     * `replay = 1` so a request that arrives on a WebSocket thread *before* the screen's collector is
     * active (common right after navigation) is retained and still delivered, instead of being dropped
     * by `tryEmit` with no subscriber. [consumeScreenRequest] resets it once consent resolves so a stale
     * request can't re-trigger the dialog on the next recomposition.
     */
    private val _screenRequested = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val screenRequested: SharedFlow<Unit> = _screenRequested.asSharedFlow()

    /** Clear the retained screen request after the consent dialog has been launched/resolved. */
    fun consumeScreenRequest() {
        _screenRequested.resetReplayCache()
    }

    private var peerConnection: PeerConnection? = null
    private var screenCapturer: VideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var frameRepeater: FrameRepeater? = null

    private var remoteDescriptionSet = false
    private val pendingRemoteIce = mutableListOf<IceCandidate>()

    init {
        // Incoming signaling → handle sequentially.
        scope.launch {
            peerLinkManager.signals.collect { msg -> handleSignal(msg) }
        }
        // Tear down whenever the link leaves CONNECTED (client → DISCONNECTED; server → CONNECTING
        // when the monitored peer leaves). drop(1) skips the initial value.
        scope.launch {
            peerLinkManager.connectionState.drop(1).collect { state ->
                if (state != ConnectionState.CONNECTED && _shareState.value != ShareState.IDLE) {
                    teardown()
                }
            }
        }
    }

    // --- Server (viewer) ---

    fun requestScreen() = scope.launch {
        if (peerLinkManager.connectionState.value != ConnectionState.CONNECTED) return@launch
        // Tear down any prior session, but do NOT pre-create the PeerConnection: the viewer must build
        // it fresh from the incoming offer (TYPE_OFFER handler), so the remote video m-line/transceiver
        // is established by setRemoteDescription and onTrack fires reliably. Pre-creating an empty,
        // track-less PeerConnection here races the offer and can leave remoteVideoTrack null (black view).
        teardown()
        _shareState.value = ShareState.REQUESTING
        peerLinkManager.sendSignal(PeerMessage(type = TYPE_REQUEST_SCREEN))
        log("Requested peer's screen")
    }

    // --- Client (sharer) ---

    /**
     * Build the screen capturer + offer. Called by BackgroundConnectionService AFTER it has promoted
     * to a `mediaProjection` foreground service (Android requires the FGS type before the projection).
     */
    fun beginCapture(resultCode: Int, data: Intent) = scope.launch {
        if (resultCode != Activity.RESULT_OK) {
            log("Screen consent not granted")
            _shareState.value = ShareState.ERROR
            keepAlive.release(KeepAliveReason.SCREEN_SHARE)
            return@launch
        }
        // Clear any prior session WITHOUT releasing SCREEN_SHARE (we're about to create the projection,
        // which requires the mediaProjection FGS type to stay active — see disposeResources).
        disposeResources()
        try {
            val factory = engine.factory
            val metrics = context.resources.displayMetrics

            val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() {
                    // User revoked screen capture from the system UI.
                    stopShare()
                }
            })
            val helper = SurfaceTextureHelper.create("ScreenCapture", engine.eglBase.eglBaseContext)
            val source = factory.createVideoSource(/* isScreencast = */ true)
            // ScreenCapturerAndroid (→ VirtualDisplay) only emits a frame when the screen content
            // changes, so a static client screen sends one frame and goes silent — the viewer freezes
            // and a viewer surface recreated by a server rotation stays black until the client is
            // touched. The repeater keeps a low-rate idle stream alive by re-feeding a *copy* of the
            // last frame (see FrameRepeater for why a copy, not the live capture frame).
            val repeater = FrameRepeater(source.capturerObserver)
            capturer.initialize(helper, context, repeater)
            capturer.startCapture(metrics.widthPixels, metrics.heightPixels, CAPTURE_FPS)
            val track = factory.createVideoTrack("screen0", source)

            screenCapturer = capturer
            surfaceTextureHelper = helper
            localVideoSource = source
            localVideoTrack = track
            frameRepeater = repeater

            val pc = createPeerConnection() ?: return@launch
            pc.addTrack(track, listOf(STREAM_ID))
            _shareState.value = ShareState.SHARING

            pc.createOffer(object : SdpAdapter() {
                override fun onCreateSuccess(desc: SessionDescription) {
                    pc.setLocalDescription(SdpAdapter(), desc)
                    peerLinkManager.sendSignal(PeerMessage(type = TYPE_OFFER, sdp = desc.description))
                }
            }, MediaConstraints())
            log("Sharing screen — sent offer")
        } catch (e: Exception) {
            Log.e(TAG, "beginCapture failed", e)
            log("Screen share failed: ${e.message}")
            _shareState.value = ShareState.ERROR
            teardown()
        }
    }

    // --- Both: stop ---

    /** Local/user-initiated stop: tell the peer, then tear down. */
    fun stopShare() = scope.launch {
        peerLinkManager.sendSignal(PeerMessage(type = TYPE_STOP_SCREEN))
        teardown()
    }

    // --- Signaling ---

    private fun handleSignal(msg: PeerMessage) {
        when (msg.type) {
            TYPE_REQUEST_SCREEN -> {
                // Client side: surface the consent prompt to the UI.
                log("Peer requested our screen — prompting for consent")
                _shareState.value = ShareState.REQUESTING
                _screenRequested.tryEmit(Unit)
            }
            TYPE_OFFER -> {
                val sdp = msg.sdp ?: return
                log("Received offer — building viewer connection")
                val pc = peerConnection ?: createPeerConnection() ?: return
                pc.setRemoteDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        remoteDescriptionSet = true
                        flushPendingIce()
                        createAnswer(pc)
                    }
                    override fun onSetFailure(error: String?) {
                        super.onSetFailure(error)
                        log("Failed to apply offer: $error")
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            }
            TYPE_ANSWER -> {
                val sdp = msg.sdp ?: return
                log("Received answer")
                val pc = peerConnection ?: return
                pc.setRemoteDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        remoteDescriptionSet = true
                        flushPendingIce()
                    }
                    override fun onSetFailure(error: String?) {
                        super.onSetFailure(error)
                        log("Failed to apply answer: $error")
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            TYPE_ICE -> {
                val candidate = IceCandidate(msg.sdpMid, msg.sdpMLineIndex ?: 0, msg.candidate)
                if (remoteDescriptionSet) peerConnection?.addIceCandidate(candidate)
                else pendingRemoteIce.add(candidate)
            }
            TYPE_STOP_SCREEN -> teardown()
        }
    }

    private fun createAnswer(pc: PeerConnection) {
        pc.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SdpAdapter(), desc)
                peerLinkManager.sendSignal(PeerMessage(type = TYPE_ANSWER, sdp = desc.description))
                _shareState.value = ShareState.VIEWING
            }
        }, MediaConstraints())
    }

    private fun flushPendingIce() {
        pendingRemoteIce.forEach { peerConnection?.addIceCandidate(it) }
        pendingRemoteIce.clear()
    }

    private fun createPeerConnection(): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        val pc = engine.factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                peerLinkManager.sendSignal(
                    PeerMessage(
                        type = TYPE_ICE,
                        sdpMid = c.sdpMid,
                        sdpMLineIndex = c.sdpMLineIndex,
                        candidate = c.sdp
                    )
                )
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                (transceiver.receiver?.track() as? VideoTrack)?.let {
                    log("Remote video track received (onTrack)")
                    _remoteVideoTrack.value = it
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                (receiver?.track() as? VideoTrack)?.let {
                    log("Remote video track received (onAddTrack)")
                    _remoteVideoTrack.value = it
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                log("WebRTC connection: $newState")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                log("ICE: $state")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })
        if (pc == null) {
            log("Failed to create PeerConnection")
            _shareState.value = ShareState.ERROR
        }
        peerConnection = pc
        return pc
    }

    /**
     * Dispose all WebRTC resources without notifying the peer (idempotent), but DON'T touch the
     * screen-share keep-alive. Used to clear any prior session at the *start* of [beginCapture]:
     * releasing SCREEN_SHARE there would make the service observer demote the foreground service and
     * strip the `mediaProjection` type milliseconds before `MediaProjection` is created — the system
     * then throws "Media projections require a foreground service of type … MEDIA_PROJECTION".
     */
    private fun disposeResources() {
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        screenCapturer = null
        // After capture is stopped (no more onFrameCaptured), stop the repeater and free its copy.
        runCatching { frameRepeater?.dispose() }
        frameRepeater = null
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        runCatching { localVideoSource?.dispose() }
        localVideoSource = null
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        _remoteVideoTrack.value = null
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        _shareState.value = ShareState.IDLE
    }

    /** Full teardown: dispose resources AND release the screen-share keep-alive (lets the FGS stop).
     *  No-op on the server for the keep-alive (it never held SCREEN_SHARE). */
    private fun teardown() {
        disposeResources()
        keepAlive.release(KeepAliveReason.SCREEN_SHARE)
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        // Mirror into the visible link log so the screen-share handshake is observable in-app.
        peerLinkManager.logExternal("Screen: $line")
    }

    /**
     * Wraps the [VideoSource]'s [CapturerObserver] so a static client screen keeps streaming.
     *
     * Android screen capture only delivers a frame when the screen content changes, so without this
     * the viewer freezes on the last frame and a viewer surface recreated by a server rotation shows
     * black until the client is touched. The repeater fills the silence by re-feeding the last frame
     * at a low idle rate ([REPEAT_INTERVAL_MS]); when real frames are flowing it stays out of the way.
     *
     * **Why a copy, not the live frame:** capture frames are backed by the capturer's single shared
     * `SurfaceTexture`. Retaining one to re-feed later would block the capturer from delivering new
     * frames (the texture stays "in use") and would re-send a since-overwritten texture — exactly the
     * hard-freeze regression this replaces. So each kept frame is copied into its own I420 memory via
     * [VideoFrame.Buffer.toI420], which is independent of the capture texture and safe to hold/resend.
     * The copy is throttled to the idle rate so the GPU→CPU read happens at most ~twice a second.
     */
    private class FrameRepeater(private val delegate: CapturerObserver) : CapturerObserver {
        private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
        private val lock = Any()
        /** Independent I420 copy of the most recent frame — safe to hold and re-feed. */
        private var lastCopy: VideoFrame? = null
        @Volatile private var lastRealFrameNanos = 0L
        private var lastCopyNanos = 0L

        init {
            executor.scheduleWithFixedDelay({
                val repeat = synchronized(lock) {
                    val copy = lastCopy ?: return@synchronized null
                    // Only fill silence: skip if a real frame flowed within the interval.
                    if (System.nanoTime() - lastRealFrameNanos < REPEAT_INTERVAL_NANOS) {
                        return@synchronized null
                    }
                    copy.buffer.retain() // released via repeat.release() after delivery below
                    VideoFrame(copy.buffer, copy.rotation, System.nanoTime())
                }
                if (repeat != null) {
                    try {
                        delegate.onFrameCaptured(repeat)
                    } finally {
                        repeat.release()
                    }
                }
            }, REPEAT_INTERVAL_MS, REPEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }

        override fun onCapturerStarted(success: Boolean) = delegate.onCapturerStarted(success)

        override fun onCapturerStopped() = delegate.onCapturerStopped()

        override fun onFrameCaptured(frame: VideoFrame) {
            // Refresh the kept copy at most once per interval (toI420 is a GPU→CPU read). Done before
            // forwarding while the producer's buffer reference is still guaranteed valid.
            synchronized(lock) {
                val now = System.nanoTime()
                lastRealFrameNanos = now
                if (lastCopy == null || now - lastCopyNanos >= REPEAT_INTERVAL_NANOS) {
                    lastCopy?.release()
                    val i420 = frame.buffer.toI420()
                    lastCopy = VideoFrame(i420, frame.rotation, frame.timestampNs)
                    lastCopyNanos = now
                }
            }
            delegate.onFrameCaptured(frame)
        }

        fun dispose() {
            executor.shutdownNow()
            synchronized(lock) {
                lastCopy?.release()
                lastCopy = null
            }
        }
    }

    /** Empty-default [org.webrtc.SdpObserver] so callers override only what they need. */
    private open class SdpAdapter : org.webrtc.SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { Log.e(TAG, "SDP create failed: $error") }
        override fun onSetFailure(error: String?) { Log.e(TAG, "SDP set failed: $error") }
    }

    companion object {
        private const val TAG = "ScreenShareController"
        private const val CAPTURE_FPS = 30
        private const val STREAM_ID = "vitalwork-screen"

        /** Idle cadence for re-feeding the last screen frame so a static client screen stays live. */
        private const val REPEAT_INTERVAL_MS = 500L
        private val REPEAT_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(REPEAT_INTERVAL_MS)

        const val TYPE_REQUEST_SCREEN = "request_screen"
        const val TYPE_OFFER = "offer"
        const val TYPE_ANSWER = "answer"
        const val TYPE_ICE = "ice"
        const val TYPE_STOP_SCREEN = "stop_screen"

        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
    }
}
