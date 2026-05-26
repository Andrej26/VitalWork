# Fibion Flash — Sensor Reference

## Device Overview

| Property | Value |
|----------|-------|
| Vendor | Fibion (Movesense platform) |
| Product | Fibion Flash |
| Connection | Bluetooth Low Energy (BLE) via Movesense MDS |
| Measured signals | Heart Rate (BPM), ECG (mV or raw), R-R intervals (ms) for HRV |
| Additional data | Battery level (%), Device info (serial, SW/HW version, product name) |
| Operating mode | Chest mode only |

The Fibion Flash is a chest-worn wearable sensor built on the Movesense platform. It communicates over BLE but, unlike the eSense Pulse, does not use standard Bluetooth SIG GATT profiles directly. Instead, after the initial BLE connection, all sensor communication is handled through the **Movesense MDS library** which exposes a REST-like API over the BLE transport.

The device always operates in **Chest mode** — Heart Rate and ECG are the only available data streams. There is no mode selection in the app.

## Communication Protocol

### Device Identification

Scanning uses no OS-level UUID or manufacturer filter (unfiltered scan, low-latency mode). Post-scan, the app keeps only devices that match the name prefix:

| Filter | Value |
|--------|-------|
| Device name prefix | `Movesense` (case-insensitive) |

No manufacturer ID filter is applied. The `filterByName` parameter can be set to `false` for debug/discovery mode, which passes all nearby BLE devices through.

### Movesense MDS Library

| Property | Value |
|----------|-------|
| Library | `mdslib-3.27.0-release.aar` |
| Location | `app/libs/mdslib-3.27.0-release.aar` |
| Device identifier | Serial number (provided by MDS on `onConnectionComplete`) |

After BLE discovery, the MDS library manages the full connection lifecycle — GATT negotiation, service discovery, and data streaming are all internal to MDS. The app interacts exclusively through the MDS REST-like API using the device's serial number as the path prefix.

#### Transitive Dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `com.polidea.rxandroidble2:rxandroidble` | 1.17.2 | BLE transport (used internally by MDS) |
| `io.reactivex.rxjava2:rxjava` | 2.2.21 | Reactive streams for MDS internals |
| `io.reactivex.rxjava2:rxandroid` | 2.1.1 | Android scheduler for RxJava |
| `com.google.code.gson:gson` | 2.10.1 | JSON parsing for MDS responses |

### MDS API Endpoints

#### Subscriptions (streaming data)

| Path | Method | Response Format | Description |
|------|--------|----------------|-------------|
| `{serial}/Meas/HR` | Subscribe | `{"Body": {"average": <float>, "rrData": [<int>, ...]}}` | Heart rate in BPM + R-R intervals in ms |
| `{serial}/Meas/ECG/{rate}` | Subscribe | `{"Body": {"Samples": [<int>, ...]}}` | Raw ECG samples in LSB units |
| `{serial}/Meas/ECG/{rate}/mV` | Subscribe | `{"Body": {"Samples": [<float>, ...]}}` | ECG samples in millivolts (firmware >= 2.3) |

The `{rate}` in the URI is the device-streamed sample rate. Movesense supports 125, 128, 200, 250, 256, 500, and 512 Hz depending on firmware. **The app currently defaults to 125 Hz** through `FibionFlashManager.subscribeEcg(sampleRate: Int = 125)`; no caller overrides it, so to raise the rate you must pass an explicit value at the two call sites (`SensorRecordingRepositoryImpl` and `FibionFlashViewModel`) or via `ConnectionRepository.subscribeFibionFlashEcg(rate)`. The ECG SharedFlow buffer is 1024 samples (~8 s @ 125 Hz, ~2 s @ 500 Hz) and should be raised in proportion if the rate is increased.

On firmware >= 2.3, the `/mV` endpoint is used automatically for calibrated millivolt values; older firmware falls back to the raw LSB path.

#### GET Requests (one-shot reads)

| URI | Response Format | Description |
|-----|----------------|-------------|
| `suunto://{serial}/System/Energy/Level` | `{"Content": <int>}` | Battery level (0–100) |
| `suunto://{serial}/Info` | `{"Content": {"serial": "...", "sw": "...", "hw": "...", "productName": "..."}}` | Device info |

### Heart Rate Data Format

The `/Meas/HR` subscription returns a JSON object with `Body.average` (float BPM) and `Body.rrData` (array of R-R intervals in ms). The app parses both: BPM is truncated to integer for display, R-R intervals are emitted as individual samples for HRV analysis.

```json
{
  "Body": {
    "average": 72.0,
    "rrData": [832, 845]
  }
}
```

### ECG Data Format

The `/Meas/ECG/{rate}` subscription returns batches of raw integer samples in the `Body.Samples` array. Each sample is a raw µV value. At the default 125 Hz, each notification batches multiple samples to reduce BLE overhead.

