# eSense Respiration — Sensor Reference

## Device Overview

| Property | Value |
|----------|-------|
| Vendor | Mindfield Biosignals |
| Product | eSense Respiration |
| Connection | 3.5mm audio jack |
| Measured signal | Respiration Amplitude (RA) — chest expansion waveform |
| Recorded signal | **Raw RA only** (dimensionless, 5 Hz) |
| Derived signal | Breathing rate (br/min) — **on-screen estimate only, never recorded** |

The eSense Respiration is a chest-strap respiratory sensor that connects via the audio jack of the tablet. It requires no Bluetooth pairing. The device measures chest expansion and contraction as a raw amplitude value (RA).

> **What ends up in the data.** `SensorType.RESPIRATION` samples are the **raw RA waveform** — a
> dimensionless chest-expansion amplitude, *not* breaths per minute. The same holds for the exported
> JSON/CSV (`sensorType: "respiration"`) and for the upload DTOs sent to the VitalWork server. The
> app additionally shows a coarse live br/min estimate on the sensor screen, but it is display-only:
> nothing derived from it is persisted, because breathing rate is far better re-derived from the raw
> waveform offline (see [Breathing Rate Calculation](#breathing-rate-calculation)).

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

Measured on this hardware, not taken from the vendor sheet:

| Condition | RA Value |
|-----------|----------|
| Worn strap, resting | ~200 – 250 |
| Worn strap, deeper breaths | ~150 – 300 |
| Device range | ~0 – 450 |
| **Strap off the chest** | **< 1** (settles around 0.4) |
| Signal-lost threshold | 0.8 (`SIGNAL_LOST_THRESHOLD_RA`) |
| Microphone active (jack removed) | ~500 |
| Valid range max | 460 |

The gap between a worn strap (150–300) and a fallen one (< 1) is roughly two orders of magnitude,
which is why detecting a slipped strap needs no signal processing at all — just a threshold. 0.8
rather than the observed 0.4 keeps a 2× margin below while staying ~190× under real breathing.

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

**One warning banner, never two (not a disconnection)**
`RespirationWarning` carries the single respiration problem worth putting in front of the operator,
rendered by `RespirationWarningBanner` on both the sensor screen and the recording screen:

| State | Condition | Message |
|-------|-----------|---------|
| `SIGNAL_LOST` | RA below `SIGNAL_LOST_THRESHOLD_RA` (0.8) for more than 1.5 s | "Respiration signal lost — check chest strap placement." |
| `NO_BREATHING` | RA healthy, but the estimator reports `NoBreathing` for more than 20 s | "No breathing detected — check the respiration chest strap." |

`SIGNAL_LOST` **outranks** `NO_BREATHING`: it is the specific, actually-observed failure (the strap
comes off and RA collapses), and two banners both saying "check the strap" would be noise. The
warning clears as soon as the signal recovers. Warmup does not count toward the `NO_BREATHING`
debounce — a freshly started stream has no verdict yet, which is not a problem to report.

**Type toggle on connect**
The SDK is always reset by switching to type 1 and back to type 3 on each connect. This flushes internal SDK state and prevents stale data from a previous session from affecting the new connection.

**Streaming can be paused and resumed**
`stopStreaming()` pauses data collection, stops the watchdog and clears the breathing-rate window and every derived verdict while keeping the SDK sampling. `startStreaming()` resumes; the rate shows `-- br/min` again until 12 s of fresh samples have accumulated. The sensor remains in `Connected` state during a pause.

## Data Format

| Property | Value |
|----------|-------|
| Raw unit | Respiration Amplitude (RA) — dimensionless |
| Recorded unit | RA (the raw value; `sampleFlow` emits exactly what the SDK reports) |
| Derived unit | Breathing rate in br/min — UI only, not recorded |
| Output rate | 5 Hz (as set in this application) |

### Breathing Rate Calculation

**This is a live on-screen indicator, not a data product.** It exists so the operator can sanity-check
the strap. It is *not* written to the database, the export, or the upload — analysis should recompute
breathing rate from the recorded RA waveform.

The estimator lives in [BreathingRateEstimator.kt](../app/src/main/java/com/vitalwork/app/data/sensor/audio/BreathingRateEstimator.kt)
as pure functions over a sample list, so it is testable without the SDK. Over a 60-second window:

1. **Smooth** — centred moving average, 3 samples (0.6 s).
2. **Detrend** — subtract a centred 75-sample (15 s) moving baseline. Removes posture drift.
3. **Amplitude** — interquartile span of the **last 25 s** of the detrended signal. Below
   `MIN_AMPLITUDE_RA` (1.8) ⇒ `NoBreathing`.
4. **Breath onsets** — a rise above `+0.14 × amplitude` that follows a dip below `−0.14 × amplitude`
   (hysteresis), at least `REFRACTORY_SECONDS` (0.8) after the previous onset.
5. **Rate** — intervals between onsets in the last 15 s (reaching back for at least
   `MIN_RATE_ONSETS` = 4 onsets if the horizon is sparse), take the median, keep only intervals
   within 0.6–1.6× of it, then `60 / mean(kept)`.
6. **Sanity** — outside 4–60 br/min ⇒ `NoBreathing`.

Steps 3 and 5 look fussier than they need to be; both are answers to failures seen in a real
10-minute session, described under [Why these constants](#why-these-constants).

`estimate()` returns one of three outcomes, which the UI renders directly:

| Outcome | Meaning | Shown as |
|---------|---------|----------|
| `Warmup` | fewer than 12 s of samples (or, under 35 s, fewer than two breaths yet) | `-- br/min` |
| `NoBreathing` | waveform too flat, or no onset in the last 20 s | `no breathing` |
| `Rate(brPerMin, amplitudeRa)` | breaths resolved | e.g. `13.3 br/min` |

`NoBreathing` is deliberately **not** a numeric `0` — "the sensor is not picking up breathing" is an
instruction to the operator, not a measurement. The rate window is cleared on `connect()`,
`disconnect()` **and `stopStreaming()`**, so a pause never mixes pre-pause samples into the
post-resume estimate.

#### Why these constants

Tuned against a real 3 × 60 s recording (13.2 / 26.1 / 46.0 br/min by spectral analysis, the last a
deliberate hyperventilation), then **re-tuned against a scripted 10-minute session** covering calm
sitting, standing up, sitting down, deep breaths, leaning forward, and the strap taken off and put
back on — plus synthetic sweeps over the whole 4–60 br/min range.

**Amplitudes measured on real hardware** (interquartile span of the detrended signal), the numbers
every threshold below is derived from:

| Posture | Amplitude |
|---------|-----------|
| Standing, moving | 22 – 32 |
| Calm seated breathing | 17 – 22 |
| Deep breaths | 12 – 20 |
| **Leaning forward** | **3.5 – 4.8** |
| Motionless strap (smoothed sensor noise) | 0.4 – 0.7 |
| Posture change (baseline step) | ~100 RA — about 9× a calm breath |

| Constant | Value | Reason |
|----------|-------|--------|
| `SMOOTHING_SECONDS` | 0.6 | A moving average of length T nulls the frequency 1/T. **1.0 s annihilates 60 br/min entirely** and cuts 46 br/min to 28 % — an earlier version reported the recorded hyperventilation as ~23 br/min because of this. At 0.6 s those retain 50 % / 69 %, and since the detection threshold is a fraction of the *measured* amplitude, uniform attenuation is harmless. |
| `BASELINE_SECONDS` | 15 | Insensitive anywhere in 8–30 s; preserves breathing down to 4 br/min. Measured drift is 5–9 RA/min; the estimate survives 180 RA/min. |
| `HYSTERESIS_FRACTION` | 0.14 | Swept 0.05–0.25 against the p10–p90 amplitude at 0.10; rescaled to 0.14 when the amplitude moved to the interquartile span, which for a sine runs ~0.74× the p10–p90 span. Above the equivalent of 0.15 the detector starts dropping real breaths during fast breathing. |
| `REFRACTORY_SECONDS` | 0.8 | Caps the estimate at 75 br/min. **Not 1.5 s** — that caps at 40, and the study's own test data reached 46. |
| `RATE_HORIZON_SECONDS` | 15 | Response to a 12 → 20 br/min step: 10 s → 6 s, 15 s → 8 s, 20 s → 10 s, 30 s → 26 s. |
| `MIN_RATE_ONSETS` | 4 | A rate from a single interval has no outlier protection, so one missed breath halves it — the source of the sub-8 br/min readings a real session showed on standing up. Reaching back for four onsets fixed that (6.8 → 12.5 br/min at the moment of standing) and left genuinely slow breathing intact, still accurate down to 4 br/min. |
| trim to 0.6–1.6× median | — | At 5 Hz the intervals quantize to 0.2 s — a 15 % step at 46 br/min — so a plain median jumps by 7–9 %. Averaging the survivors brings that to 0.7 % and still survives a missed breath. |
| interquartile amplitude | p25–p75 | Not p10–p90: one deep breath among ten is ~9 % of samples, which p10–p90 barely trims but the quartiles ignore. With the wider span, a single deep breath set the band for everything after it. |
| `AMPLITUDE_WINDOW_SECONDS` | 25 | A posture change shifts the baseline by ~100 RA. Measured over the full 60 s buffer that transient kept the band inflated for a minute — the rate collapsed to 5.4 br/min after the strap was refitted. Over 25 s it clears about twice as fast. |
| `MIN_AMPLITUDE_RA` | 1.8 | Sits between breathing while leaning forward (3.5–4.8) and a motionless strap (0.4–0.7), ~2× from each. **An earlier value of 5.0 landed exactly on the leaning amplitude** and produced a false "no breathing" in a real session — the mistake this constant now documents. |
| `WARMUP_SECONDS` | 12 | At t = 12 s the rolling estimate already matched all three recordings (12.5 / 27.9 / 46.2). |

#### What this replaced, and why

The previous method counted how often the raw waveform crossed its own 30-second window mean.
Measured head-to-head:

| | Old | New |
|---|---|---|
| Response to a 12 → 20 br/min step | 24 s | **8 s** |
| Flat strap (no breathing at all) | **72 br/min** | `no breathing` |
| Successive readings on calm breathing | only {12, 14} | 13.3 … 14.0 |
| Baseline drift of 180 RA/min at 12 br/min | 8 | 12.3 |

Note that on a *clean* signal the old method was not inaccurate — this hardware's noise floor is low
(sd 0.8–1.5 RA against a breathing amplitude of 23–27). The gains are responsiveness, continuous
values, drift immunity, and above all not reporting a dead sensor as a plausible number.

### Signal quality in the export

The live warning disappears when the session ends, so the same judgement is applied again **at export
time** over the recorded RA and written next to the existing gaps. It is computed from samples that
are already stored — no database change, and it can be re-run over sessions recorded earlier.
See `detectRespirationIssues` in
[GapDetector.kt](../app/src/main/java/com/vitalwork/app/data/recording/GapDetector.kt).

| Reason | How it is found | Minimum duration | Precision |
|--------|-----------------|------------------|-----------|
| `SIGNAL_LOST` | contiguous run of samples below `SIGNAL_LOST_THRESHOLD_RA` | 2 s | exact, to the sample |
| `NO_BREATHING` | sliding 10 s window whose detrended amplitude is under `MIN_AMPLITUDE_RA` | 20 s | ±1 s |

A run is broken when consecutive samples are more than 1 s apart: the sensor dropped out there, and
that stretch is already reported as a gap — it must not be counted as measured lost signal. **No
respiration samples at all yields nothing**, because that means the sensor was never connected, not
that the signal was lost.

> **Limitation.** `NO_BREATHING` cannot distinguish a genuine breath hold from a strap that is on the
> chest but not coupled — the waveform looks the same. The name is deliberately neutral; the analyst
> judges from context. `SIGNAL_LOST` is unambiguous.

JSON (`ScenarioExport.respirationIssues`, `null` when there is nothing to report):

```json
"respirationIssues": {
  "signalLostCount": 1,  "signalLostTotalMs": 15200,
  "noBreathingCount": 0, "noBreathingTotalMs": 0,
  "events": [
    { "reason": "SIGNAL_LOST", "startElapsedMs": 720000,
      "endElapsedMs": 735200, "durationMs": 15200 }
  ]
}
```

CSV header (emitted only when something was found; the sample rows are unchanged):

```
# respiration_signal_lost,1
# respiration_signal_lost_total_ms,15200
# respiration_signal_lost_1,720000,735200
```

Times are `elapsedMs` from the scenario start — the same unit `GapExport` uses, so the file carries
one convention throughout. Absolute time is one subtraction away (`timestampMs - elapsedMs` from any
sample row). Nothing is added to the server upload; the existing gaps are not uploaded either.

See [sensor_sampling_rates.md](sensor_sampling_rates.md) for multi-sensor synchronization context.

## App Implementation

### Key Files

| File | Role |
|------|------|
| [data/sensor/audio/MindfieldRespiration.kt](../app/src/main/java/com/vitalwork/app/data/sensor/audio/MindfieldRespiration.kt) | Singleton SDK wrapper (connect, stream, watchdog, rate calculation) |
| [.../respiration/EsenseRespirationViewModel.kt](../app/src/main/java/com/vitalwork/app/presentation/screens/sensors/mindfield/respiration/EsenseRespirationViewModel.kt) | UI state management |
| [.../respiration/EsenseRespirationScreen.kt](../app/src/main/java/com/vitalwork/app/presentation/screens/sensors/mindfield/respiration/EsenseRespirationScreen.kt) | Compose UI |

`MindfieldRespiration` is a Kotlin `object` (singleton). Only one instance exists per process lifetime.

### Data Flow

```
eSense Respiration (audio jack)
  └─► HardwareController (eSense SDK)
        └─► sdkObserver.valueHasChanged(SensorData)
              └─► _dataRate (StateFlow<Float>) ──────────────────► UI (raw RA)
              └─► _detailedStats (StateFlow<String>) ──────────────► UI only (br/min estimate)
              └─► sampleFlow (SharedFlow<Float>) ─────────────────► ScenarioRecordingRepository (raw RA)
              └─► warning (StateFlow<RespirationWarning>) ───────► UI banner (one at a time)
              └─► breathingEstimate (StateFlow) ─────────────────► UI only (Warmup/NoBreathing/Rate)
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
  │  ├─ RA < 0.8 sustained > 1500ms → RespirationWarning.SIGNAL_LOST (no disconnect)
  │  └─ NoBreathing sustained > 20s → RespirationWarning.NO_BREATHING (no disconnect)
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

**"Respiration signal lost" during streaming**
- RA is consistently below 0.8 — the strap has lost contact with the chest. Reposition it snugly.
- The warning clears automatically once the signal recovers, and the stretch is recorded in the
  export as a `SIGNAL_LOST` event.

**"No breathing detected" during streaming**
- RA is at a normal level but its excursion is too small to resolve breaths for 20 s or more: the
  strap is on but not coupled to chest movement — or the participant is genuinely holding their
  breath. The app cannot tell these apart; check the strap first.
- The `MIN_AMPLITUDE_RA` threshold behind this was re-tuned against a real session after an earlier
  value flagged normal breathing-while-leaning as "no breathing". The event log prints the measured
  amplitude at every verdict change (`Breathing detected: 13.3 br/min (amplitude 18.4 RA)`) — if a
  false warning ever appears again, that number says how far off the threshold is.

**"Signal abnormality detected (device may be disconnected)"**
- RA has been above 460 for more than 1 second. This typically means the jack was pulled and the tablet's microphone became the audio source.
- Reconnect the sensor.

**SDK must be on Main thread**
- If `HardwareController.getInstance()` is called from a background thread, a crash ("Flash" crash) occurs. `MindfieldRespiration` is already guarded against this via `Dispatchers.Main`, but any future code interacting with the controller must respect this constraint.

**Disconnect reason persists**
- After a `forceDisconnect`, the reason string is stored in `lastDisconnectReason`. The UI can display this as a dialog. Call `clearDisconnectReason()` after the user dismisses the dialog to reset the state.
