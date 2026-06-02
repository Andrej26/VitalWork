# Samsung Galaxy Watch 8 — Sensor Reference

## Device Overview

| Property | Value |
|----------|-------|
| Vendor | Samsung |
| Product | Galaxy Watch 8 (Wear OS 6 / One UI Watch) |
| SDK | Samsung Health Sensor SDK `samsung-health-sensor-api-1.4.1.aar` |
| Connection | **Indirect** — companion app on the watch streams to the tablet over the Wearable Data Layer (Bluetooth, no internet) |
| Measured signals | Heart Rate (BPM), Inter-Beat Interval / IBI (ms), Electrodermal Activity / EDA (µS) |
| Additional data | Battery level (%), supported-tracker capability list |
| Optional (off by default) | PPG (25 Hz), SpO₂ (on-demand), Skin Temperature (1 Hz) |

> **Why this is different from eSense Pulse/Respiration.** The Watch 8 exposes **no standard
> Bluetooth Heart Rate Profile** and **no BLE GATT** for its sensors. EDA in particular is
> **only** reachable through Samsung's proprietary Health Sensor SDK, which runs **on the watch**.
> There is therefore no way for the tablet to read the watch directly the way `BleManager` reads
> the eSense Pulse. The integration is a **two-app design**: a thin Wear OS "sensor faucet" app
> reads the sensors on the watch and forwards each reading to the tablet over the Wearable Data
> Layer. This lives in the same Gradle project as a second module, `:wear`.

## Architecture

```
┌─────────────────────── Galaxy Watch 8 (:wear module) ───────────────────────┐
│  Samsung Health Sensor SDK (HealthTrackingService)                           │
│    └─► HEART_RATE_CONTINUOUS tracker  → HR + IBI (one HeartRateSet)           │
│    └─► EDA_CONTINUOUS tracker         → skin conductance                      │
│         │                                                                     │
│  WatchSensorService (foreground service, type=health)                        │
│    • owns the SDK lifecycle, registers trackers                               │
│    • flush() loop @1 Hz forces screen-off batches out immediately             │
│    └─► WatchDataSender (MessageClient)                                        │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                    │  Wearable Data Layer
                                    │  MessageClient.sendMessage(node, "/biometrix/sensors", json)
                                    │  Bluetooth-direct, no internet
                                    ▼
┌─────────────────────────── Android Tablet (:app module) ─────────────────────┐
│  WatchListenerService : WearableListenerService (auto-started on message)     │
│    • onMessageReceived → parse JSON line(s)                                   │
│    └─► WatchSensorReceiver (Hilt @Singleton)                                  │
│          • latestByType / availableTrackers / batteryLevel / connectionState  │
│          • connection state INFERRED from message arrival (watchdog)          │
│                │                                                               │
│          ┌─────┴───────────────┐                                              │
│          ▼                     ▼                                              │
│  ConnectionRepository    WatchSensorViewModel ──► WatchSensorScreen           │
│  (.watchConnectionState) (live readings)         (Sensors → Galaxy Watch)     │
└───────────────────────────────────────────────────────────────────────────────┘
```

## Transport: Wearable Data Layer (MessageClient)

The watch↔tablet link uses **`MessageClient`**, not a socket, not BLE GATT, not the VR WebSocket.

| Property | Value |
|----------|-------|
| API | `MessageClient.sendMessage(nodeId: String, path: String, data: ByteArray): Task<Int>` |
| Path | `/biometrix/sensors` |
| Node discovery | `CapabilityClient.getCapability("biometrix_phone", FILTER_REACHABLE)` |
| Delivery | Best-effort, fire-and-forget (a dropped message loses one sample) |
| Cost | Free — Google Play Services is a system component; no internet required |

**Why MessageClient and not ChannelClient.** An earlier version used a `ChannelClient` byte stream.
It froze: any transient write hiccup nulled the `OutputStream` and every later write silently
no-op'd with no reconnect. HR/EDA are only ~1 Hz, so a persistent stream buys nothing. MessageClient
is **stateless** — each reading is an independent message, nothing to stall, nothing to reconnect.

**Why the phone declares a "capability."** The watch must locate *this specific tablet's* node.
The tablet advertises a capability named `biometrix_phone` in `app/src/main/res/values/wear.xml`
(`android_wear_capabilities`); the watch resolves it via `CapabilityClient` and caches the node id.
On send failure the cached id is cleared so the next send re-resolves.

