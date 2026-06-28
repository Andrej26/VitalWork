# Device-to-Device Link (WebSocket)

## Overview

The peer link is a direct device-to-device channel between two VitalWork tablets/phones on the same
Wi-Fi network. One device runs as the **server** (host) and the other as the **client**; once paired
they exchange small JSON messages over a single WebSocket. The link is used for two things:

1. **Pairing + diagnostics** — a greeting handshake and a free-text test-message log.
2. **WebRTC signaling** — the same socket doubles as the signaling channel that sets up screen
   mirroring (see [webrtc_screen_share.md](webrtc_screen_share.md)). The actual video does **not** flow
   over this socket — only the offer/answer/ICE setup messages do.

This is distinct from the **VR link** (`data/vr/`), which is a Ktor HTTP server the Meta Quest POSTs to.
The peer link is symmetric device↔device and built on the Java-WebSocket library.

**Connection URL:** `ws://{host}:9090` (fixed port **9090**)

**Key constraint:** like mDNS, the link is link-local — both devices must be on the same Wi-Fi subnet,
and AP/client isolation (common on guest/enterprise Wi-Fi) blocks it. See
[mdns_discovery.md](mdns_discovery.md).

---

## Roles

| Role | Entry point | Behavior |
|------|-------------|----------|
| **Server** (host) | `startServer()` | Opens a `WebSocketServer` on port 9090, registers an mDNS service, waits for a client. Started manually via the **Connect** button. |
| **Client** | `startClientDiscovery()` → `connectTo(device)` | Scans for advertised peers via mDNS, then opens a `WebSocketClient` to the chosen one. Discovery starts automatically when the client screen opens. |

`PeerRole` is `{ SERVER, CLIENT }`. The active role is exposed as `activeRole: StateFlow<PeerRole?>`
(null when no link is active).

---

## Message Schema

All traffic is a single `@Serializable` envelope, `PeerMessage`, encoded with kotlinx-serialization
(`ignoreUnknownKeys`, lenient, defaults omitted from the wire):

```kotlin
data class PeerMessage(
    val type: String,            // "hello" | "log" | "request_screen" | "offer" | "answer" | "ice" | "stop_screen"
    val text: String = "",       // human-readable payload (greeting / test message); empty for signaling
    val ts: Long = 0,            // sender's System.currentTimeMillis()
    val sdp: String? = null,     // SDP for "offer" / "answer"
    val sdpMid: String? = null,  // ICE candidate fields for "ice"
    val sdpMLineIndex: Int? = null,
    val candidate: String? = null
)
```

| `type` | Direction | Meaning |
|--------|-----------|---------|
| `hello` | both | Greeting sent immediately on connect; `text` = "Hello from {deviceName}". |
| `log` | both | Free-text test message (the **Send test message** button). |
| `request_screen` | server → client | Ask the client to share its screen. |
| `offer` | client → server | WebRTC SDP offer (`sdp`). |
| `answer` | server → client | WebRTC SDP answer (`sdp`). |
| `ice` | both | A single ICE candidate (`sdpMid` / `sdpMLineIndex` / `candidate`). |
| `stop_screen` | both | End the screen-share session. |

Signaling fields default to `null` so legacy `hello`/`log` messages (and their tests) are unaffected.
`hello`/`log` are rendered into the on-screen log; every other `type` is forwarded to the
`signals` flow for the screen-share controller to consume.

---

## Discovery (mDNS)

`PeerMdnsService` (Hilt `@Singleton`) wraps `NsdManager`:

| Constant | Value |
|----------|-------|
| Register service type | `_vitalwork._tcp` |
| Discover service type | `_vitalwork._tcp.` |
| Advertised name | `VitalWork-{Build.MODEL}` (spaces → `-`) |

The server calls `register(deviceName, PORT)`; the client calls `startDiscovery()` and resolved peers
appear as `PeerDevice(name, host, port)` in `discoveredDevices`. Requires the
`CHANGE_WIFI_MULTICAST_STATE` permission and a held `MulticastLock` (same machinery described in
[mdns_discovery.md](mdns_discovery.md)).

`LanAddress.localIpv4()` resolves the device's own IPv4 for the server's advertised `ws://…:9090`
label shown in the UI.

---

## Public API — `PeerLinkManager`

```kotlin
interface PeerLinkManager {
    val connectionState: StateFlow<ConnectionState>     // DISCONNECTED / CONNECTING / CONNECTED / ERROR
    val isActive: StateFlow<Boolean>                    // true while a persistent link is up
    val activeRole: StateFlow<PeerRole?>
    val discoveredDevices: StateFlow<List<PeerDevice>>  // client role; empty on server
    val logLines: StateFlow<List<String>>               // visible on-screen log (last 200 lines)
    val signals: SharedFlow<PeerMessage>                // incoming WebRTC signaling messages
    val peerLabel: StateFlow<String?>                   // advertised address (server) / peer (client)

    fun startServer()
    fun startClientDiscovery()
    fun connectTo(device: PeerDevice)
    fun sendMessage(text: String)                       // send a "log" message
    fun sendSignal(message: PeerMessage)                // send a signaling message
    fun logExternal(line: String)                       // append to the visible log (used by ScreenShareController)
    fun stop()                                          // tear down server/client/mDNS, reset state
}
```