```json
{
  "Body": {
    "Samples": [123, -45, 67, 234, ...]
  }
}
```

## Quirks and Known Behaviors

**Connection timeout: 20 seconds**
If MDS does not report `onConnectionComplete` within 20 seconds of `connect()`, the connection is aborted, `ConnectionTimeout` event is emitted, and MDS disconnect is called for cleanup.

**Auto-read on connection**
Immediately after MDS reports a successful connection, the app automatically reads device info (`/Info`) and battery level (`/System/Energy/Level`). No user action is required.

**Battery polling: every 2 minutes**
A background coroutine reads the battery level every 2 minutes while connected. The polling job is cancelled on disconnect or Bluetooth disable.

**Low battery warning at 30%**
If the battery level drops to 30% or below, a dialog is shown to the user. This dialog is shown only once per connection to avoid repeated alerts.

**Scan timeout: 15 seconds**
If no Movesense devices are found within 15 seconds of starting a scan, a `ScanTimeout` dialog is shown. The timeout is cancelled early if any device is found.

**Bluetooth adapter state monitoring**
A `BroadcastReceiver` listens for `ACTION_STATE_CHANGED`. If Bluetooth is turned off mid-session, scanning stops, the active MDS connection is torn down, and all state is reset. When Bluetooth is re-enabled, the ViewModel waits 3 seconds and then automatically initiates a scan if permissions are granted and no device is connected.

**Chest mode only — no mode selection**
The Fibion Flash supports multiple modes on the Movesense platform, but this app always operates in Chest mode. Both HR and ECG subscriptions are started together. There is no UI to select a different mode.

**Auto-reconnection on unexpected disconnect**
If the BLE connection is lost unexpectedly (not user-initiated), the manager automatically attempts to reconnect up to 3 times with 5-second intervals. Auto-reconnect is cancelled if the user explicitly disconnects or Bluetooth is disabled.

**ECG millivolt endpoint (firmware >= 2.3)**
The app checks the device firmware version after connection. If SW version is >= 2.3, ECG is subscribed via `/Meas/ECG/{rate}/mV` (calibrated millivolt values). Older firmware falls back to the raw `/Meas/ECG/{rate}` path (LSB units). Current default rate is 125 Hz.

**ECG buffer overflow logging**
ECG uses a SharedFlow buffer of 1024 samples. Buffer headroom shrinks linearly with rate — ~8.2 s @ 125 Hz, ~4.1 s @ 250 Hz, ~2 s @ 500 Hz; the buffer must be raised in proportion if the configured rate is increased. On overflow `tryEmit()` returns false and a `Debug` event is emitted; the emission path is **non-blocking**, so recording is never throttled by a slow consumer — dropped samples are logged so the data loss is visible.

**Debug scan mode**
The `startScan(filterByName)` parameter defaults to `true` (only Movesense-prefixed devices). Setting it to `false` shows all nearby BLE devices, useful for debugging.

**MDS uses serial number as device identifier**
After BLE connection, MDS provides the device serial number via `onConnectionComplete`. All subsequent REST calls use this serial as the path prefix, not the BLE MAC address.

## Data Format

| Property | Heart Rate | ECG | R-R Interval |
|----------|-----------|-----|-------------|
| Unit | BPM (beats per minute) | mV (firmware >= 2.3) or LSB (older) | ms (milliseconds) |
| Source path | `/Meas/HR` | `/Meas/ECG/{rate}/mV` or `/Meas/ECG/{rate}` (current default rate = 125) | `/Meas/HR` (parsed from `rrData`) |
| Output rate | ~1 notification per heartbeat (≈1.0–1.4 Hz at resting HR; not a fixed clock) | 125 Hz (default; configurable via `subscribeEcg(sampleRate)` — Movesense supports 125/128/200/250/256/500/512 Hz) | ~1 per heartbeat (bundled inside each HR notification) |
| Value type | Integer (truncated from `average`) | Float array (samples per notification) | Integer array (inter-beat intervals) |
| Recording sensor type | `FIBION_HEART_RATE` | `FIBION_ECG` | `FIBION_RR_INTERVAL` |
| HRV use | Basic stress indicator | Post-processing for R-peak detection | Direct HRV analysis (RMSSD, SDNN, LF/HF) |

| Property | Battery |
|----------|---------|
| Unit | Percentage (0–100) |
| Source URI | `suunto://{serial}/System/Energy/Level` |
| Read method | One-shot GET (not streaming) |
| Poll interval | Every 2 minutes while connected |

### Vendor Specifications (per Movesense)