**Why the listener is a `WearableListenerService`.** Declared with a `MESSAGE_RECEIVED`
intent-filter, it **auto-starts the tablet app on a matching message even if the app isn't
running** — so the Hilt singleton receiver is created on demand and data is captured without the
user opening the watch screen.

## Message Format

Newline-free single-object JSON, hand-built (no serializer) to keep the payload tiny. The tablet
parses the same shape.

```jsonc
// One reading:
{"t":1717245600000,"type":"HR","value":72.0,"accuracy":0}
{"t":1717245600000,"type":"EDA","value":50.5,"accuracy":0}
{"t":1717245600000,"type":"IBI","value":833.0,"accuracy":0}
{"t":1717245600000,"type":"BATTERY","value":86.0,"accuracy":0}

// Capability list (resent ~5× early so a single drop isn't permanent):
{"t":1717245600000,"type":"CAPABILITIES","text":"HEART_RATE_CONTINUOUS,EDA_CONTINUOUS"}

// Several 1 Hz samples from one screen-off SDK callback, bundled into one message:
{"type":"BATCH","items":[ {…reading…}, {…reading…} ]}

// Explicit "tracking stopped" notice (instant DISCONNECT on the phone):
{"t":1717245600000,"type":"STOP"}
```

`t` is stamped on the watch with `System.currentTimeMillis()` — same clock convention as the rest of
the app, so watch samples align to VR events with no clock-sync step.

## Sensor Data Specifics

### Heart Rate + IBI (one tracker)

`HEART_RATE_CONTINUOUS` delivers **both** HR and IBI in a single `HeartRateSet` — do **not** register
two trackers. Read via:

| Value | Key | Notes |
|-------|-----|-------|
| HR (BPM) | `ValueKey.HeartRateSet.HEART_RATE` | gated by `HEART_RATE_STATUS` |
| IBI (ms) | `ValueKey.HeartRateSet.IBI_LIST` | a **list** per data point |
| IBI status | `ValueKey.HeartRateSet.IBI_STATUS_LIST` | parallel list |

**Gotcha (Samsung spec):** IBI values for the whole tracking interval are packed into the IBI
**list**; the parser must **iterate the list**, not assume one IBI per callback. A value is valid
only when `ibiStatus == 0 && ibiValue != 0` — invalid entries are discarded.

### EDA (Electrodermal Activity / skin conductance)

| Value | Key |
|-------|-----|
| Skin conductance (µS) | `ValueKey.EdaSet.SKIN_CONDUCTANCE` |
| Status | `ValueKey.EdaSet.STATUS` |

EDA is the watch's stress signal and the primary reason this SDK was chosen — it is **not**
obtainable through the standard Android `SensorManager`.

### Sampling rates

| Signal | Native rate |
|--------|-------------|
| Heart Rate | 1 Hz |
| EDA | 1 Hz |
| Skin Temperature | 1 Hz |
| PPG | 25 Hz (optional) |
| Accelerometer | 25 Hz (optional) |
| SpO₂ | on-demand only |

1 Hz is the **native** delivery rate for HR + EDA — "send every second" is not throttling, it is the
sensor's own cadence.

## The Screen-Off Problem (the hard part — read this first if reusing)

**Requirement:** continuous ~1 Hz HR + EDA with the **screen off**, wrist down, watch dozing, for
hour-long back-to-back sessions, so the data can be compared 1:1 against a wired MindField sensor.

**What goes wrong by default.** When the watch screen blanks it enters `mWakefulness=Dozing` and the
application processor (AP) suspends. Two things then stall:
1. The SDK keeps sampling at 1 Hz but **buffers** ~10 s and flushes the whole batch in one callback
   when the AP next wakes (verified: an 11-sample burst after ~11 s of silence).
2. If the app lacks background-sensor permission, the SDK **stops delivering entirely** once
   backgrounded.

### What did NOT work (verified on-device — do not repeat)