`signals` is buffered (`extraBufferCapacity = 16`) and emitted with `tryEmit`, because Java-WebSocket
fires its callbacks on its own threads (so suspending emits aren't possible there).

---

## Lifecycle and Keep-Alive

A live link acquires `KeepAliveReason.LINK` from the `KeepAliveCoordinator`, which starts the single
app-wide `BackgroundConnectionService` (foreground service + Wi-Fi lock). This keeps the socket alive
while the screen is off. The link is released — and the service allowed to stop — when `stop()` runs or
the connection drops terminally. See [sensor_galaxy_watch.md](sensor_galaxy_watch.md) for the broader
keep-alive rationale shared across features.

The server stays "listening" (`CONNECTING`) after a client disconnects, so a new client can reconnect
without restarting hosting. The client, on a dropped link, fully tears down and **rescans** so the user
can reconnect (`terminateClientLink()`).

A periodic WebSocket ping (`connectionLostTimeout = 30s`) keeps NAT/AP idle timers from dropping a
backgrounded socket and detects a dead peer (→ `onClose` → teardown).

---

## Connection State Machine

```
                       startServer()                         startClientDiscovery()
        ┌──────────────────────────────┐          ┌──────────────────────────────────┐
        ▼                              (server)    ▼                            (client)
   CONNECTING ◄── client leaves ── CONNECTED   DISCONNECTED ── connectTo(device) ─► CONNECTING
   (listening)                         ▲                                                │
        │                              │                                       onOpen  │
        │  onOpen (client connects)    └────────────────── CONNECTED ◄─────────────────┘
        └──────────────────────────────────────────────►  (hello exchanged)
                                                                │ link drops / stop()
                                                                ▼
                                       server: → CONNECTING (still listening)
                                       client: → DISCONNECTED (+ rescan)
```

---

## App Implementation

### Key Files

| File | Role |
|------|------|
| [data/link/PeerLinkManager.kt](../app/src/main/java/com/vitalwork/app/data/link/PeerLinkManager.kt) | Interface — the single source of truth for the link |
| [data/link/PeerLinkManagerImpl.kt](../app/src/main/java/com/vitalwork/app/data/link/PeerLinkManagerImpl.kt) | Java-WebSocket server + client implementation |
| [data/link/PeerMdnsService.kt](../app/src/main/java/com/vitalwork/app/data/link/PeerMdnsService.kt) | mDNS register/discover via `NsdManager` |
| [data/link/LanAddress.kt](../app/src/main/java/com/vitalwork/app/data/link/LanAddress.kt) | Local IPv4 resolution for the advertised label |
| [data/link/PeerRole.kt](../app/src/main/java/com/vitalwork/app/data/link/PeerRole.kt) | `enum PeerRole { SERVER, CLIENT }` |
| [data/link/model/PeerMessage.kt](../app/src/main/java/com/vitalwork/app/data/link/model/PeerMessage.kt) | Wire envelope (pairing + signaling) |
| [data/link/model/PeerDevice.kt](../app/src/main/java/com/vitalwork/app/data/link/model/PeerDevice.kt) | Resolved peer (name/host/port) |
| [.../screens/link/PeerLinkViewModel.kt](../app/src/main/java/com/vitalwork/app/presentation/screens/link/PeerLinkViewModel.kt) | UI state + role selection |
| [.../screens/link/PeerLinkScreen.kt](../app/src/main/java/com/vitalwork/app/presentation/screens/link/PeerLinkScreen.kt) | Compose UI (status, discovered peers, log, screen monitor) |

### Data Flow

```
Server tablet                                  Client tablet
─────────────                                  ─────────────
startServer()                                  startClientDiscovery()
  └─ WebSocketServer :9090                        └─ NsdManager scan → discoveredDevices
  └─ PeerMdnsService.register()                   └─ connectTo(device)
        ▲  mDNS advertise                              └─ WebSocketClient → ws://host:9090
        └──────────────── mDNS ─────────────────────────┘
  onOpen ──► "hello" ◄──────── WebSocket ────────► "hello" ──► onOpen
  log / signals  ◄──────────── PeerMessage ───────────►  log / signals
                                                          │
                              (signaling types) ─────────┴─► ScreenShareController.signals
```

---

## Threading Notes

Java-WebSocket fires `onOpen`/`onMessage`/`onClose`/`onError` on its own threads. The implementation
keeps this safe by:

- Updating `MutableStateFlow`s directly (`.update`/`.value` are thread-safe).
- Emitting signaling with `tryEmit` (non-suspending) rather than `emit`.
- Running teardown (`server.stop()` blocks up to 1s) on an IO scope, never on the caller's (possibly
  main) thread.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Client scans but no peers appear | Server not hosting, or AP/client isolation blocking mDNS multicast (see [mdns_discovery.md](mdns_discovery.md)) |
| `Advertising at: ws://?:9090` (host shows `?`) | `LanAddress.localIpv4()` found no IPv4 — device not on Wi-Fi |
| Link drops whenever the screen turns off | Battery optimization killing the process — the screen shows a "Allow background" exemption card |
| `Failed to start server` in the log | Port 9090 already bound (a previous server didn't release) — `isReuseAddr` is set, but try **Disconnect** then **Connect** again |
| Peer disconnects after ~30s idle | `connectionLostTimeout` fired (dead peer or network gap) — the client rescans, the server keeps listening |
| Signaling messages logged as `Recv (unparsed)` | Malformed/incompatible `PeerMessage` JSON from the peer |
