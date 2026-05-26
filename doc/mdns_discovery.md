# mDNS Device Discovery

## What is mDNS?

mDNS (multicast DNS) lets devices on the same local network find each other by name without a central DNS server. A device announces itself by broadcasting a service record — its name, type, IP, and port — to the multicast group `224.0.0.251`. Any other device on the same subnet listening for that service type will receive the announcement.

DNS-SD (DNS Service Discovery) sits on top of mDNS and organises services by type, e.g. `_http._tcp` or `_narrowingchamber._tcp`. This is the same protocol used by AirPlay, Chromecast, and network printers.

**Key constraint:** mDNS is link-local — it only works between devices on the same WiFi network and subnet. It does not cross routers, and it is blocked by AP/client isolation (a setting present on many enterprise or guest WiFi networks).

---

## Android implementation

Android exposes mDNS through `NsdManager` (Network Service Discovery). The API has known reliability issues on Android 12 and below, but works well in practice on current hardware.

Two things are required beyond just using `NsdManager`:

1. **`CHANGE_WIFI_MULTICAST_STATE` permission** in `AndroidManifest.xml` — without this the kernel silently drops all multicast packets before they reach the app.
2. **`WifiManager.MulticastLock`** — must be acquired at runtime before starting discovery and released when done.

---

## Classes

### `MdnsDiscoveryService` — `data/vr/MdnsDiscoveryService.kt`

Singleton service (Hilt `@Singleton`) that owns the `NsdManager` session. It exposes two `StateFlow`s:

| Flow | Type | Description |
|---|---|---|
| `discoveredDevices` | `StateFlow<List<DiscoveredVrDevice>>` | Live list of resolved devices. Updated as devices appear/disappear. |
| `isDiscovering` | `StateFlow<Boolean>` | `true` while an active discovery session is running. |

**Lifecycle methods:**

```kotlin
mdnsDiscovery.startDiscovery()  // acquires MulticastLock, starts NsdManager session, clears previous results
mdnsDiscovery.stopDiscovery()   // stops NsdManager session, releases MulticastLock
```

`startDiscovery()` is idempotent — calling it while already discovering does nothing.

**Resolve queue:**
`NsdManager` can only resolve one service at a time. If multiple services are found simultaneously, a `ConcurrentLinkedQueue` + `AtomicBoolean` serialise the resolve calls so they run one after another without dropping any.

### `DiscoveredVrDevice` — `data/vr/model/DiscoveredVrDevice.kt`

Plain data class representing a fully resolved device:

```kotlin
data class DiscoveredVrDevice(
    val name: String,  // mDNS service instance name, e.g. "NarrowingChamber"
    val host: String,  // resolved IPv4 address, e.g. "192.168.1.42"
    val port: Int      // port the service is listening on
)
```

---

## How to use it on another screen

### 1. Inject into your ViewModel

```kotlin
@HiltViewModel
class MyViewModel @Inject constructor(
    private val mdnsDiscovery: MdnsDiscoveryService
) : ViewModel() {

    val devices: StateFlow<List<DiscoveredVrDevice>> = mdnsDiscovery.discoveredDevices
    val isDiscovering: StateFlow<Boolean> = mdnsDiscovery.isDiscovering

    init {
        mdnsDiscovery.startDiscovery()
    }

    override fun onCleared() {
        super.onCleared()
        mdnsDiscovery.stopDiscovery()
    }
}
```

### 2. Collect in your Composable

```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel = hiltViewModel()) {
    val devices by viewModel.devices.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()

    if (isDiscovering) {
        CircularProgressIndicator()
    }

    devices.forEach { device ->
        Text("${device.name} — ${device.host}:${device.port}")
    }
}
```

### 3. Get the IP when you need it

```kotlin
val ip = device.host   // ready to pass to a WebSocket, HTTP client, etc.
val port = device.port
```

---

## Discovery lifecycle tips

- **Start discovery in `init {}`** and stop in `onCleared()` so the scan runs for exactly as long as the screen is alive.
- **Stop before connecting.** Once the user picks a device, call `stopDiscovery()` to release the multicast lock and stop unnecessary network traffic.
- **Restart after disconnect.** Call `startDiscovery()` again to resume scanning so the user can pick a different device.
- `MdnsDiscoveryService` is a **singleton** — if two ViewModels call `startDiscovery()` at the same time only one session runs (the second call is ignored). Coordinate lifecycle carefully if the service is shared across multiple active screens simultaneously.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `isDiscovering` stays `false` | `discoverServices()` threw an exception — check Logcat for `NsdManager` errors |
| Scanning but no devices appear | AP/client isolation on the router is blocking multicast; verify with `avahi-browse` or `dns-sd -B` from a laptop on the same WiFi |
| Device appears then immediately disappears | `onServiceLost` fired — the advertising device went offline or changed IP |
| Works on Android 13+, not on 12 | Known `mDNSResponder` daemon lifecycle bug on Android 12; consider retrying `startDiscovery()` after a short delay |
