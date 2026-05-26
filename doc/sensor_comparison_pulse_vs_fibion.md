# Sensor Comparison: eSense Pulse vs. Fibion Flash

BioMetrix Operator — Android app implementation comparison.
Sources: [`sensor_esense_pulse.md`](sensor_esense_pulse.md), [`sensor_fibion_flash.md`](sensor_fibion_flash.md).

## Contents

1. [Device Overview](#1-device-overview)
2. [Communication Protocol](#2-communication-protocol)
3. [Device Discovery](#3-device-discovery)
4. [Connection Lifecycle](#4-connection-lifecycle)
5. [Data Streams](#5-data-streams)
6. [Battery Management](#6-battery-management)
7. [Subscription Model](#7-subscription-model)
8. [Recording Integration](#8-recording-integration)
9. [Architecture & Dependency Injection](#9-architecture--dependency-injection)
10. [UI State](#10-ui-state)
11. [Event Systems](#11-event-systems)
12. [Shared Components & Permissions](#12-shared-components--permissions)
13. [Key Files](#13-key-files)
14. [Summary: Key Differences](#14-summary-key-differences)

---

## 1. Device Overview

| Property | eSense Pulse | Fibion Flash |
|---|---|---|
| Vendor | Mindfield Biosignals | Fibion (Movesense platform) |
| Form factor | Chest strap | Chest strap |
| Operating mode | One mode only (chest strap) | Chest mode only (fixed — no mode selection) |
| Measured signals | Heart Rate (BPM), R-R intervals (ms) | Heart Rate (BPM), ECG, R-R intervals (ms) |
| Additional data | Battery level (%) | Battery level (%), Device info (serial, SW/HW version, product name) |
| Typical HR notification cadence | **~4.5 Hz fixed** (225 ms between samples, clock-driven) | **~1 per heartbeat** (≈1.0–1.4 Hz at resting HR) — not a fixed clock; cadence scales with HR |
| BLE addressing | MAC address from Android scan; advertises a random BLE address | MAC address from Android scan; Movesense serial number additionally exposed via MDS once connected |
| Documentation | [`sensor_esense_pulse.md`](sensor_esense_pulse.md) | [`sensor_fibion_flash.md`](sensor_fibion_flash.md) |

---

## 2. Communication Protocol

The most fundamental architectural difference: eSense Pulse uses the standard Android GATT API with binary byte-array parsing, while Fibion Flash routes all communication through the Movesense MDS library using a REST-like JSON API.

| Aspect | eSense Pulse | Fibion Flash |
|---|---|---|
| BLE abstraction | **Direct Android GATT API** | **Movesense MDS library** (`mdslib-3.27.0-release.aar`) |
| Protocol after BLE connect | Standard GATT services & characteristics | REST-like JSON API over MDS transport |
| Device identifier | BLE MAC address | Serial number (from MDS `onConnectionComplete`) |
| Standards compliance | Bluetooth SIG Heart Rate Profile | Proprietary Movesense / Suunto API |
| Data format | Binary byte-array parsing (BT SIG spec) | JSON subscriptions and one-shot GET requests |

### eSense Pulse — GATT Services

| Service | UUID | Characteristic | UUID | Access |
|---|---|---|---|---|
| Heart Rate | `0x180D` | Heart Rate Measurement | `0x2A37` | NOTIFY |
| Battery Service | `0x180F` | Battery Level | `0x2A19` | READ |

> CCCD `0x2902` written to enable remote notifications on `0x2A37`.

### Fibion Flash — MDS API Endpoints

| Type | Path | Response Format |
|---|---|---|
| Subscribe | `{serial}/Meas/HR` | `{"Body": {"average": float, "rrData": [int, ...]}}` |
| Subscribe | `{serial}/Meas/ECG/{rate}` | `{"Body": {"Samples": [int, ...]}}` — raw LSB |
| Subscribe | `{serial}/Meas/ECG/{rate}/mV` | `{"Body": {"Samples": [float, ...]}}` — mV (fw ≥ 2.3) |
| GET | `suunto://{serial}/System/Energy/Level` | `{"Content": int}` — battery 0–100 |
| GET | `suunto://{serial}/Info` | `{"Content": {"serial","sw","hw","productName"}}` |

---

## 3. Device Discovery

| Aspect | eSense Pulse | Fibion Flash |
|---|---|---|
| OS-level filter | None (unfiltered scan) | None (unfiltered scan) |
| Post-scan name filter | Prefix `eSense` (case-insensitive) | Prefix `Movesense` (case-insensitive) |
| Manufacturer ID filter | `0xFF0C` (Mindfield) — fallback if name unavailable | None |
| Advertised name | `eSense XXXXXXX` | `Movesense...` — *not* "Fibion" |
| Scan mode | `SCAN_MODE_LOW_LATENCY` | `SCAN_MODE_LOW_LATENCY` |
| Scan timeout | 15 seconds | 15 seconds |
| Debug mode | None — `startScan()` takes no parameters; only the post-scan name/manufacturer filter applies | `filterByName=false` shows all BLE devices |

---

## 4. Connection Lifecycle

| Aspect | eSense Pulse | Fibion Flash |
|---|---|---|
| Connection timeout | **8 seconds** | **20 seconds** (MDS protocol overhead) |
| Post-connect auto-actions | Discover services → enable HR notifications → read battery | Read `/Info` + read battery → start battery polling |
| Connection priority | `CONNECTION_PRIORITY_BALANCED` — 30–50 ms intervals, coexists with concurrent BLE connections | Managed internally by MDS |
| Warmup after connect | **5 seconds** — samples shown in UI but blocked from recording flows | **None** |
| Auto-reconnect | None | Up to **3 attempts**, 5-second intervals (unexpected disconnect only) |
| Bluetooth-off detection | `BroadcastReceiver` → stops scan, cleans up GATT | `BroadcastReceiver` → stops scan, tears down MDS |
| BT re-enable behavior | No auto-scan — `STATE_ON` only flips `_bluetoothEnabled` and emits a debug event; user must trigger a scan manually | 3 s delay after `STATE_ON`, then `toggleScan()` from the ViewModel, gated by permissions + location services + not-already-scanning + `DISCONNECTED` state |

### Connection State Machines

```
eSense Pulse:

  DISCONNECTED
    │ startScan()
    ▼
  SCANNING
    │ device found + connect(device)
    ▼
  CONNECTING ──(8s timeout)──► ERROR / DISCONNECTED
    │ GATT STATE_CONNECTED
    │ discoverServices() → enableHeartRateNotifications() → write CCCD
    │ 5-second warmup begins
    ▼
  CONNECTED (warming up) — readings visible in UI, NOT recorded
    │ warmup complete (5s)
    ▼
  CONNECTED (measuring) — readings visible in UI AND recorded
    │ disconnect() or BT off or unexpected disconnect
    ▼
  DISCONNECTED
```

```
Fibion Flash:

  DISCONNECTED
    │ startScan()
    ▼
  SCANNING ──(15s, no devices found)──► ScanTimeout dialog (scan continues)
    │ device found + connect(device); scan stopped automatically
    ▼
  CONNECTING ──(20s timeout)──► ERROR → ConnectionTimeout dialog
    │ MDS onConnectionComplete(address, serial)
    │ auto-read device info + battery; battery polling starts (every 2 min)
    ▼
  CONNECTED
    │ subscribeHeartRate() + subscribeEcg()
    ▼
  CONNECTED (streaming) — HR + ECG data flowing to UI and recording
    │ disconnect() or BT off or unexpected MDS disconnect
    │ unsubscribeAll(); cancel battery polling; reset state
    ▼
  DISCONNECTED
```

---

## 5. Data Streams

### Heart Rate

| Property | eSense Pulse | Fibion Flash |
|---|---|---|
| Source | BLE characteristic `0x2A37` (NOTIFY) | `/Meas/HR` MDS subscription |
| Format | Binary byte array (BT SIG spec) | JSON: `Body.average` (float → truncated to Int) |
| Output rate | **~4.5 Hz** (225 ms period, clock-driven; observed 4.27–5.01 Hz) | **~1 per heartbeat** (scales with HR; measured 1.04–1.36 Hz across recordings, median Δ 758–958 ms) |
| Internal sensor rate | 500 Hz PPG (downsampled before BLE TX) | Single-channel ECG analog front-end, bandwidth **0.5–40 Hz**; selectable API output rates **125 / 128 / 200 / 250 / 256 / 500 / 512 Hz** (native ADC rate not published by vendor) |
| Measuring range | 30–240 BPM (±2 BPM accuracy per device spec) | ≈ **30–300 BPM** detection window — derived from `/Meas/HR/Info` R-R bounds (Min = 200 ms ↔ 300 BPM, Max = 2000 ms ↔ 30 BPM). No explicit BPM accuracy published. |
| SensorType (DB) | `HEART_RATE` | `FIBION_HEART_RATE` |
| SharedFlow buffer | 64 samples | 64 samples |

### R-R Intervals

> The raw data formats differ significantly. eSense Pulse uses the BT SIG binary format (1/1024-second units, requires conversion), while Fibion Flash delivers values already in milliseconds via JSON.

| Property | eSense Pulse | Fibion Flash |
|---|---|---|
| Source | Same `0x2A37` characteristic — bit 4 of flags byte signals R-R presence | Same `/Meas/HR` subscription — `Body.rrData` array |
| Raw format | UINT16 LE, resolution **1/1024 second** | Already in **milliseconds** (int array) |
| Conversion | `raw × 1000 / 1024` | None needed |
| Per notification | Variable — 0 or more intervals | Bundled *inside* each HR notification — one R-R value per HR push, so cadence matches HR (~1 per beat) |
| Availability | Only when device includes R-R data (flags bit 4 = 1) | Always present in HR notifications |
| SensorType (DB) | `ESENSE_RR_INTERVAL` | `FIBION_RR_INTERVAL` |
| SharedFlow buffer | **256** samples | 64 samples |
| Warmup gate | Yes (same 5-second gate as HR) | No |

### ECG — Fibion Flash only

> The ECG rate is **path-encoded** in the MDS URI (`/Meas/ECG/{rate}`), **not a fixed constant**. 125 Hz is the current default parameter on `subscribeEcg()`, not a hardware minimum.

| Property | Fibion Flash |
|---|---|
| Source | `/Meas/ECG/{rate}` (raw LSB) or `/Meas/ECG/{rate}/mV` (calibrated) |
| Unit | mV (firmware ≥ 2.3) or LSB raw units (older firmware) |
| Path-encoded rate | `{rate}` is whatever the firmware streams at — Movesense supports **125, 128, 200, 250, 256, 500, 512 Hz** (subset depends on product/firmware) |
| Current app setting | **125 Hz** via the default parameter on `FibionFlashManager.subscribeEcg(sampleRate: Int = 125)`. No constant, no UI control. All call sites use the default. |
| How to change the rate | Pass an explicit value at `SensorRecordingRepositoryImpl.kt:80` and `FibionFlashViewModel.kt:246`, or via `ConnectionRepository.subscribeFibionFlashEcg(rate)` (`ConnectionRepository.kt:194`) |
| Endpoint selection | On connect, `/Info` is read and the SW version parsed as semver: ≥ 2.3 → `/mV`, else → raw LSB. Parse failure falls back to raw LSB. |
| Per notification | Multiple samples batched (reduces BLE overhead) |
| SensorType (DB) | `FIBION_ECG` |
| SharedFlow buffer | **1024 samples** — buffer headroom ≈ **8.2 s @ 125 Hz**, **4.1 s @ 250 Hz**, **2 s @ 500 Hz**. If the rate is raised, the buffer must be raised proportionally. |
| Buffer overflow | `tryEmit()` returns false; a `Debug` event "ECG buffer full — sample dropped" is logged. **Non-blocking** — recording is never throttled by a slow consumer. |
| Observed in production | 125 Hz confirmed in 5 of 8 export files. Three files show ~250 Hz — likely a race between firmware-version detection and the initial subscribe, leaving both LSB and `/mV` subscriptions active. Treat as a separate bug. |
| Chart display | **Not plotted** — too dense for timeline; stored in DB & CSV export |

---

## 6. Battery Management

> eSense Pulse has no low-battery warning. Fibion Flash polls every 2 minutes and shows a dialog at ≤ 30%.

| Aspect | eSense Pulse | Fibion Flash |
|---|---|---|
| Read trigger | **Once automatically** in the CCCD-write descriptor callback (when HR notifications are enabled). No polling job; **manual re-read is available** via `BleManager.readBatteryLevel()`. | Once after connect, then every **2 minutes** while connected |
| Protocol | GATT Battery Service `0x180F / 0x2A19` (READ) | MDS GET `suunto://{serial}/System/Energy/Level` |
| Low battery warning | **None** — no threshold check, no dialog | Dialog shown once per connection at **≤ 30%** |
| Manual read | Yes — `BleManager.readBatteryLevel()` exposed on the interface and reachable from the ViewModel | `readBatteryLevel()` available from UI |

---

## 7. Subscription Model

| Aspect | eSense Pulse | Fibion Flash |
|---|---|---|
| Concept | No explicit subscriptions — HR notifications always active after CCCD write | Explicit `subscribeHeartRate()`, `subscribeEcg()`, `unsubscribeAll()` |
| Idempotency | N/A | Yes — re-subscribing an already-active path is silently skipped |
| Retry after failure | N/A | On `MdsException` the URI is removed from `activeSubscriptions` (`FibionFlashManagerImpl.kt:400`), so the next `subscribe*()` call re-issues it. Idempotency holds only while the subscription is alive. |
| On recording start | `enableHeartRateNotifications()` called if not already active | `subscribeHeartRate()` + `subscribeEcg()` called (idempotent) |
| On recording stop | Notifications remain active | Subscriptions remain active (user can view live data) |
| On disconnect | Notifications implicitly stopped by GATT disconnect | `unsubscribeAll()` called explicitly; polling cancelled |

---

## 8. Recording Integration

Both sensors are **recording-scoped** — data is collected within the `start_recording → stop_recording` VR window. `SensorRecordingRepositoryImpl` orchestrates all collectors.

> **Only one HR sensor records per session.** `SensorRecordingRepositoryImpl` reads `HeartRateDevicePreferences` and launches *either* the eSense Pulse collectors *or* the Fibion Flash collectors — never both. Respiration runs alongside whichever HR sensor is selected. Fibion ECG is auto-subscribed when Fibion recording starts; there is no per-recording toggle.

### Collector Jobs in `SensorRecordingRepositoryImpl`

| Job | Source Flow | SensorType | Rate |
|---|---|---|---|
| `heartRateJob` | `bleManager.heartRateSampleFlow` | `HEART_RATE` | ~4.5 Hz |
| `esenseRrJob` | `bleManager.rrIntervalSampleFlow` | `ESENSE_RR_INTERVAL` | ~1/beat |
| `fibionHrJob` | `fibionFlashManager.heartRateSampleFlow` | `FIBION_HEART_RATE` | ~1/beat |
| `fibionEcgJob` | `fibionFlashManager.ecgSampleFlow` | `FIBION_ECG` | 125 Hz (default) |
| `fibionRrJob` | `fibionFlashManager.rrIntervalSampleFlow` | `FIBION_RR_INTERVAL` | ~1/beat |

### RecordingEntity Fields

| Field | eSense Pulse | Fibion Flash |
|---|---|---|
| Enabled flag | `heartRateEnabled: Boolean` | `fibionEnabled: Boolean` |
| Sample count fields | `heartRateSampleCount`, `esenseRrIntervalSampleCount` | `fibionHeartRateSampleCount`, `fibionEcgSampleCount`, `fibionRrIntervalSampleCount` |

### Chart & Export Visibility

| SensorType | Timeline Chart | CSV Export |
|---|---|---|
| `HEART_RATE` | ✓ Plotted | ✓ |
| `ESENSE_RR_INTERVAL` | ✗ Not plotted | ✓ |
| `FIBION_HEART_RATE` | ✓ Plotted | ✓ |
| `FIBION_ECG` | ✗ Not plotted (too dense at 125 Hz) | ✓ |
| `FIBION_RR_INTERVAL` | ✗ Not plotted | ✓ |

---

## 9. Architecture & Dependency Injection

> Fibion Flash is wrapped in `ConnectionRepository` so that screens which don't own the sensor (`TestControlViewModel`, `HomeViewModel`, `SensorsViewModel`) can read its connection state and serial without injecting the manager directly. eSense Pulse is consumed by only two collaborators (`EsensePulseViewModel` and `SensorRecordingRepositoryImpl`) so direct injection is sufficient.

### eSense Pulse — Direct Injection

```
BleManagerImpl  (Hilt @Singleton)
  ├─► directly injected into  EsensePulseViewModel
  └─► directly injected into  SensorRecordingRepositoryImpl
```

### Fibion Flash — Via ConnectionRepository

```
FibionFlashManagerImpl  (Hilt @Singleton)
  ├─► injected into  ConnectionRepository  (app-wide state aggregator)
  │     ├─► injected into  FibionFlashViewModel
  │     └─► injected into  TestControlViewModel
  └─► directly injected into  SensorRecordingRepositoryImpl
```

---

## 10. UI State

| Property | eSense Pulse | Fibion Flash |
|---|---|---|
| Connection state | `connectionState` | `connectionState` |
| Scanning | `isScanning` | `isScanning` |
| Connected device | `connectedDevice` | `connectedDevice` |
| Device identity | `connectedDevice: BleDevice?` exposes name + MAC address; no separate serial or firmware fields | `deviceSerial`, `deviceInfo` (serial, SW/HW, product name) |
| Heart rate | `heartRate: Int?` | `heartRate: Int?` |
| HR monitoring toggle | `isMonitoringHeartRate: Boolean` — flips `true` *only after* the 5 s warmup completes; gates the "Start recording" affordance | None — HR subscription is managed implicitly via `subscribeHeartRate()` / `unsubscribeAll()`; no UI-state boolean. `isEcgSubscribed` plays the analogous role for ECG only. |
| ECG | Not available — eSense Pulse has no ECG stream, so no ECG-related state fields exist | `isEcgSubscribed: Boolean` — set `true` on every collected ECG sample (acts as an "ECG currently streaming" indicator); reset to `false` on disconnect. `ecgLatestSample: Float?` |
| Latest R-R | `latestRrInterval: Int?` | `latestRrInterval: Int?` |
| R-R history | `rrIntervalHistory: List<Float>` (last 30) | `rrIntervalHistory: List<Float>` (last 30) |
| Battery | `batteryLevel: Int?` | `batteryLevel: Int?`, `batteryLastUpdated: Long?` |
| GATT services | `discoveredServices: List<BleGattService>` | Not exposed — Fibion uses the Movesense MDS abstraction; raw GATT services are managed inside MDS and not surfaced to the UI |
| Debug log | `logEntries` (max 200, newest first) | `logEntries` (max 200, newest first) |

---

## 11. Event Systems

Both use sealed class hierarchies for debug logging. All events carry a timestamp and human-readable message. Events are logged as `List<LogEntry>` in the ViewModel (max 200, newest first) and displayed in a collapsible debug log.

### eSense Pulse — `BleEvent`

| Event | Description |
|---|---|
| `ScanStarted` / `ScanStopped` | Scan state changes |
| `DeviceFound(device)` | Matching device discovered |
| `Connecting` / `Connected` / `Disconnected` | Connection lifecycle |
| `HeartRateNotificationEnabled` / `Disabled` | CCCD write result |
| `HeartRateReceived(bpm)` | Per-sample event |
| `BatteryLevelRead(percent)` | Battery read result |
| `UnexpectedDisconnection(deviceName, status)` | Non-clean disconnect |
| `ConnectionTimeout(deviceName)` | 8-second timeout reached |
| `Error` / `Debug` | Generic messages |

### Fibion Flash — `FibionFlashEvent`

| Event | Description |
|---|---|
| `ScanStarted` / `ScanStopped` | Scan state changes |
| `DeviceFound(device)` | Matching device discovered |
| `Connecting` / `Connected(device, serial)` / `Disconnected` | Connection lifecycle — Connected event includes MDS serial |
| `SubscriptionStarted(path)` | **Fibion only** — MDS subscription success |
| `SubscriptionError(path, error)` | **Fibion only** — MDS subscription failure |
| `HeartRateReceived(bpm)` | Per HR notification |
| `BatteryLevelRead(percent)` | Battery read result |
| `DeviceInfoReceived(serial, productName)` | **Fibion only** — after `/Info` GET |
| `ConnectionTimeout(deviceName)` | 20-second timeout reached |
| `Error` / `Debug` | Generic messages |

---

## 12. Shared Components & Permissions

### Shared Implementations

| Component | Description |
|---|---|
| `RrIntervalCard` + `RrTachogramChart` | Latest R-R value + rolling 30-sample sparkline chart |
| `ConnectionStatusBadge` | Connection state visual indicator |
| `BleDevice` model | Scanned device representation (name, address, RSSI, signal strength) |
| `ConnectionState` enum | `DISCONNECTED / CONNECTING / CONNECTED / ERROR` |
| `SensorSampleEntity` | Universal database entity for all sensor samples |
| Permission handling pattern | Identical logic for API 31+ vs. API ≤ 30 |
| `LocationServicesRequired` dialog | Location Services must be enabled for BLE scan |

### Required Permissions (both sensors identical)

| Android version | Permissions required |
|---|---|
| API 31+ (Android 12+) | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` |
| API ≤ 30 (Android 11 and below) | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` |

> **Note:** Location Services (GPS) must be enabled in device settings for BLE scanning to function on both sensors, even on Android 12+.

---

## 13. Key Files

| Role | eSense Pulse | Fibion Flash |
|---|---|---|
| Manager interface | `data/sensor/ble/BleManager.kt` — defines HR/battery flows, scan/connect API, `BleEvent` sealed class inline | `data/sensor/fibion/FibionFlashManager.kt` — defines HR/ECG/R-R flows; `subscribeEcg(sampleRate: Int = 125)` default lives here |
| Manager implementation | `data/sensor/ble/BleManagerImpl.kt` (782 lines) | `data/sensor/fibion/FibionFlashManagerImpl.kt` (636 lines) |
| Recording orchestrator | `data/recording/SensorRecordingRepositoryImpl.kt` — launches HR collectors for the selected sensor (eSense XOR Fibion); calls `subscribeEcg()` for Fibion (line 80) | (same — shared) |
| Events (sealed class) | *Inline in `BleManager.kt`* | `data/sensor/fibion/FibionFlashEvent.kt` |
| Data models | `data/sensor/ble/model/BleDevice.kt` | `data/sensor/fibion/model/FibionFlashData.kt` |
| ViewModel | `…/mindfield/pulse/EsensePulseViewModel.kt` (397 lines) | `…/fibion/flash/FibionFlashViewModel.kt` |
| Screen (Compose UI) | `…/mindfield/pulse/EsensePulseScreen.kt` (781 lines) | `…/fibion/flash/FibionFlashScreen.kt` |
| R-R UI component | `…/sensors/components/RrIntervalDisplay.kt` — shared by both | (same — shared) |
| Documentation | [`sensor_esense_pulse.md`](sensor_esense_pulse.md) | [`sensor_fibion_flash.md`](sensor_fibion_flash.md) |

---

## 14. Summary: Key Differences

| Dimension | eSense Pulse | Fibion Flash |
|---|---|---|
| **BLE abstraction** | Raw Android GATT | Movesense MDS library (JSON over BLE) |
| **Data richness** | HR + R-R | HR + R-R + **ECG at 125 Hz** |
| **HR output rate** | ~4.5 Hz (225 ms period) | ~1 per heartbeat (1.0–1.4 Hz at resting HR) |
| **Concurrent HR streaming** | eSense Pulse and Fibion Flash **cannot record simultaneously** — exactly one is selected via `HeartRateDevicePreferences` | (same — see left cell) |
| **R-R raw format** | BT SIG binary (1/1024 sec, needs conversion) | JSON array (already in ms) |
| **ECG** | Not available | 125 Hz default — path-encoded and configurable via `subscribeEcg(sampleRate)`; Movesense supports 125 / 128 / 200 / 250 / 256 / 500 / 512 Hz. mV (fw ≥ 2.3) or LSB. |
| **Connection timeout** | 8 seconds | 20 seconds |
| **Connection priority** | `BALANCED` — 30–50 ms intervals, coexists with concurrent BLE connections | Managed internally by MDS |
| **Warmup after connect** | 5 seconds (samples not recorded) | None |
| **Subscription model** | Always-on after connect | Explicit subscribe / unsubscribe |
| **Battery polling** | Once on connect — **no low-battery warning** | Every 2 min — **dialog at ≤ 30%** |
| **Auto-reconnect** | None | 3 attempts × 5 s (unexpected disconnect) |
| **BT re-enable behavior** | No auto-scan (debug event only) | 3 s delay then ViewModel-driven auto-scan (gated by permissions, location, idle state) |
| **Device info exposed** | Not exposed | Serial, SW/HW version, product name |
| **DI architecture** | Direct injection | Via `ConnectionRepository` indirection |
| **DB sensor types** | 2: `HEART_RATE`, `ESENSE_RR_INTERVAL` | 3: `FIBION_HEART_RATE`, `FIBION_ECG`, `FIBION_RR_INTERVAL` |
| **Timeline chart** | HR plotted | HR plotted (ECG too dense — not plotted) |

---

*Generated from [`sensor_esense_pulse.md`](sensor_esense_pulse.md) and [`sensor_fibion_flash.md`](sensor_fibion_flash.md) — BioMetrix Operator.*

*Fibion / Movesense specs for §5 (sampling frequencies & ECG bandwidth; HR `/Info` Min/Max) sourced from the [Movesense Flash product page](https://www.movesense.com/product/movesense-flash/) and the [Movesense API reference](https://www.movesense.com/docs/esw/api_reference/).*