A `PARTIAL_WAKE_LOCK` + battery-optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` +
`dumpsys deviceidle whitelist`). On the Galaxy Watch 8 the OEM Doze implementation shows the wake
lock as **`DISABLED`** in `dumpsys power` even when the app is whitelisted, and the SDK still stopped
firing. **The standard Android "wake lock + exemption" advice is the wrong lever for the Samsung SDK
path.** Both were removed from the final code.

### What actually works (three load-bearing pieces)

| # | Piece | Why it's required |
|---|-------|-------------------|
| 1 | Foreground service, **type `health`**, with the FGS type **asserted at runtime** via `ServiceCompat.startForeground(this, id, notif, FOREGROUND_SERVICE_TYPE_HEALTH)` | Keeps the **process** alive & resident. Without the runtime assertion on API 34+, the OS demotes the process to *cached* the moment the launching Activity leaves TOP, then `START_STICKY` recreates it — producing a connect→unbind churn every ~2–10 s. |
| 2 | **`BODY_SENSORS_BACKGROUND`** permission (API 31+), granted separately (settings-routed) | Without it the SDK **stops delivering sensor data** once backgrounded / dozing. This is what stopped the *total* shutoff (vs. mere batching). |
| 3 | **`HealthTracker.flush()` driven on a ~1 s loop** while tracking | Screen-off, the SDK batches. `flush()` forces the buffered batch out **immediately**, so instead of an 11-sample burst every 11 s the phone gets 1–2 samples every second → continuous 1 Hz. |

**Verified result:** with all three in place and `mWakefulness=Dozing`, the EDA timestamps arrive
unbroken at 1 s intervals and the phone receives ~1 msg/s with no gap > ~1.4 s. This is the Samsung-
sanctioned mechanism (per Samsung Developer blog *"Continuous Heart Rate Tracking on Galaxy Watch,
Even with the Screen Off"*, 2026-04-23, and the SDK getting-started guide).

> Open item: **battery cost** of holding the AP busy at 1 Hz for an hour has not yet been measured.
> Measure with `dumpsys batterystats` over a ~15 min mock session and extrapolate before relying on
> hour-long untethered sessions.

## Connection State (inferred — there is no "connection" object)

Because the transport is stateless fire-and-forget, the tablet has **no event** telling it the watch
dropped. State is **inferred** in `WatchSensorReceiver`:

- Every message updates a `@Volatile lastMessageMs` and sets `CONNECTED`.
- A single poll loop (`POLL_INTERVAL_MS = 1 s`) sets `DISCONNECTED` if
  `now - lastMessageMs > INACTIVITY_TIMEOUT_MS` (**4 s**). One poll loop watching a volatile
  timestamp is race-free vs. rearming a debounce job from a binder thread.
- A normal **Stop is signalled explicitly**: the watch sends `{"type":"STOP"}` before teardown, and
  `onStop()` flips to `DISCONNECTED` **instantly** (no watchdog wait). The watchdog is only the
  safety net for the abnormal case — watch dies / out of range — where no goodbye can be sent.

```
DISCONNECTED
  │ first /biometrix/sensors message arrives
  ▼
CONNECTED ──(STOP message)──────────────► DISCONNECTED   (instant, normal Stop)
  │                                   ▲
  │ messages keep arriving (~1 Hz)    │
  └──(no message for > 4 s)───────────┘   (watchdog, abnormal drop)
```

## Watch-Side Service Lifecycle (gotchas)

The single biggest source of bugs was the foreground service being recreated, churning the SDK
connection. The fixes, all in `WatchSensorService`:

1. **Assert the FGS type at promotion** (see table above) — not the 2-arg `startForeground`.
2. **Promote first, on every non-STOP delivery** — including null-action sticky restarts — within
   ~5 s, or the OS throws `ForegroundServiceDidNotStartInTimeException`.
3. **Idempotency on the real instance:** guard SDK init on `trackingService != null` (an instance
   field), **never** on the static `isTracking` StateFlow (which is UI state and can be out of sync
   after recreation). This is what stops the connect→register→unbind churn.
4. `onStartCommand` returns **`START_REDELIVER_INTENT`** so restarts re-deliver the real
   `ACTION_START` (non-null) and hit the guard cleanly.

## Required Permissions (watch / `:wear`)

```xml
<uses-feature android:name="android.hardware.type.watch" />

