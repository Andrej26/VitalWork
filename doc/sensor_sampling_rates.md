# Sensor Sampling Rates

Sampling rates, data types, and synchronization approach for all sensors in BioMetrixOperator.

## Overview

| Sensor | Vendor | Connection | Data Types | Rate |
|--------|--------|------------|------------|------|
| eSense Pulse | Mindfield | BLE | Heart Rate (BPM), R-R Intervals (ms) | ≈ 4.5 Hz (225 ms period; nominal 5 Hz) |
| eSense Respiration | Mindfield | Audio Jack | Respiration Amplitude (RA) | 5 Hz (configured) |
| Fibion Flash | Fibion | BLE (Movesense MDS) | Heart Rate (BPM), R-R Intervals (ms), ECG (mV) | HR: ~1/heartbeat (≈ 1.0–1.4 Hz at rest); ECG: 125 Hz (default; configurable) |
| Beurer BC87 | Beurer | BLE | Blood Pressure (mmHg), Pulse Rate (bpm) | Episodic (one-shot) |

## Per-Sensor Details

### eSense Pulse (Heart Rate + R-R)

**Output rate: ≈ 4.5 Hz** (225 ms period, clock-driven; observed 4.27–5.01 Hz across recordings; nominal 5 Hz)

- Internal PPG sensor: 500 Hz, processed on-device before BLE transmission
- BLE Heart Rate Service (`0x180D`), characteristic `0x2A37`
- Each notification contains: BPM value + optionally R-R intervals (beat-to-beat timing in ms)
- Measuring range: 30–240 BPM (±2 BPM accuracy)

See `sensor_comparison_pulse_vs_fibion.html` §5 for the measured-rate breakdown.

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

### Fibion Flash (HR + ECG + R-R)

**ECG rate: 125 Hz** (default *output* rate; configurable via `subscribeEcg(sampleRate)`. Movesense supports 125 / 128 / 200 / 250 / 256 / 500 / 512 Hz. Native ADC rate not published by vendor.)
**HR rate: ~1 notification per heartbeat** (≈ 1.0–1.4 Hz at resting HR; tracks the wearer's HR — not a fixed clock)
**R-R rate: ~1 per heartbeat** (one interval per beat, bundled inside each HR notification)

- ECG via `/Meas/ECG/{rate}[/mV]` — batched samples per BLE notification
- HR + R-R via `/Meas/HR` — `average` BPM + `rrData` array per notification
- At 125 Hz, ECG produces ~7,500 samples per minute (rate × 60)

See [sensor_fibion_flash.md](sensor_fibion_flash.md) for protocol details.

### Beurer BC87 (Blood Pressure)

**Rate: Episodic** — one measurement per patient-initiated reading, not a continuous stream.

- Device advertises for ~30 seconds after each measurement
- App retrieves the latest reading via GATT indications
- Data stored in a separate `blood_pressure_events` table (test-scoped, not recording-scoped)

See [sensor_beurer_bc87.md](sensor_beurer_bc87.md) for protocol details.

## Data Collection

`SensorRecordingRepositoryImpl` collects samples from all connected sensors concurrently.

**Only one HR sensor (eSense Pulse XOR Fibion Flash) feeds the recording at a time** — the diagram lists both paths but exactly one HR branch is live per session, selected via `HeartRateDevicePreferences`. Respiration and Blood Pressure run independently alongside whichever HR sensor is active.

```
eSense Pulse ──(~4.5 Hz)──► heartRateSampleFlow ────────► HEART_RATE
                          ► rrIntervalSampleFlow ───────► ESENSE_RR_INTERVAL
eSense Respiration ──(5 Hz)──► sampleFlow ─────────────► RESPIRATION
Fibion Flash ──(125 Hz default)──► ecgSampleFlow ──────► FIBION_ECG
             ──(~1/heartbeat)──► heartRateSampleFlow ──► FIBION_HEART_RATE
                               ► rrIntervalSampleFlow ─► FIBION_RR_INTERVAL
Beurer BC87 ──(episodic)──► readingFlow ───────────────► blood_pressure_events (separate table)
```

Each sample is stored with:
- `timestampMs` — absolute timestamp (`System.currentTimeMillis()`)
- `elapsedMs` — time since recording start
- `sensorType` — one of the six `SensorType` enum values
- `value` — the sensor reading (Float)

Samples are batched (50 samples or 1-second flush interval) before database write.

## Synchronization Approach

Sensors run at their native rates. Alignment is handled via timestamp-based storage:

- **eSense Pulse (~4.5 Hz) + Respiration (5 Hz)** are close enough in cadence that samples remain co-located on the timeline at the typical sub-second analysis resolution; precise alignment is done by per-sample timestamp regardless
- **Fibion ECG** at 125 Hz is stored at full resolution for post-processing
- **R-R intervals** (both eSense and Fibion) arrive at variable rates tied to heartbeat timing
- **Blood pressure** is episodic and stored separately by test, not by recording

All continuous sensor samples share the same `sensor_samples` table with per-sample timestamps, so downstream analysis can align across rates as needed.

## References

- [eSense Pulse Technical Documentation](https://mindfield-store.com/en/eSense-Pulse/)
- [Bluetooth Heart Rate Service Specification](https://www.bluetooth.com/specifications/specs/heart-rate-service-1-0/)
- [eSense SDK Documentation](https://mindfield-store.com/en/eSense-SDK/)
