# eSense Pulse — Sensor Reference

## Device Overview

| Property | Value |
|----------|-------|
| Vendor | Mindfield Biosignals |
| Product | eSense Pulse |
| Connection | Bluetooth Low Energy (BLE) |
| Measured signal | Heart Rate (BPM), R-R intervals (ms) |
| Additional data | Battery level (%) |

The eSense Pulse is a chest-strap heart rate sensor. It communicates via BLE using the standard Bluetooth SIG Heart Rate Profile.

## Communication Protocol

### Device Identification

Scanning uses no OS-level UUID filter (unfiltered scan, low-latency mode). Post-scan, the app keeps only devices that match either criterion:

| Filter | Value |
|--------|-------|
| Device name prefix | `eSense` (case-insensitive) |
| Manufacturer ID in advertisement | `0xFF0C` (65292) — Mindfield |

The device uses a **random BLE address type**.

### GATT Services and Characteristics

| Service | UUID | Characteristic | UUID | Access |
|---------|------|----------------|------|--------|
| Heart Rate | `0x180D` | Heart Rate Measurement | `0x2A37` | NOTIFY |
| Battery Service | `0x180F` | Battery Level | `0x2A19` | READ |

**CCCD (Client Characteristic Configuration Descriptor):** `0x2902` — written to enable remote notifications on `0x2A37`.

### Heart Rate Data Format

Per Bluetooth SIG Heart Rate Measurement specification:

```
Byte 0: Flags
  bit 0 — HR value format (0 = UINT8, 1 = UINT16)
  bit 3 — Energy Expended present (skip 2 bytes)
  bit 4 — R-R Interval(s) present

Byte 1 (or bytes 1–2 if UINT16): Heart Rate Value in BPM
[bytes N]:   Energy Expended (optional, 2 bytes, skipped)
[bytes N+]:  R-R Interval(s) (optional, variable — N × 2 bytes, UINT16 LE, 1/1024 sec resolution)
```

The device may send UINT8 or UINT16 format; the parser handles both. R-R values are converted to milliseconds as `raw * 1000 / 1024`. Zero-value intervals are discarded.

### Connection Priority

After service discovery, the app requests `CONNECTION_PRIORITY_BALANCED` (30–50 ms connection intervals). This is sufficient for the HR notification rate and coexists better with other concurrent BLE connections.

## Quirks and Known Behaviors

**5-second warmup after connection**
After notifications are enabled, a 5-second warmup period begins. During warmup:
- Heart rate values are displayed in the UI as normal.
- Values are **not** fed to the recording flows (`heartRateSampleFlow`, `rrIntervalSampleFlow`).
- This prevents noisy initial readings from entering recorded session data.

R-R interval samples are gated by the same warmup. If the device does not include R-R data in its notifications (flags bit 4 = 0), no R-R samples are emitted at all.

**Connection timeout: 8 seconds**
If the device does not connect within 8 seconds of `connect()` being called, the connection is aborted and a `ConnectionTimeout` event is emitted.

**Bluetooth adapter state monitoring**
The manager registers a `BroadcastReceiver` for `ACTION_STATE_CHANGED`. If Bluetooth is turned off mid-session, scanning stops and the active connection is cleaned up automatically.

**Unexpected disconnection detection**
If the GATT callback reports `STATE_DISCONNECTED` with a non-success status while the device was connected, a `BleEvent.UnexpectedDisconnection` is emitted (as opposed to a clean `Disconnected` event).

## Data Format

| Property | Value |
|----------|-------|
| Unit | BPM (beats per minute) |
| Output rate | ≈ 4.5 Hz (225 ms period, clock-driven; observed 4.27–5.01 Hz across recordings). Samples occasionally arrive in close pairs (~1–2 ms apart). |
| Internal device rate | 500 Hz (PPG sensor); processed before BLE transmission |
| Measuring range | 30–240 BPM (±2 BPM accuracy per device spec) |

See [sensor_sampling_rates.md](sensor_sampling_rates.md) for multi-sensor synchronization context.

### R-R Interval Data

| Property | Value |
|----------|-------|
| Unit | Milliseconds (ms, as Float) |
| Source | Flags bit 4 of Heart Rate Measurement characteristic (0x2A37) |
| Raw format | UINT16 LE, resolution 1/1024 second |
| Conversion | `raw × 1000 / 1024` |
| Per notification | Variable — 0 or more intervals per HR notification |
| Availability | Only when device includes R-R data in the notification payload |

R-R samples are emitted through `rrIntervalSampleFlow` (SharedFlow, buffer 256), subject to the same 5-second warmup gate as `heartRateSampleFlow`.

## App Implementation

### Key Files