<!-- Wear OS 6 / API 36+ replaced BODY_SENSORS with the health.* runtime permissions -->
<uses-permission android:name="android.permission.BODY_SENSORS" android:maxSdkVersion="35" />
<uses-permission android:name="android.permission.BODY_SENSORS_BACKGROUND" />   <!-- load-bearing -->
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.health.READ_HEART_RATE" />     <!-- API 36+ -->
<uses-permission android:name="com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA" /> <!-- EDA/ECG/PPG/BIA -->

<!-- Foreground service (health) to keep tracking with the screen off -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Service declaration:
```xml
<service android:name=".WatchSensorService" android:exported="false"
         android:foregroundServiceType="health" />
```

> **Runtime grants on API 36+:** HR/EDA require `android.permission.health.READ_HEART_RATE` **and**
> the Samsung `READ_ADDITIONAL_HEALTH_DATA` — `BODY_SENSORS` alone returns PERMISSION_ERROR.
> `BODY_SENSORS_BACKGROUND` is requested **separately** and routed to a settings screen
> ("Allow all the time"). For testing it can be granted directly:
> `adb shell pm grant com.biometrix.operator android.permission.BODY_SENSORS_BACKGROUND`.

### Tablet side (`:app`)

```xml
<service android:name=".data.sensor.watch.WatchListenerService" android:exported="true">
  <intent-filter>
    <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
    <data android:scheme="wear" android:host="*" android:pathPrefix="/biometrix/sensors" />
  </intent-filter>
</service>
```
Plus `app/src/main/res/values/wear.xml` declaring the `biometrix_phone` capability. No body-sensor
permissions on the tablet — it only receives messages.

## Project / Build Setup

- **Module:** `:wear` (`com.android.application`, same `applicationId "com.biometrix.operator"`),
  `minSdk 28` (Samsung Sensor SDK floor; Watch 8 far exceeds it), compile/target 36.
- **Samsung SDK** is a local AAR at `wear/libs/samsung-health-sensor-api-1.4.1.aar`, wired as:
  ```kotlin
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
  ```
  Do **not** use `implementation(":name@aar")` — that resolves against a repository, not the file.
- **`settings.gradle.kts`** uses `FAIL_ON_PROJECT_REPOS`, so per-module `repositories {}` are
  forbidden; the central `flatDir` lists both `app/libs` and `wear/libs`. `include(":wear")`.
- **`play-services-wearable`** (Data Layer) is a dependency of **both** `:app` and `:wear`.
- **Samsung developer mode** must be enabled on the watch for the SDK to deliver data prior to
  Samsung Partner approval (Health Platform → tap the title ~10×).

## App Implementation

### Key Files

| File | Role |
|------|------|
| [wear/.../WatchSensorService.kt](../wear/src/main/java/com/biometrix/operator/wear/WatchSensorService.kt) | Foreground `health` service; owns the Samsung SDK, registers trackers, runs the `flush()` loop, sends `STOP` |
| [wear/.../WatchDataSender.kt](../wear/src/main/java/com/biometrix/operator/wear/WatchDataSender.kt) | `MessageClient` sender; resolves & caches the `biometrix_phone` node |
| [wear/.../WatchMessage.kt](../wear/src/main/java/com/biometrix/operator/wear/WatchMessage.kt) | Builds the JSON lines (`reading`, `capabilities`, `batch`, `stop`) |
| [wear/.../MainActivity.kt](../wear/src/main/java/com/biometrix/operator/wear/MainActivity.kt) | Minimal Start/Stop watch UI; requests runtime permissions |
| [app/.../data/sensor/watch/WatchListenerService.kt](../app/src/main/java/com/biometrix/operator/data/sensor/watch/WatchListenerService.kt) | `WearableListenerService`; parses messages → receiver |
| [app/.../data/sensor/watch/WatchSensorReceiver.kt](../app/src/main/java/com/biometrix/operator/data/sensor/watch/WatchSensorReceiver.kt) | Hilt singleton sink; exposes flows; inferred connection state + watchdog |
| [app/.../data/sensor/watch/model/WatchReading.kt](../app/src/main/java/com/biometrix/operator/data/sensor/watch/model/WatchReading.kt) | `WatchReading(type, value, accuracy, t)` |
| [app/.../presentation/screens/sensors/watch/WatchSensorScreen.kt](../app/src/main/java/com/biometrix/operator/presentation/screens/sensors/watch/WatchSensorScreen.kt) | Sensors → Galaxy Watch live-readings screen |
| [app/.../presentation/screens/sensors/watch/WatchSensorViewModel.kt](../app/src/main/java/com/biometrix/operator/presentation/screens/sensors/watch/WatchSensorViewModel.kt) | Reads receiver flows for the screen |

