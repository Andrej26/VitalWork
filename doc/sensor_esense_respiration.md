# eSense Respiration — Sensor Reference

## Device Overview

| Property | Value |
|----------|-------|
| Vendor | Mindfield Biosignals |
| Product | eSense Respiration |
| Connection | 3.5mm audio jack |
| Measured signal | Respiration Amplitude (RA) — chest expansion waveform |
| Derived signal | Breathing rate (br/min) |

The eSense Respiration is a chest-strap respiratory sensor that connects via the audio jack of the tablet. It requires no Bluetooth pairing. The device measures chest expansion and contraction as a raw amplitude value (RA), from which the app derives the breathing rate using zero-crossing detection.

The SDK (`de.mindfield.esense_sdk_2_lib`) is provided as a local JAR at `app/libs/eSense_sdk_2_lib.jar`.

## Communication Protocol

### Connection Type

Audio jack (3.5mm). The SDK internally handles audio input capture via `HardwareController`. There is no BLE involved.

**Important:** `HardwareController.getInstance()` **must be called on the Main thread**. Calling it from a background thread causes a crash. `MindfieldRespiration` uses `Dispatchers.Main` for its coroutine scope for this reason.

### SDK Configuration

```kotlin
controller?.setSensorType(1)            // Toggle type to force internal SDK reset
controller?.setSampleFrequencyHz(SAMPLE_FREQ)
controller?.setSensorType(SENSOR_TYPE_INT)  // 3 = respiration (RA)
```

| Constant | Value | Meaning |
|----------|-------|---------|
| `SENSOR_TYPE_INT` | `3` | Respiration amplitude mode |
| `SAMPLE_FREQ` | `5` | Sample rate in Hz |

> See [sensor_sampling_rates.md](sensor_sampling_rates.md) for discussion of the configured rate and synchronization with eSense Pulse.

### Respiration Amplitude (RA) Range

| Condition | RA Value |
|-----------|----------|
| Normal breathing | 0.5 – 420 |
| No signal / flat | < 0.4 |
| Microphone active (jack removed) | ~500 |
| Valid range max | 460 |

## Quirks and Known Behaviors

**Verification phase on every connect (2.5 seconds)**
After the SDK starts sampling, a 2.5-second verification phase runs before the device is considered connected:
- Minimum 5 samples must arrive (`VERIFY_MIN_SAMPLES = 5`).
- RA values must be within `[0.0, 460.0]`.
- RA must show at least some variation (`delta ≥ 0.02`).
If verification fails, the sensor force-disconnects with a reason message.

**Auto-start streaming after verification**
If verification succeeds, streaming starts automatically without any additional user action. The watchdog also starts at this point.

**Watchdog runs every 2 seconds during streaming**
Three conditions cause automatic disconnection:
1. No data received for more than 1250ms (`TIMEOUT_MS`) — cable issue or SDK freeze.
2. RA drops below `RANGE_MIN` (0.0) — signal out of range.
3. RA stays above `RANGE_MAX` (460.0) for more than 1000ms (`SPIKE_DISCONNECT_MS`) — jack likely removed, microphone becomes active.

**Low-signal warning (not a disconnection)**
If RA stays below 0.4 (`LOW_SIGNAL_THRESHOLD`) for more than 1.5 seconds (`LOW_SIGNAL_WARNING_MS`), a `LowSignalWarning.WARNING` state is emitted. This prompts the user to check chest-strap placement. The warning clears automatically when the signal recovers.

**Type toggle on connect**
The SDK is always reset by switching to type 1 and back to type 3 on each connect. This flushes internal SDK state and prevents stale data from a previous session from affecting the new connection.

**Streaming can be paused and resumed**
`stopStreaming()` pauses data collection and stops the watchdog while keeping the SDK sampling. `startStreaming()` resumes. The sensor remains in `Connected` state during a pause.

## Data Format

| Property | Value |
|----------|-------|
| Raw unit | Respiration Amplitude (RA) — dimensionless |
| Derived unit | Breathing rate in br/min |
| Output rate | 5 Hz (as set in this application) |

### Breathing Rate Calculation

Breathing rate is derived from the RA waveform using **zero-crossing detection**:

1. A sliding window of the last 30 seconds of RA samples is maintained (30 × `SAMPLE_FREQ` = 150 samples).
2. The mean of the window is computed.
3. Upward crossings of the mean are counted — each crossing represents one breath cycle.
4. Rate in br/min = `(crossings / window_duration_sec) × 60`.