| File | Role |
|------|------|
| [data/sensor/ble/BleManager.kt](../app/src/main/java/com/vitalwork/app/data/sensor/ble/BleManager.kt) | Interface (scan, connect, disconnect, HR notifications, battery) |
| [data/sensor/ble/BleManagerImpl.kt](../app/src/main/java/com/vitalwork/app/data/sensor/ble/BleManagerImpl.kt) | Android BLE implementation |
| [.../mindfield/pulse/EsensePulseViewModel.kt](../app/src/main/java/com/vitalwork/app/presentation/screens/sensors/mindfield/pulse/EsensePulseViewModel.kt) | UI state management |
| [.../mindfield/pulse/EsensePulseScreen.kt](../app/src/main/java/com/vitalwork/app/presentation/screens/sensors/mindfield/pulse/EsensePulseScreen.kt) | Compose UI |
| [.../sensors/components/RrIntervalDisplay.kt](../app/src/main/java/com/vitalwork/app/presentation/screens/sensors/components/RrIntervalDisplay.kt) | `RrIntervalCard` and `RrTachogramChart` Compose components for R-R display |

### Data Flow

```
eSense Pulse (BLE)
  └─► BleManagerImpl (GATT callback)
        └─► _heartRate (StateFlow<Int?>) ──────────────────► UI display
        └─► heartRateSampleFlow (SharedFlow<Float>) ────────► SensorRecordingRepository
        └─► rrIntervalSampleFlow (SharedFlow<Float>) ────────► SensorRecordingRepository
              (both flows blocked during 5-second warmup)
```

### R-R Interval UI State

`EsensePulseUiState` tracks R-R data for live display:

| Property | Type | Purpose |
|----------|------|---------|
| `latestRrInterval` | `Int?` | Most recent R-R interval in ms; null when not monitoring |
| `rrIntervalHistory` | `List<Float>` | Rolling last-30 R-R values for the tachogram chart |

Both are cleared on disconnect and when HR notifications are disabled. `RrIntervalCard` renders these in the Heart Rate tab alongside HR monitoring controls.

### Recording Integration

eSense Pulse data is **recording-scoped** — it is collected within the `start_recording` → `stop_recording` VR window, alongside eSense Respiration.

When a recording starts, `SensorRecordingRepositoryImpl` checks if the eSense Pulse is connected (`ConnectionState.CONNECTED`). If so:
1. `enableHeartRateNotifications()` is called explicitly to ensure HR notifications are active before collecting begins.
2. Two collector coroutines are launched:
   - `heartRateSampleFlow` → `SensorType.HEART_RATE` samples in `sensor_samples` table
   - `rrIntervalSampleFlow` → `SensorType.ESENSE_RR_INTERVAL` samples in `sensor_samples` table (same BLE characteristic, zero extra cost)
3. Sample counts are tracked in `RecordingEntity` (`heartRateSampleCount`, `esenseRrIntervalSampleCount`).

When recording stops, collectors are cancelled but HR notifications remain active. The user can continue viewing live heart rate and R-R data after recording ends.

**Chart display:** `HEART_RATE` samples are shown on the test review timeline chart. `ESENSE_RR_INTERVAL` samples are recorded to the database and included in CSV export, but are not plotted on the timeline.

### Database Schema

eSense Pulse data uses the existing `sensor_samples` table with two dedicated sensor types:

```sql
-- SensorType enum values used by eSense Pulse:
-- HEART_RATE           — BPM value (one sample per HR notification, as Float)
-- ESENSE_RR_INTERVAL   — R-R interval in ms (one row per inter-beat interval, as Float)

-- Recording entity fields:
-- heartRateEnabled: Boolean              — whether eSense Pulse was connected at recording start
-- heartRateSampleCount: Int              — running count of HR samples
-- esenseRrIntervalSampleCount: Int       — running count of R-R interval samples
```

### Connection State Machine

```
DISCONNECTED
  │ startScan()
  ▼
SCANNING
  │ device found + connect(device)
  ▼
CONNECTING ──(8s timeout)──► ERROR / DISCONNECTED
  │ GATT STATE_CONNECTED
  ▼
CONNECTED
  │ discoverServices() → enableHeartRateNotifications() → write CCCD
  │ 5-second warmup begins
  ▼
CONNECTED (warming up) — readings visible in UI, not recorded
  │ warmup complete (5s)
  ▼
CONNECTED (measuring) — readings visible in UI and recorded
  │ disconnect() or BT off or unexpected disconnect
  ▼
DISCONNECTED
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

> **Note:** Location Services (GPS) must be enabled in device settings for BLE scanning to function, even on Android 12+.

## Troubleshooting and Edge Cases

**Device not found during scan**
- Ensure the eSense Pulse is powered on and in range.
- Verify Location Services are enabled on the tablet.
- The device must not be already connected to another host.

**Connection timeout (8 seconds)**
- The device may have gone to sleep. Adjust the strap to re-establish skin contact.
- Try scanning again; the device should re-advertise.

**Unexpected disconnection during session**
- Strap lost skin contact, or device ran out of battery.
- Battery level is read once automatically after connection; there is no low-battery threshold warning for eSense Pulse. However, a manual re-read is exposed via `BleManager.readBatteryLevel()` and can be triggered from the eSense Pulse screen.
- The ViewModel receives a `BleEvent.UnexpectedDisconnection` and can notify the user.

**HR values during warmup**
- Readings shown in the UI during the 5-second warmup are intentionally excluded from session recording. This is by design, not a bug.

**Bluetooth turned off mid-session**
- The BroadcastReceiver in `BleManagerImpl` detects this and emits `BleEvent.Disconnected("Bluetooth disabled")`. The session recording should be stopped from the ViewModel in response.