### Receiver State (flows)

| Flow | Type | Purpose |
|------|------|---------|
| `connectionState` | `StateFlow<ConnectionState>` | inferred CONNECTED/DISCONNECTED (also via `ConnectionRepository.watchConnectionState`) |
| `latestByType` | `StateFlow<Map<String, WatchReading>>` | most recent reading per signal (HR/IBI/EDA/BATTERY) |
| `availableTrackers` | `StateFlow<List<String>>` | trackers the watch reports as supported |
| `batteryLevel` | `StateFlow<Int?>` | watch battery % |

### Recording / Database Integration

**Currently out of scope (Phase 1 = live display only).** No Room changes, no new `SensorType`
values, no export wiring. The watch is a live data source for evaluating signal availability and
accuracy against the MindField sensor. A future phase would map HR→`HEART_RATE`,
IBI→`ESENSE_RR_INTERVAL`, EDA→`GSR` (or new `SensorType` values), bump the DB, and extend export —
mirroring the eSense Pulse recording flow.

## Troubleshooting and Edge Cases

**Phone shows "disconnected" most of the time while data clearly flows**
- The watchdog is inferring from message arrival. If screen-off bursts exceed the timeout, the UI
  false-flips. Ensure the `flush()` loop is running (continuous 1 Hz) and `INACTIVITY_TIMEOUT_MS`
  is set sensibly (4 s for a 1 Hz stream).

**Data appears then freezes / "connected but frozen"**
- Historical symptom of the old `ChannelClient` stream nulling its `OutputStream`. The current
  `MessageClient` design has no stream to freeze. If it recurs, suspect the FGS recreation churn
  (see lifecycle gotchas) — check `adb logcat` for repeating `unbind Tracker Service` /
  `Health SDK connection ended`.

**SDK stops firing entirely the moment the screen blanks**
- `BODY_SENSORS_BACKGROUND` is not granted. Grant it (settings, "Allow all the time"), or via adb
  for testing.

**Watch keeps "restarting" / connect→unbind every few seconds**
- FGS type not asserted at runtime, or the init guard checks the static `isTracking` flag instead of
  the `trackingService` instance. See lifecycle gotchas.

**`PERMISSION_ERROR` on HR/EDA (API 36)**
- Need `health.READ_HEART_RATE` **and** Samsung `READ_ADDITIONAL_HEALTH_DATA`, not `BODY_SENSORS`.

**No data at all, no errors**
- Samsung developer mode (Health Platform) not enabled, or the `biometrix_phone` capability isn't
  declared/resolved (watch can't find the tablet node).

**Useful adb diagnostics**
```bash
# Is the watch dozing? Is our wake lock honored (it won't be — that's expected)?
adb -s <watch> shell dumpsys power | grep -E "mWakefulness=|BioMetrix"
# Is the SDK firing 1/s or in bursts?
adb -s <watch> logcat -s SHS#EDAContinuousSensor WatchDataSender
# Is the foreground service resident and typed HEALTH?
adb -s <watch> shell dumpsys activity services com.biometrix.operator
# Are messages arriving on the phone?
adb -s <phone> logcat -s WatchListenerService
```

## References

- Samsung Health Sensor SDK overview: https://developer.samsung.com/health/sensor/overview.html
- Getting started (trackers, `flush()`, `onFlushCompleted()`): https://developer.samsung.com/health/sensor/guide/getting-started.html
- Data specifications (HeartRateSet, EdaSet, status semantics): https://developer.samsung.com/health/sensor/guide/data-specifications.html
- "Continuous Heart Rate Tracking on Galaxy Watch, Even with the Screen Off" (2026-04-23): https://developer.samsung.com/galaxy-watch/blog/en/2026/04/23/continuous-heart-rate-tracking-on-galaxy-watch-even-with-the-screen-off
- See also [sensor_sampling_rates.md](sensor_sampling_rates.md) for multi-sensor synchronization context.