| Property | Value |
|----------|-------|
| ECG analog front-end bandwidth | 0.5–40 Hz |
| Supported ECG sample rates | 125 / 128 / 200 / 250 / 256 / 500 / 512 Hz (subset depends on product/firmware) |
| HR detection window | ≈ 30–300 BPM (derived from `/Meas/HR/Info` R-R bounds Min = 200 ms, Max = 2000 ms) |
| Native ECG ADC rate | Not published by vendor |

Sources: [Movesense Flash product page](https://www.movesense.com/product/movesense-flash/), [Movesense API reference](https://www.movesense.com/docs/esw/api_reference/).

## App Implementation

### Key Files

| File | Role |
|------|------|
| [data/sensor/fibion/FibionFlashManager.kt](../app/src/main/java/com/biometrix/operator/data/sensor/fibion/FibionFlashManager.kt) | Interface (scan, connect, subscribe HR/ECG, battery, device info) |
| [data/sensor/fibion/FibionFlashManagerImpl.kt](../app/src/main/java/com/biometrix/operator/data/sensor/fibion/FibionFlashManagerImpl.kt) | Implementation — Android BLE scanning + Movesense MDS |
| [data/sensor/fibion/FibionFlashEvent.kt](../app/src/main/java/com/biometrix/operator/data/sensor/fibion/FibionFlashEvent.kt) | Sealed class for debug/log events |
| [data/sensor/fibion/model/FibionFlashData.kt](../app/src/main/java/com/biometrix/operator/data/sensor/fibion/model/FibionFlashData.kt) | `FibionFlashDeviceInfo` data class |
| [.../fibion/flash/FibionFlashViewModel.kt](../app/src/main/java/com/biometrix/operator/presentation/screens/sensors/fibion/flash/FibionFlashViewModel.kt) | UI state management, dialog logic, scan/connection lifecycle |
| [.../fibion/flash/FibionFlashScreen.kt](../app/src/main/java/com/biometrix/operator/presentation/screens/sensors/fibion/flash/FibionFlashScreen.kt) | Compose UI |

`FibionFlashManagerImpl` is instantiated as a singleton via Hilt (`@Provides @Singleton` in `AppModule.kt`). It survives screen navigation and is injected into `ConnectionRepository` and `SensorRecordingRepositoryImpl`.

### Data Flow

```
Fibion Flash (BLE)
  └─► Android BluetoothLeScanner (discovery only)
        └─► Movesense MDS (connection + data)
              │
              ├─ /Meas/HR subscription
              │    ├─► _heartRate (StateFlow<Int?>) ──────────────────► UI display
              │    ├─► heartRateSampleFlow (SharedFlow<Float>) ────────► SensorRecordingRepository
              │    └─► rrIntervalSampleFlow (SharedFlow<Float>) ───────► SensorRecordingRepository (HRV data)
              │
              ├─ /Meas/ECG/{rate}[/mV] subscription (default rate = 125 Hz)
              │    └─► ecgSampleFlow (SharedFlow<Float>) ──────────────► SensorRecordingRepository
              │
              ├─ /System/Energy/Level GET
              │    └─► _batteryLevel (StateFlow<Int?>) ────────────────► UI display
              │
              └─ /Info GET
                   └─► _deviceInfo (StateFlow<FibionFlashDeviceInfo?>) ► UI display
```

### Connection State Machine

```
DISCONNECTED
  │ startScan()
  ▼
SCANNING ──(15s, no devices found)──► ScanTimeout dialog (scan continues)
  │ device found + connect(device)
  │ scan stopped automatically
  ▼
CONNECTING ──(20s timeout)──► ERROR → ConnectionTimeout dialog
  │ MDS onConnectionComplete(address, serial)
  │ auto-read device info + battery
  │ battery polling starts (every 2 min)
  ▼
CONNECTED
  │ subscribeHeartRate() + subscribeEcg()
  ▼
CONNECTED (streaming) — HR + ECG data flowing to UI and recording
  │ disconnect() or BT off or unexpected MDS disconnect
  │ unsubscribeAll(), cancel battery polling, reset state
  ▼
DISCONNECTED
```

### Recording Integration

Fibion Flash data is **recording-scoped** — it is collected within the `start_recording` → `stop_recording` VR window, the same as eSense Pulse and eSense Respiration.

**Only one HR sensor records per session.** If the active heart-rate device preference is set to eSense Pulse, the Fibion collectors below are not started. See [`SensorRecordingRepositoryImpl`](../app/src/main/java/com/biometrix/operator/data/recording/SensorRecordingRepositoryImpl.kt) and [`HeartRateDevicePreferences`](../app/src/main/java/com/biometrix/operator/data/prefs/HeartRateDevicePreferences.kt).

When a recording starts, `SensorRecordingRepositoryImpl` checks if the Fibion Flash is connected **and** that it is the selected HR device. If both conditions hold:
1. Both HR and ECG subscriptions are started (idempotent — already-subscribed paths are skipped). If a subscription fails with `MdsException`, the URI is removed from `activeSubscriptions` so the next `subscribe*()` call re-issues it; idempotency holds only while the subscription is alive.
2. Three collector coroutines are launched:
   - `heartRateSampleFlow` → `SensorType.FIBION_HEART_RATE` samples in `sensor_samples` table
   - `ecgSampleFlow` → `SensorType.FIBION_ECG` samples in `sensor_samples` table
   - `rrIntervalSampleFlow` → `SensorType.FIBION_RR_INTERVAL` samples in `sensor_samples` table
3. Sample counts are tracked in `RecordingEntity` (`fibionHeartRateSampleCount`, `fibionEcgSampleCount`, `fibionRrIntervalSampleCount`).

When recording stops, subscriptions are not automatically unsubscribed (unlike recording start which auto-subscribes). The user can continue viewing live data after recording ends.

**Chart display:** Only `FIBION_HEART_RATE` is shown on the test review timeline chart. ECG samples are recorded but not charted — raw ECG waveforms at 125 Hz are too dense for the timeline view.

### Database Schema

Fibion data uses the existing `sensor_samples` table with two dedicated sensor types:

```sql
-- SensorType enum values used by Fibion:
-- FIBION_HEART_RATE    — BPM value (one sample per HR notification)
-- FIBION_ECG           — ECG value in mV or LSB (one row per ECG sample at the configured rate; currently 125 Hz)
-- FIBION_RR_INTERVAL   — R-R interval in ms (one row per inter-beat interval)

-- Recording entity fields:
-- fibionEnabled: Boolean                — whether Fibion was connected at recording start
-- fibionHeartRateSampleCount: Int       — running count of HR samples
-- fibionEcgSampleCount: Int             — running count of ECG samples
-- fibionRrIntervalSampleCount: Int      — running count of R-R interval samples
```

## Required Permissions

### Android 12 and above (API 31+)
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

### Android 11 and below (API 30 and below)
```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

> **Note:** Location Services (GPS) must be enabled in device settings for BLE scanning to function, even on Android 12+. The ViewModel checks `LocationManager.isLocationEnabled()` before starting a scan and shows a dialog if location is disabled.

## Troubleshooting and Edge Cases

**Device not found during scan**
- Ensure the Fibion Flash is powered on and in range.
- Verify Location Services are enabled on the tablet.
- The device must not be already connected to another host (phone, laptop).
- The device advertises as "Movesense", not "Fibion" — this is expected. The app filters by the `Movesense` name prefix.

**Connection timeout (20 seconds)**
- The Fibion Flash may need a firmware restart. Remove and re-attach the device, then scan again.
- MDS connection is more complex than standard GATT — the 20-second timeout is intentionally longer than the eSense Pulse's 8-second timeout.

**Subscription error after connecting**
- If a subscription to `/Meas/HR` or `/Meas/ECG/{rate}` fails, a `SubscriptionError` event is emitted and logged.
- The subscription can be retried from the UI without reconnecting.
- Check the debug log on the Fibion Flash sensor screen for the specific error message.

**No heart rate or ECG data after subscribing**
- Verify the device is properly positioned on the chest with good skin contact.
- Check the debug log for parse errors — if the MDS response format has changed, parse errors will appear.

**Bluetooth turned off mid-session**
- The `BroadcastReceiver` detects this and emits `Disconnected("Bluetooth disabled")`. All state is cleaned up automatically.
- When Bluetooth is re-enabled, the ViewModel waits 3 seconds and then auto-starts a scan if permissions are granted.

**Battery level shows as null**
- Battery is read via a GET request after connection and every 2 minutes. If the first read fails (e.g., device was busy), it will retry on the next polling cycle.
- The `readBatteryLevel()` function can be triggered manually from the UI.

**ECG data not visible in test review chart**
- By design. Only `FIBION_HEART_RATE` is plotted on the timeline chart. ECG samples are recorded to the database and included in CSV export, but raw 125 Hz ECG waveforms are too dense for the test timeline view.

**ECG appears to stream at ~250 Hz instead of the configured rate**
- Observed in 3 of 8 historical exports. Likely a race between firmware-version detection (which switches the path from raw LSB to `/mV`) and the first subscribe call, leaving both `/Meas/ECG/{rate}` and `/Meas/ECG/{rate}/mV` subscriptions active. Treat as a separate bug, not a configuration issue.

**Concurrent scanning with eSense Pulse**
- Both sensors use separate BLE scans with different post-scan filters (name prefix `Movesense` vs. `eSense` / manufacturer ID `0xFF0C`). Android supports concurrent scans without conflict.
