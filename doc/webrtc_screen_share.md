# Screen Mirroring (WebRTC)

## Overview

Screen mirroring lets the **operator** (server) watch the live screen of the **monitored device**
(client) — e.g. to see what a participant sees on their tablet during a scenario. It is built on
**WebRTC** so the video travels **peer-to-peer directly over the local Wi-Fi**: there is no media
server, no cloud relay, and no recurring cost. The only external dependency is Google's free public
**STUN** server, used to discover each device's LAN address; on the same Wi-Fi the direct path is used.

The existing peer WebSocket link ([peer_link_websocket.md](peer_link_websocket.md)) is reused as the
**signaling channel** — it carries only the small setup messages (offer/answer/ICE). The actual H.264/VP8
video frames flow over a **separate** WebRTC connection (UDP), not through the WebSocket.

```
Signaling (tiny text)            Media (video frames)
─────────────────────            ────────────────────
Server ⇄  WebSocket  ⇄ Client    Server ⇄ WebRTC P2P (UDP) ⇄ Client
   offer / answer / ice              live screen video
```

**Library:** `io.getstream:stream-webrtc-android` (a packaging of Google's libwebrtc for Android).

> Rebuilding this from scratch in another app? See the step-by-step
> [screen_share_reproduction.md](screen_share_reproduction.md) — this document is the *reference*; that
> one is the *recipe* (dependencies, copy-able code, exact ordering).

---

## Cost: free to run

Screen mirroring carries **no monetary cost** — no subscription, no per-minute/per-session fee, no
metered backend. It runs entirely on your own hardware over your own network, so you can mirror
continuously (all day) without paying anyone:

- **Peer-to-peer over local Wi-Fi.** The two devices talk directly; the video never routes through a
  paid cloud service, media server, or third-party relay.
- **No API/service fees.** It uses Android's own MediaProjection (screen capture) + a WebRTC peer
  connection. Nothing here bills per use (no Anthropic/cloud charges, no streaming service).
- **No internet data transfer.** Because the media stays on the LAN, it doesn't consume cellular data
  or metered ISP bandwidth. The only external call is to Google's **free public STUN** server, used
  once to discover LAN addresses (a few tiny packets) — see [Network Scope](#network-scope-lan-only-no-turn).

The only real costs are physical, not financial:

- **Battery & heat.** Continuous screen capture + video encoding + radio is power-hungry; the sharing
  (client) device drains and warms up faster.
- **Local Wi-Fi bandwidth.** It uses your local network capacity, which can affect other devices on
  the same Wi-Fi — but that's not a charge.

**Caveat — this holds only for the on-device, LAN setup described here.** If the link were ever routed
through a hosted **TURN** relay (for off-LAN use), a cloud streaming service, or over cellular data,
*those* could cost money. The one piece that isn't free at scale is TURN, which this configuration does
**not** use (see [Network Scope](#network-scope-lan-only-no-turn)).

---

## Roles

| Role | WebRTC role | Responsibility |
|------|-------------|----------------|
| **Server** (operator) | Viewer / receiver | Sends `request_screen`, receives the client's offer, renders the incoming `VideoTrack`. |
| **Client** (monitored) | Sharer / sender | On `request_screen` shows the system consent dialog; after consent, captures the screen and sends an offer. |

The role comes from the peer link's `PeerRole` — the WebRTC role is derived from it, so the device that
hosts the link is always the viewer.

---

## Handshake — End to End

```
SERVER (viewer)                                    CLIENT (sharer)
───────────────                                    ───────────────
requestScreen()
  └─ sendSignal(request_screen) ──────────────────►  handleSignal(request_screen)
                                                        └─ screenRequested → UI
                                                        └─ system consent dialog
                                                              │ user taps "Start now"
                                                              ▼
                                                        onScreenConsent(resultCode, data)
                                                        └─ BackgroundConnectionService
                                                             .startScreenShare(...)        [FGS: mediaProjection]
                                                        └─ beginCapture():
                                                             ScreenCapturerAndroid → VideoTrack
                                                             createPeerConnection() + addTrack
                                                             createOffer → setLocalDescription
  handleSignal(offer) ◄──────── sendSignal(offer) ──────────┘
  └─ createPeerConnection()
  └─ setRemoteDescription(offer)
  └─ createAnswer → setLocalDescription
  └─ sendSignal(answer) ─────────────────────────►  handleSignal(answer)
                                                        └─ setRemoteDescription(answer)
  onIceCandidate ──── sendSignal(ice) ⇄ ─── sendSignal(ice) ──── onIceCandidate
        (ICE candidates exchanged both directions, queued until remote description is set)
  onTrack → remoteVideoTrack ──► SurfaceViewRenderer  ◄═══════ screen video frames (P2P) ═══════
```

**Important ordering detail:** the viewer does **not** pre-create its `PeerConnection` in
`requestScreen()`. It is built fresh when the offer arrives, so the remote video m-line/transceiver is
established by `setRemoteDescription` and `onTrack` fires reliably. Pre-creating an empty, track-less
connection races the offer and can leave `remoteVideoTrack` null (a blank "waiting" view).

---

## Android MediaProjection + Foreground Service (the critical part)

Capturing the screen uses Android's `MediaProjection` API, which has strict requirements on Android 14+
(target SDK 36):

1. The user must grant consent via the system dialog (`MediaProjectionManager.createScreenCaptureIntent()`).
2. **Before** the projection is created, a foreground service of type
   `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` must be running — **and stay running with that type** for
   the entire capture.

The flow is therefore routed through the shared `BackgroundConnectionService`:

```
PeerLinkViewModel.onScreenConsent(resultCode, data)
  └─ BackgroundConnectionService.startScreenShare(context, resultCode, data)   // ACTION_START_SCREEN
       └─ onStartCommand:
            coordinator.acquire(SCREEN_SHARE)            // add the keep-alive reason
            startForeground(..., forceScreenShare=true)  // promote WITH the mediaProjection type
            screenShareController.beginCapture(resultCode, data)
```

`forceScreenShare = true` forces the `mediaProjection` type into that `startForeground` call regardless
of how quickly the coordinator's reason set becomes observable.

### The teardown trap (fixed)

`beginCapture()` clears any prior session before starting. It must do so **without** releasing the
`SCREEN_SHARE` keep-alive reason. Releasing it makes the service's reactive observer demote the
foreground service and strip the `mediaProjection` type milliseconds before the projection is created —
the system then throws:

> `Media projections require a foreground service of type ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`

To prevent this, cleanup is split:

| Method | Disposes WebRTC resources | Releases `SCREEN_SHARE` keep-alive |
|--------|:---:|:---:|
| `disposeResources()` | ✅ | ❌ — used at the **start** of `beginCapture` |
| `teardown()` | ✅ | ✅ — used on stop / disconnect / genuine failure |

So the `mediaProjection` permit is held continuously from acquisition through screen capture.

---

## State Model

`ShareState` (exposed to the link UI):

| State | Meaning |
|-------|---------|
| `IDLE` | No screen-share session. |
| `REQUESTING` | Server asked to view; awaiting the client's consent + offer. |
| `SHARING` | Client is capturing + sending its screen. |
| `VIEWING` | Server is receiving + rendering the remote screen. |
| `ERROR` | Setup failed. |

`screenRequested` (client) uses `replay = 1` so a `request_screen` that arrives **before** the UI
collector is active (common right after navigation) is retained and still delivered, instead of being
dropped by `tryEmit` with no subscriber. `consumeScreenRequest()` resets it after the consent dialog is
launched so it can't re-trigger on recomposition.

---

## Capture Parameters

| Parameter | Value | Notes |
|-----------|-------|-------|
| Resolution | Device display (`displayMetrics.widthPixels × heightPixels`) | Full native screen resolution. |
| Frame rate | 30 fps (`CAPTURE_FPS`) | |
| Codec | Hardware encoder/decoder via `DefaultVideoEncoderFactory`/`DefaultVideoDecoderFactory` | Shared `EglBase`. |
| ICE servers | `stun:stun.l.google.com:19302` | STUN only — no TURN (LAN-only, see below). |
| SDP semantics | Unified Plan | |

Quality is "best effort" at native resolution and 30 fps; WebRTC adapts bitrate to the link. On the same
Wi-Fi this is typically smooth and low-latency.

---

## App Implementation

### Key Files

| File | Role |
|------|------|
| [data/webrtc/WebRtcEngine.kt](../app/src/main/java/com/vitalwork/app/data/webrtc/WebRtcEngine.kt) | Process-wide `EglBase` + lazy `PeerConnectionFactory` (`@Singleton`) |
| [data/webrtc/ScreenShareController.kt](../app/src/main/java/com/vitalwork/app/data/webrtc/ScreenShareController.kt) | Drives the session: request/offer/answer/ICE, capture, render handoff, teardown |
| [data/webrtc/model/ShareState.kt](../app/src/main/java/com/vitalwork/app/data/webrtc/model/ShareState.kt) | `ShareState` enum |
| [service/BackgroundConnectionService.kt](../app/src/main/java/com/vitalwork/app/service/BackgroundConnectionService.kt) | Promotes to a `mediaProjection` FGS, then calls `beginCapture` |
| [.../screens/link/PeerLinkViewModel.kt](../app/src/main/java/com/vitalwork/app/presentation/screens/link/PeerLinkViewModel.kt) | Bridges UI ↔ controller; launches consent |
| [.../screens/link/PeerLinkScreen.kt](../app/src/main/java/com/vitalwork/app/presentation/screens/link/PeerLinkScreen.kt) | Consent launcher + `SurfaceViewRenderer` (the "Screen monitor" card) |
| [data/link/model/PeerMessage.kt](../app/src/main/java/com/vitalwork/app/data/link/model/PeerMessage.kt) | Signaling envelope (shared with the link) |

### Threading

All session mutations run on a single-worker scope
(`Dispatchers.Default.limitedParallelism(1)`) so create/dispose never race. WebRTC callbacks
(ICE/track/connection) only push to flows or send signals, which are thread-safe.

### On-screen tracing

`ScreenShareController.log()` mirrors every handshake step into the peer link's visible log
(`Screen: …`), so the whole flow — `Requested peer's screen`, `Received offer`, `ICE: CHECKING/CONNECTED`,
`Remote video track received` — is observable in-app on both devices, not just in Logcat.

---

## Keeping the picture alive when the screen "turns off"

Android **stops compositing a display that is truly powered off**, so `MediaProjection` produces no
frames once the sharer's screen sleeps — the operator would see a frozen/black image. There is no API
to capture an off display. The workaround keeps the sharer's display technically **on** while making
it *look* off ([ScreenDimController](../app/src/main/java/com/vitalwork/app/service/ScreenDimController.kt)):

1. A **`SCREEN_DIM_WAKE_LOCK`** held by `BackgroundConnectionService` keeps the screen rendering
   regardless of which app is in the foreground (a window `KEEP_SCREEN_ON` flag only works while *our*
   activity is visible). It is acquired/released in lockstep with the `SCREEN_SHARE` keep-alive reason.
2. The **backlight is dimmed to near-black** via `Settings.System.SCREEN_BRIGHTNESS`. Backlight is a
   panel property and does **not** affect the captured framebuffer, so the operator keeps seeing
   full-brightness content while the phone looks off and draws little power. The original brightness +
   mode are saved and restored on stop.

Dimming needs the **`WRITE_SETTINGS`** special access (`Settings.System.canWrite`); the client's
"Sharing your screen" card offers an **Allow dark screen** button that opens the grant page. Without it
the screen still stays on (step 1), just at normal brightness.

The **tablet (viewer)** needs nothing here — the WebRTC connection + foreground service survive the
tablet sleeping; waking it resumes the live picture.

## Network Scope: LAN only (no TURN)

The current configuration is **same-Wi-Fi only**:

- Discovery uses mDNS (link-local).
- The only ICE server is STUN. There is **no TURN relay**.

Across the open internet (two different networks behind NAT), mDNS can't find the peer and STUN-only ICE
frequently fails to connect. Making it work off-LAN would require (a) an out-of-band way to exchange the
address and (b) a **TURN** server to relay when a direct path can't be punched through — TURN is the one
piece that isn't free at scale (though self-hosting `coturn` is cheap). For the study (operator tablet +
monitored device on the same room Wi-Fi) LAN + STUN is the ideal, free choice.

---

## Required Permissions

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

```xml
<service
    android:name=".service.BackgroundConnectionService"
    android:exported="false"
    android:foregroundServiceType="microphone|connectedDevice|dataSync|mediaProjection" />
```

Cleartext is permitted for the LAN (`network_security_config.xml`). No internet-facing permissions are
needed for screen share itself.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Client log: `Media projections require a foreground service of type … MEDIA_PROJECTION` | The `mediaProjection` FGS type was stripped before capture — fixed by `disposeResources()` (not full `teardown()`) at the start of `beginCapture`. |
| Client never shows the consent dialog | `request_screen` arrived before the UI collector — fixed by `screenRequested` `replay = 1`. |
| Consent granted but server stays on "Waiting…", no video | Check the client log for `Sharing screen — sent offer`. If absent, capture failed; if present, look at the server's `ICE:` lines. |
| Server: `Received offer` but ICE never reaches `CONNECTED` | AP/client isolation on the router is blocking peer-to-peer traffic — disable guest/isolation, put both devices on the same SSID. |
| Server: `ICE: CONNECTED` but no picture | Codec/renderer issue — confirm `Remote video track received` appears; the renderer is a keyed `SurfaceViewRenderer` sharing the engine's `EglBase`. |
| Black/blank "Screen monitor" card | `remoteVideoTrack` is null — usually the pre-create race (fixed) or capture never started on the client. |
| Works on LAN, fails across networks | Expected — LAN-only by design (no TURN). See *Network Scope* above. |
