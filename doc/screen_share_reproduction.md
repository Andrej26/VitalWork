# Reproducing Device-to-Device Screen Mirroring (Step by Step)

## What you're building

Two Android devices on the **same Wi-Fi**. One ("server"/operator) watches the live screen of the
other ("client"/monitored device). Video flows **peer-to-peer over the LAN** via WebRTC — no media
server, no cloud, no recurring cost (it's free to run all day; only battery/heat and local Wi-Fi
bandwidth are consumed — see [webrtc_screen_share.md](webrtc_screen_share.md#cost-free-to-run)). A
small WebSocket carries only the setup messages.

```
Signaling (tiny text over WebSocket)        Media (video over WebRTC/UDP, P2P)
Server ⇄ ws://client:9090 ⇄ Client          Server ⇄ direct UDP ⇄ Client
   request / offer / answer / ice               live screen frames
```

This guide is **self-contained**: follow it in a fresh app. It uses plain Android + coroutines; it does
**not** require Hilt, Room, or any class specific to VitalWork. Where VitalWork uses a helper
(`KeepAliveCoordinator`, `BackgroundConnectionService`), this guide gives the minimal generic version.

For the deeper reference, see [webrtc_screen_share.md](webrtc_screen_share.md) and
[peer_link_websocket.md](peer_link_websocket.md). For mDNS auto-discovery (optional — you can also just
type the server's IP), see [mdns_discovery.md](mdns_discovery.md).

---

## Step 0 — Dependencies

`gradle/libs.versions.toml` (or use the raw coordinates directly):

```toml
[versions]
javaWebSocket = "1.6.0"
streamWebrtc  = "1.3.10"

[libraries]
java-websocket        = { group = "org.java-websocket", name = "Java-WebSocket", version.ref = "javaWebSocket" }
stream-webrtc-android = { group = "io.getstream",       name = "stream-webrtc-android", version.ref = "streamWebrtc" }
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.java.websocket)          // WebSocket server + client (signaling)
    implementation(libs.stream.webrtc.android)   // Google's libwebrtc for Android (the video)
    // kotlinx-serialization-json is used below for the message envelope.
}
```

---

## Step 1 — Manifest: permissions + the foreground service

The screen capture **will not work** without a foreground service of type `mediaProjection`. This is the
single most common reproduction failure. Declare it now.

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<!-- mDNS only; skip if you type the IP manually -->
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />   <!-- Android 13+ -->

<application ...>
    <service
        android:name=".ScreenShareService"
        android:exported="false"
        android:foregroundServiceType="mediaProjection" />
</application>
```

Plain-HTTP on the LAN needs cleartext. Add `res/xml/network_security_config.xml`:

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors><certificates src="system" /></trust-anchors>
    </base-config>
</network-security-config>
```
…and reference it: `<application android:networkSecurityConfig="@xml/network_security_config" ...>`.

---

## Step 2 — The signaling envelope

One JSON message type carries everything (pairing + WebRTC signaling). Fields default to `null` so a
`request_screen` is just `{"type":"request_screen"}` on the wire.

```kotlin
@Serializable
data class PeerMessage(
    val type: String,            // "request_screen" | "offer" | "answer" | "ice" | "stop_screen"
    val sdp: String? = null,     // for "offer" / "answer"
    val sdpMid: String? = null,  // for "ice"
    val sdpMLineIndex: Int? = null,
    val candidate: String? = null
)
```

---

## Step 3 — The signaling transport (WebSocket)

You need a tiny duplex channel that: (a) lets the server send `request_screen`, and (b) delivers each
incoming `PeerMessage` to a callback. Below is the essence (full version:
[PeerLinkManagerImpl.kt](../app/src/main/java/com/vitalwork/app/data/link/PeerLinkManagerImpl.kt)).

**Server** (run on the operator device):

```kotlin
val json = Json { ignoreUnknownKeys = true; isLenient = true }
var conn: WebSocket? = null

val server = object : WebSocketServer(InetSocketAddress(9090)) {
    override fun onOpen(c: WebSocket?, h: ClientHandshake?) { conn = c }
    override fun onMessage(c: WebSocket?, msg: String?) {
        msg ?: return
        onSignal(json.decodeFromString(PeerMessage.serializer(), msg))   // your callback
    }
    override fun onClose(c: WebSocket?, code: Int, reason: String?, remote: Boolean) { conn = null }
    override fun onError(c: WebSocket?, ex: Exception?) {}
    override fun onStart() {}
}
server.isReuseAddr = true
server.connectionLostTimeout = 30   // ping keepalive; drops a dead peer
server.start()

fun send(m: PeerMessage) { conn?.takeIf { it.isOpen }?.send(json.encodeToString(PeerMessage.serializer(), m)) }
```

**Client** (run on the monitored device) — point it at the server's IP:

```kotlin
val client = object : WebSocketClient(URI("ws://$serverIp:9090")) {
    override fun onOpen(h: ServerHandshake?) {}
    override fun onMessage(msg: String?) {
        msg ?: return
        onSignal(json.decodeFromString(PeerMessage.serializer(), msg))   // your callback
    }
    override fun onClose(code: Int, reason: String?, remote: Boolean) {}
    override fun onError(ex: Exception?) {}
}
client.connectionLostTimeout = 30
client.connect()
```

> **How does the client get `serverIp`?** Either type it (the server can show its own IP — see
> `LanAddress.localIpv4()` in [LanAddress.kt](../app/src/main/java/com/vitalwork/app/data/link/LanAddress.kt),
> a one-method UDP trick), or auto-discover it with mDNS ([mdns_discovery.md](mdns_discovery.md)).

> **Threading:** Java-WebSocket calls these callbacks on its own threads. Don't touch UI directly from
> them — hop to the main thread / a flow. Run `server.stop()` off the main thread (it blocks).

---

## Step 4 — WebRTC bootstrap (one-time, shared)

Heavy to create — do it once and reuse:

```kotlin
val eglBase: EglBase = EglBase.create()

val factory: PeerConnectionFactory by lazy {
    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
    )
    PeerConnectionFactory.builder()
        .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
        .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
        .createPeerConnectionFactory()
}

val iceServers = listOf(
    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
)
```

---

## Step 5 — The shared PeerConnection (both sides)

Both devices build a `PeerConnection` the same way. The `Observer` is where ICE candidates leave and the
remote video arrives. A small detail that bites everyone: **ICE candidates can arrive before the remote
description is set**, so queue them.

```kotlin
private var remoteDescriptionSet = false
private val pendingRemoteIce = mutableListOf<IceCandidate>()
var remoteVideoTrack: VideoTrack? = null   // expose as a flow to your UI

fun createPeerConnection(send: (PeerMessage) -> Unit): PeerConnection {
    val config = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }
    remoteDescriptionSet = false
    pendingRemoteIce.clear()

    return factory.createPeerConnection(config, object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            send(PeerMessage("ice", sdpMid = c.sdpMid, sdpMLineIndex = c.sdpMLineIndex, candidate = c.sdp))
        }
        override fun onTrack(t: RtpTransceiver) {
            (t.receiver?.track() as? VideoTrack)?.let { remoteVideoTrack = it }   // viewer gets the video here
        }
        // The rest can be empty no-ops:
        override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}   // log this — invaluable
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {}
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    })!!
}

fun flushPendingIce(pc: PeerConnection) {
    pendingRemoteIce.forEach { pc.addIceCandidate(it) }
    pendingRemoteIce.clear()
}
```

A tiny `SdpObserver` helper so you only override what you need:

```kotlin
open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}
```

---

## Step 6 — Signaling logic (both sides)

This is the whole handshake. Route every incoming `PeerMessage` here.

```kotlin
fun onSignal(msg: PeerMessage, isServer: Boolean, send: (PeerMessage) -> Unit) = when (msg.type) {

    // CLIENT: server asked for our screen → show the system consent dialog (Step 7).
    "request_screen" -> launchConsentDialog()

    // SERVER (viewer): the client's offer arrived. Build the connection FRESH from the offer.
    "offer" -> {
        val pc = createPeerConnection(send).also { peerConnection = it }
        pc.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushPendingIce(pc)
                pc.createAnswer(object : SdpAdapter() {
                    override fun onCreateSuccess(d: SessionDescription) {
                        pc.setLocalDescription(SdpAdapter(), d)
                        send(PeerMessage("answer", sdp = d.description))
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, msg.sdp!!))
    }

    // CLIENT (sharer): the server's answer arrived.
    "answer" -> peerConnection?.setRemoteDescription(object : SdpAdapter() {
        override fun onSetSuccess() { remoteDescriptionSet = true; flushPendingIce(peerConnection!!) }
    }, SessionDescription(SessionDescription.Type.ANSWER, msg.sdp!!))

    // BOTH: an ICE candidate. Queue it until the remote description is in.
    "ice" -> {
        val c = IceCandidate(msg.sdpMid, msg.sdpMLineIndex ?: 0, msg.candidate)
        if (remoteDescriptionSet) peerConnection?.addIceCandidate(c) else pendingRemoteIce.add(c)
    }

    "stop_screen" -> teardown()
    else -> {}
}
```

> **Critical:** the viewer must **not** pre-create its `PeerConnection` before the offer. Build it inside
> the `"offer"` branch as shown. A pre-made, track-less connection races the offer and `onTrack` may
> never fire → you get a permanent blank "waiting" screen.

The server kicks the whole thing off with:

```kotlin
fun requestScreen(send: (PeerMessage) -> Unit) {
    teardown()
    send(PeerMessage("request_screen"))
}
```

---

## Step 7 — Client: consent → foreground service → capture

This is the part with the strict Android 14+ rules. The order is non-negotiable:

**7a. Launch the system consent dialog (in a Composable / Activity):**

```kotlin
val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
val launcher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
        // Hand the consent token to the SERVICE — capture must start from inside the FGS.
        ScreenShareService.start(context, result.resultCode, result.data!!)
    }
}
// when "request_screen" arrives:
launcher.launch(projectionManager.createScreenCaptureIntent())
```

> Deliver the `request_screen` signal to the UI via a flow with **`replay = 1`** (a `MutableSharedFlow`).
> If it arrives before the collector is active (common right after navigation) a `replay = 0` flow with
> `tryEmit` silently drops it and the dialog never appears.

**7b. The foreground service — promote with the `mediaProjection` type, THEN capture:**

```kotlin
class ScreenShareService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1) Promote to foreground WITH the mediaProjection type — BEFORE creating the projection.
        startForeground(
            1,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        // 2) Now it's legal to use the projection.
        val resultCode = intent!!.getIntExtra("code", Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, "data", Intent::class.java)!!
        beginCapture(resultCode, data)            // Step 8
        return START_STICKY
    }

    companion object {
        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, ScreenShareService::class.java)
                .putExtra("code", code).putExtra("data", data)
            ContextCompat.startForegroundService(ctx, i)   // called from a foreground context (right after consent)
        }
    }
}
```

> **The trap that cost us hours:** if any cleanup runs between `startForeground(mediaProjection)` and the
> projection call and it **demotes/recreates** the foreground service without the `mediaProjection`
> type, capture throws *"Media projections require a foreground service of type …
> MEDIA_PROJECTION"*. Keep the service promoted **with that type** continuously until capture is running.
> In VitalWork this meant splitting cleanup into `disposeResources()` (no demotion) vs full `teardown()`
> — see [webrtc_screen_share.md](webrtc_screen_share.md#android-mediaprojection--foreground-service-the-critical-part).

---

## Step 8 — Client: capture the screen and send the offer

```kotlin
fun beginCapture(resultCode: Int, data: Intent, send: (PeerMessage) -> Unit) {
    if (resultCode != Activity.RESULT_OK) return
    val metrics = context.resources.displayMetrics

    val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
        override fun onStop() { teardown() }   // user revoked from the system UI
    })
    val helper = SurfaceTextureHelper.create("ScreenCapture", eglBase.eglBaseContext)
    val source = factory.createVideoSource(/* isScreencast = */ true)
    capturer.initialize(helper, context, source.capturerObserver)
    capturer.startCapture(metrics.widthPixels, metrics.heightPixels, 30)   // native res, 30 fps
    val track = factory.createVideoTrack("screen0", source)

    val pc = createPeerConnection(send).also { peerConnection = it }
    pc.addTrack(track, listOf("stream0"))

    pc.createOffer(object : SdpAdapter() {
        override fun onCreateSuccess(d: SessionDescription) {
            pc.setLocalDescription(SdpAdapter(), d)
            send(PeerMessage("offer", sdp = d.description))
        }
    }, MediaConstraints())
}
```

Keep references to `capturer`, `helper`, `source`, `track`, and `pc` so you can `dispose()` them in
`teardown()`.

---

## Step 9 — Server: render the incoming video

When `remoteVideoTrack` becomes non-null (set in `onTrack`, Step 5), attach it to a
`SurfaceViewRenderer`. In Compose:

```kotlin
@Composable
fun ScreenVideoView(track: VideoTrack, eglBase: EglBase, modifier: Modifier = Modifier) {
    key(track) {   // a new session → new surface
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(eglBase.eglBaseContext, null)
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
```

(Views: create the renderer, `init(eglBase.eglBaseContext, null)`, `track.addSink(renderer)`, and
`renderer.release()` on teardown.)

---

## Step 10 — Teardown

Dispose in this order whenever the session ends (`stop_screen`, disconnect, error):

```kotlin
fun teardown() {
    runCatching { capturer?.stopCapture() }; runCatching { capturer?.dispose() }; capturer = null
    runCatching { localVideoTrack?.dispose() }; localVideoTrack = null
    runCatching { videoSource?.dispose() }; videoSource = null
    runCatching { surfaceTextureHelper?.dispose() }; surfaceTextureHelper = null
    runCatching { peerConnection?.dispose() }; peerConnection = null
    remoteVideoTrack = null
    remoteDescriptionSet = false; pendingRemoteIce.clear()
    // client only: stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
}
```

---

## Minimal checklist (the order that makes it work)

1. Deps added (Step 0). Manifest has `FOREGROUND_SERVICE_MEDIA_PROJECTION` + the service with
   `foregroundServiceType="mediaProjection"` (Step 1).
2. Server hosts WebSocket :9090; client connects (Step 3).
3. Server taps "view" → sends `request_screen` (Step 6).
4. Client receives it via a **`replay = 1`** flow → shows consent (Step 7a).
5. Consent OK → `startForegroundService` → `startForeground(mediaProjection)` → **then** capture (7b, 8).
6. Client sends `offer`; server builds its PeerConnection **from the offer**, returns `answer` (Step 6).
7. ICE flows both ways (queued until the remote description is set) → `onTrack` → render (Steps 5, 9).

If it stalls, log `onIceConnectionChange`. `CONNECTED` + no picture ⇒ renderer/codec. Never reaching
`CONNECTED` ⇒ network: the two devices aren't on the same subnet, or the router has **AP/client
isolation** on (disable it; guest Wi-Fi almost always has it).

---

## Scope: LAN only

This recipe is same-Wi-Fi only (mDNS is link-local; STUN-only ICE rarely traverses real-internet NAT).
To work across networks you'd add an out-of-band way to exchange the address **and** a **TURN** server
(e.g. self-hosted `coturn`) to relay when no direct path exists — TURN is the only piece that isn't
free. For an in-room operator + device, LAN + STUN is the right, free choice. Details in
[webrtc_screen_share.md](webrtc_screen_share.md#network-scope-lan-only-no-turn).
