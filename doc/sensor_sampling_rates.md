# Sensor Sampling Rates

Sampling rates, data types, and synchronization approach for all sensors in VitalWork.

## Overview

| Sensor | Vendor | Connection | Data Types | Rate |
|--------|--------|------------|------------|------|
| eSense Pulse | Mindfield | BLE | Heart Rate (BPM), R-R Intervals (ms) | ≈ 4.5 Hz (225 ms period; nominal 5 Hz) |
| eSense Respiration | Mindfield | Audio Jack | Respiration Amplitude (RA) | 5 Hz (configured) |

## Per-Sensor Details

### eSense Pulse (Heart Rate + R-R)

**Output rate: ≈ 4.5 Hz** (225 ms period, clock-driven; observed 4.27–5.01 Hz across recordings; nominal 5 Hz)

- Internal PPG sensor: 500 Hz, processed on-device before BLE transmission
- BLE Heart Rate Service (`0x180D`), characteristic `0x2A37`
- Each notification contains: BPM value + optionally R-R intervals (beat-to-beat timing in ms)
- Measuring range: 30–240 BPM (±2 BPM accuracy)

See [sensor_esense_pulse.md](sensor_esense_pulse.md) for protocol details.

### eSense Respiration

**Output rate: 5 Hz** (configurable via SDK, currently set to 5 Hz)

```kotlin
private const val SAMPLE_FREQ = 5  // in MindfieldRespiration.kt
```

- Respiration Amplitude (RA) — raw chest expansion/contraction waveform
- Breathing rate derived via zero-crossing detection on a 30-second window

**Nyquist analysis:** Normal breathing rate is 0.2–0.33 Hz. At 5 Hz, oversampling is >15x — more than adequate.

See [sensor_esense_respiration.md](sensor_esense_respiration.md) for protocol details.

## Data Collection

`SensorRecordingRepositoryImpl` collects samples from all connected sensors concurrently.

```
eSense Pulse ──(~4.5 Hz)──► heartRateSampleFlow ────────► HEART_RATE
                          ► rrIntervalSampleFlow ───────► ESENSE_RR_INTERVAL
eSense Respiration ──(5 Hz)──► sampleFlow ─────────────► RESPIRATION
```

Each sample is stored with:
- `timestampMs` — absolute timestamp (`System.currentTimeMillis()`)
- `elapsedMs` — time since recording start
- `sensorType` — one of the `SensorType` enum values
- `value` — the sensor reading (Float)

Samples are batched (50 samples or 1-second flush interval) before database write.

## Synchronization Approach

Sensors run at their native rates. Alignment is handled via timestamp-based storage:

- **eSense Pulse (~4.5 Hz) + Respiration (5 Hz)** are close enough in cadence that samples remain co-located on the timeline at the typical sub-second analysis resolution; precise alignment is done by per-sample timestamp regardless
- **R-R intervals** arrive at variable rates tied to heartbeat timing

All continuous sensor samples share the same `sensor_samples` table with per-sample timestamps, so downstream analysis can align across rates as needed.

## References

- [eSense Pulse Technical Documentation](https://mindfield-store.com/en/eSense-Pulse/)
- [Bluetooth Heart Rate Service Specification](https://www.bluetooth.com/specifications/specs/heart-rate-service-1-0/)
- [eSense SDK Documentation](https://mindfield-store.com/en/eSense-SDK/)
