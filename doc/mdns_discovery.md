# mDNS Device Discovery

## What is mDNS?

mDNS (multicast DNS) lets devices on the same local network find each other by name without a central DNS server. A device announces itself by broadcasting a service record — its name, type, IP, and port — to the multicast group `224.0.0.251`. Any other device on the same subnet listening for that service type will receive the announcement.

DNS-SD (DNS Service Discovery) sits on top of mDNS and organises services by type, e.g. `_http._tcp` or `_vitalwork._tcp` (the type VitalWork advertises its peer link under). This is the same protocol used by AirPlay, Chromecast, and network printers.

**Key constraint:** mDNS is link-local — it only works between devices on the same WiFi network and subnet. It does not cross routers, and it is blocked by AP/client isolation (a setting present on many enterprise or guest WiFi networks).

---

## Android implementation

Android exposes mDNS through `NsdManager` (Network Service Discovery). The API has known reliability issues on Android 12 and below, but works well in practice on current hardware.

Two things are required beyond just using `NsdManager`:

1. **`CHANGE_WIFI_MULTICAST_STATE` permission** in `AndroidManifest.xml` — without this the kernel silently drops all multicast packets before they reach the app.
2. **`WifiManager.MulticastLock`** — must be acquired at runtime before starting discovery and released when done.

---

## Classes

mDNS in VitalWork drives the **device-to-device peer link** (see
[peer_link_websocket.md](peer_link_websocket.md)): the **server** advertises its WebSocket, the
**client** discovers and resolves it, then opens `ws://host:port`.

### `PeerMdnsService` — `data/link/PeerMdnsService.kt`

Singleton service (Hilt `@Singleton`) that owns the `NsdManager` session and handles **both roles**.
It exposes one `StateFlow`:

| Flow | Type | Description |
|---|---|---|
| `discoveredDevices` | `StateFlow<List<PeerDevice>>` | Live list of resolved peers (client role). Updated as peers appear/disappear; cleared on `stopDiscovery()`. |

**Service type** (note the register/discover asymmetry Android requires):

| Constant | Value |
|----------|-------|
| `SERVICE_TYPE_REGISTER` | `_vitalwork._tcp` (no trailing dot) |
| `SERVICE_TYPE_DISCOVER` | `_vitalwork._tcp.` (trailing dot) |

**Lifecycle methods:**

```kotlin
// Server:
mdns.register(serviceName, port)   // acquires MulticastLock, advertises the WebSocket service
mdns.unregister()                  // stops advertising, releases the lock if idle

// Client:
mdns.startDiscovery()              // acquires MulticastLock, starts NsdManager scan, clears previous results
mdns.stopDiscovery()               // stops the scan, clears discoveredDevices, releases the lock if idle
```

Both `register()` and `startDiscovery()` are idempotent — calling them while already active does
nothing. A **non-reference-counted** `MulticastLock` is held while *either* role is active and
released only when both are idle (`releaseLockIfIdle()`).

**IPv4 preference:** on resolve, an `Inet4Address` host is preferred (a resolved IPv6/link-local
would need bracketed `ws://[..]` and is flaky on LAN), falling back to whatever host resolved.

**Resolve queue:**
`NsdManager` can only resolve one service at a time. If multiple services are found simultaneously, a
`ConcurrentLinkedQueue` + `AtomicBoolean` serialise the resolve calls so they run one after another
without dropping any.

### `PeerDevice` — `data/link/model/PeerDevice.kt`

Plain data class representing a fully resolved peer:

```kotlin
data class PeerDevice(
    val name: String,  // mDNS service instance name, e.g. "VitalWork-Pixel7"
    val host: String,  // resolved IPv4 address, e.g. "192.168.1.42"
    val port: Int      // port the WebSocket server is listening on
)
```

The service **instance name** carries the device prefix so pairs are scoped across parallel tablets —
see [PeerNaming.kt](../app/src/main/java/com/vitalwork/app/data/link/PeerNaming.kt).

---

## How it's wired in the peer link

`PeerMdnsService` is injected into `PeerLinkManagerImpl`, not consumed directly by a screen. The link
manager drives it per role:

- **Server** — on `startServer()`, opens the `WebSocketServer` on port **9090** then calls
  `register(serviceName, 9090)`.
- **Client** — on `startClientDiscovery()`, calls `startDiscovery()`; resolved peers surface through
  `PeerLinkManager.discoveredDevices` to `PeerLinkScreen`. When the user taps a peer, the manager
  calls `stopDiscovery()` and opens `ws://${device.host}:${device.port}`.

```kotlin
val ip = device.host   // ready to pass to the WebSocketClient
val port = device.port // 9090
```

---

## Discovery lifecycle tips

- **Stop before connecting.** Once the user picks a peer, discovery is stopped to release the
  multicast lock and cut unnecessary network traffic.
- **Restart after disconnect.** The client re-runs `startDiscovery()` on a dropped link so the user
  can pick a peer again (`terminateClientLink()` in the link manager).
- `PeerMdnsService` is a **singleton** holding a single register + single discover slot — a second
  `register()`/`startDiscovery()` while one is active is a no-op. The link manager runs **one role at
  a time** (`PeerLinkManager.activeRole`), so the two slots don't contend in practice.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `discoveredDevices` stays empty right after `startDiscovery()` | `discoverServices()` threw (check Logcat for `PeerMdnsService`/`NsdManager` errors), or the server isn't advertising yet |
| Scanning but no devices appear | AP/client isolation on the router is blocking multicast; verify with `avahi-browse` or `dns-sd -B _vitalwork._tcp` from a laptop on the same WiFi |
| Device appears then immediately disappears | `onServiceLost` fired — the advertising device went offline or changed IP |
| Works on Android 13+, not on 12 | Known `mDNSResponder` daemon lifecycle bug on Android 12; consider retrying `startDiscovery()` after a short delay |
