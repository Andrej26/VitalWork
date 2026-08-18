# mDNS Device Discovery

How the two VitalWork devices find each other on the local network. This is the discovery layer
underneath the [device-to-device link](peer_link_websocket.md) — the server advertises its WebSocket
service, the client browses for it and resolves an IP to connect to.

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

### `PeerMdnsService` — [data/link/PeerMdnsService.kt](../app/src/main/java/com/vitalwork/app/data/link/PeerMdnsService.kt)

Hilt `@Singleton` wrapping `NsdManager`. **One service covers both roles** — the server registers,
the client discovers:

| Role | Call | Effect |
|---|---|---|
| Server | `register(serviceName, port)` | Advertises the WebSocket server under an instance name |
| Server | `unregister()` | Withdraws the advertisement |
| Client | `startDiscovery()` | Browses for peers; results land in `discoveredDevices` |
| Client | `stopDiscovery()` | Stops browsing and clears the list |

**Service type asymmetry (important):** Android's register API wants the type *without* a trailing
dot, discover wants it *with* one. Both constants are exposed so the mismatch is explicit rather
than an easy-to-miss typo:

```kotlin
const val SERVICE_TYPE_REGISTER = "_vitalwork._tcp"   // registerService()
const val SERVICE_TYPE_DISCOVER = "_vitalwork._tcp."  // discoverServices()
```

**State:** a single flow — there is no separate `isDiscovering` flag.

| Flow | Type | Description |
|---|---|---|
| `discoveredDevices` | `StateFlow<List<PeerDevice>>` | Live list of resolved peers. A peer is replaced on re-resolve and removed on `onServiceLost`. |

**Idempotency:** `register()` and `startDiscovery()` both return early if their listener is already
set, so duplicate calls are harmless. The list is cleared at *both* ends of a discovery cycle —
`startDiscovery()` starts from an empty list, and `stopDiscovery()` clears it (plus the pending
resolve queue) even when discovery was already stopped — so a stale peer never lingers after a link
ends.

**MulticastLock:** one non-reference-counted lock (`"VitalWorkPeerMdns"`) shared by both roles.
Acquired by whichever role starts first; released only once **neither** role is active
(`releaseLockIfIdle()`). This matters because the server registers *and* the client discovers from
the same singleton — naive release-on-stop would kill multicast for the other role.

**Resolve queue:** `NsdManager` can only resolve one service at a time. A `ConcurrentLinkedQueue` +
`AtomicBoolean` serialise the resolve calls so simultaneous discoveries run one after another without
dropping any. On resolve, an **IPv4** address is preferred (a resolved IPv6/link-local would need a
bracketed `ws://[..]` URL and is flaky on LAN); it falls back to whatever host was resolved.

> `NsdManager.resolveService()` is deprecated on API 34+ but still functional and still used here.

### `PeerDevice` — [data/link/model/PeerDevice.kt](../app/src/main/java/com/vitalwork/app/data/link/model/PeerDevice.kt)

Plain data class representing a fully resolved peer:

```kotlin
data class PeerDevice(
    val name: String,  // mDNS service instance name, e.g. "VitalWork-A-Pixel7"
    val host: String,  // resolved IPv4 address, e.g. "192.168.1.42"
    val port: Int      // port the WebSocket server is listening on
)
```

### `PeerNaming` — [data/link/PeerNaming.kt](../app/src/main/java/com/vitalwork/app/data/link/PeerNaming.kt)

The instance name is not arbitrary: it carries the **device prefix** (A/B/C/D, set in Settings) so
several server/client pairs can share one Wi-Fi without crossing wires.

```kotlin
PeerNaming.advertise("A", "Pixel7")           // → "VitalWork-A-Pixel7"
PeerNaming.prefixOf("VitalWork-A-Pixel7")     // → "A"
PeerNaming.matchesPrefix(name, localPrefix)   // client-side filter
```

`prefixOf` tolerates `NsdManager`'s collision-rename suffix (e.g. `VitalWork-A-Pixel7 (1)`) and
normalises the letter to upper case.