At least 3 seconds of data (`RATE_MIN_SAMPLES = 3 × SAMPLE_FREQ = 15`) are required before a rate is returned; below that threshold the rate is reported as `0`.

See [sensor_sampling_rates.md](sensor_sampling_rates.md) for multi-sensor synchronization context.

## App Implementation

### Key Files

| File | Role |
|------|------|
| [data/sensor/audio/MindfieldRespiration.kt](../app/src/main/java/com/biometrix/operator/data/sensor/audio/MindfieldRespiration.kt) | Singleton SDK wrapper (connect, stream, watchdog, rate calculation) |
| [.../respiration/EsenseRespirationViewModel.kt](../app/src/main/java/com/biometrix/operator/presentation/screens/sensors/mindfield/respiration/EsenseRespirationViewModel.kt) | UI state management |
| [.../respiration/EsenseRespirationScreen.kt](../app/src/main/java/com/biometrix/operator/presentation/screens/sensors/mindfield/respiration/EsenseRespirationScreen.kt) | Compose UI |

`MindfieldRespiration` is a Kotlin `object` (singleton). Only one instance exists per process lifetime.

### Data Flow

```
eSense Respiration (audio jack)
  └─► HardwareController (eSense SDK)
        └─► sdkObserver.valueHasChanged(SensorData)
              └─► _dataRate (StateFlow<Float>) ──────────────────► UI (raw RA)
              └─► _detailedStats (StateFlow<String>) ──────────────► UI (br/min)
              └─► sampleFlow (SharedFlow<Float>) ─────────────────► SensorRecordingRepository
              └─► lowSignalWarning (StateFlow) ──────────────────► UI warning
```

### Connection State Machine

```
DISCONNECTED
  │ connect(context)
  ▼
CONNECTING
  │ SDK initializes on Main thread
  │ setSensorType, setSampleFrequencyHz, startSampling
  │ 2.5-second verification phase
  │   ├─ fail (no/invalid signal) → forceDisconnect()
  ▼
CONNECTED
  │ verification passed → startStreaming() called automatically
  ▼
STREAMING ──── watchdog running (every 2s)
  │  ├─ no data > 1250ms → forceDisconnect("Connection Lost")
  │  ├─ RA < 0.0 → forceDisconnect("Signal Out of Range")
  │  ├─ RA > 460 sustained > 1000ms → forceDisconnect("Signal abnormality")
  │  └─ RA < 0.4 sustained > 1500ms → LowSignalWarning.WARNING (no disconnect)
  │
  │ stopStreaming()
  ▼
CONNECTED (paused)
  │ startStreaming()
  ▼
STREAMING
  │ disconnect()
  ▼
DISCONNECTED
```

## Required Permissions

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

No Bluetooth or Location permissions are required for this sensor.

## Troubleshooting and Edge Cases

**Verification fails: "No Signal"**
- Fewer than 5 samples received in 2.5 seconds — SDK did not start properly.
- Try disconnecting and reconnecting. Ensure the audio jack is fully seated.

**Verification fails: "Signal Out of Range"**
- RA values outside `[0.0, 460.0]` — usually means the wrong audio device is connected or there is electrical noise.
- If RA is ~500, the jack is likely pulled out and the tablet microphone is active.

**Watchdog: "Connection Lost (No Data)"**
- No SDK callback for 1250ms while streaming. The cable may be partially disconnected.
- Reconnect the jack and start a new connection.

**Low signal warning during streaming**
- RA is consistently below 0.4. Check that the chest strap is correctly positioned and snug.
- The warning clears automatically once the signal recovers.

**"Signal abnormality detected (device may be disconnected)"**
- RA has been above 460 for more than 1 second. This typically means the jack was pulled and the tablet's microphone became the audio source.
- Reconnect the sensor.

**SDK must be on Main thread**
- If `HardwareController.getInstance()` is called from a background thread, a crash ("Flash" crash) occurs. `MindfieldRespiration` is already guarded against this via `Dispatchers.Main`, but any future code interacting with the controller must respect this constraint.

**Disconnect reason persists**
- After a `forceDisconnect`, the reason string is stored in `lastDisconnectReason`. The UI can display this as a dialog. Call `clearDisconnectReason()` after the user dismisses the dialog to reset the state.