**Pairing rule for operators:** both devices of a pair use the **same** letter; different pairs use
different letters. The same prefix also scopes participant/session code generation, so keeping the
letters distinct across pairs keeps the generated codes collision-free.

---

## How it's wired in the peer link

Screens and ViewModels **do not inject `PeerMdnsService` directly** — they go through
[`PeerLinkManager`](../app/src/main/java/com/vitalwork/app/data/link/PeerLinkManager.kt), whose
implementation owns the mDNS lifecycle alongside the WebSocket and applies the prefix filter.
Injecting the mDNS service straight into a second consumer would fight the manager over the shared
listener and multicast lock.

`PeerLinkManagerImpl` drives it per role:

- **Server** — `startServer()` opens the `WebSocketServer` on port **9090** (`PORT`), then calls
  `register(PeerNaming.advertise(devicePrefix, model), PORT)`.
- **Client** — `startClientDiscovery()` calls `startDiscovery()`; resolved peers surface through
  `PeerLinkManager.discoveredDevices`. `connectTo(device)` calls `stopDiscovery()` and opens
  `ws://${device.host}:${device.port}`.
- Both entry points call `stop()` first (which unregisters *and* stops discovery), so the manager
  runs **one role at a time** — see `PeerLinkManager.activeRole`.

The prefix filter lives in `PeerLinkManagerImpl`, which maps the raw mDNS list through
`PeerNaming.matchesPrefix` before exposing it — so a UI never has to think about pair scoping. The
prefix is read **per emission**, so changing it in Settings takes effect without restarting the link.

```kotlin
@HiltViewModel
class MyViewModel @Inject constructor(
    private val peerLink: PeerLinkManager
) : ViewModel() {

    // Already filtered to peers whose embedded prefix matches this device's.
    val devices: StateFlow<List<PeerDevice>> = peerLink.discoveredDevices

    fun browse() = peerLink.startClientDiscovery()
    fun connect(device: PeerDevice) = peerLink.connectTo(device)  // opens ws://host:port
}
```

Reach for `PeerMdnsService` directly only when adding a genuinely new advertised service, and then
route its lifecycle through the same singleton so the multicast lock stays correctly shared.

---

## Discovery lifecycle tips

- **Discovery is not free.** It holds the multicast lock and keeps the Wi-Fi chipset receiving
  multicast, so stop it once the user has picked a peer — `connectTo()` does exactly this.
- **Restart after a dropped link.** `terminateClientLink()` re-runs `startDiscovery()` when the
  client's link dies (server gone, network lost, connect failure), so the stale peer is dropped and
  the user can pick a peer again.
- **The link outlives its screen.** `PeerLinkManager.isActive` is false while merely discovering and
  true once a link is up; the link screen tears down discovery on close but preserves an active link
  (kept alive by `BackgroundConnectionService`). Don't stop the link from `onCleared()`.
- **`PeerMdnsService` is a singleton** holding a single register + single discover slot. Calling
  `startDiscovery()` while already discovering is a no-op, and the multicast lock is only released
  when register *and* discover are both idle.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `discoveredDevices` stays empty right after `startDiscovery()` | `discoverServices()` threw (check Logcat for `PeerMdnsService`/`NsdManager` errors), or the server isn't advertising yet |
| Scanning but no devices appear | AP/client isolation on the router is blocking multicast; verify with `avahi-browse` or `dns-sd -B _vitalwork._tcp` from a laptop on the same WiFi |
| Peer appears in logs but not in the UI list | Prefix mismatch — the two devices are set to different letters in **Settings** (see `PeerNaming`) |
| Device appears then immediately disappears | `onServiceLost` fired — the advertising device went offline or changed IP |
| Peer resolves but `ws://` connect fails | Resolved an IPv6 host (IPv4 preferred but not guaranteed), or the server isn't listening on port 9090 |
| Works on Android 13+, not on 12 | Known `mDNSResponder` daemon lifecycle bug on Android 12; consider retrying `startDiscovery()` after a short delay |

---

## Related

- [peer_link_websocket.md](peer_link_websocket.md) — the WebSocket link that mDNS bootstraps
- [webrtc_screen_share.md](webrtc_screen_share.md) — screen mirroring layered on that link
