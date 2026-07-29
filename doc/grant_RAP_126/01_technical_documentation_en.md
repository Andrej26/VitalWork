# Technical Documentation — VitalWork Sensor Control Module

**Project:** RAP_126 — Development of a prototype sensor control module for monitoring operator
physiological state during simulated work scenarios
**Document version:** 1.0
**Date:** 2026-07-22
**Language:** English translation (the authoritative formal output is the Slovak version); source-code
documentation is in English.

---

## Contents

1. [Purpose of the document](#1-purpose-of-the-document)
2. [System overview](#2-system-overview)
3. [Hardware components](#3-hardware-components)
4. [Software architecture](#4-software-architecture)
5. [Communication protocols](#5-communication-protocols)
6. [Data model](#6-data-model)
7. [Data flow and processing](#7-data-flow-and-processing)
8. [Security and permissions](#8-security-and-permissions)
9. [External tool dependencies](#9-external-tool-dependencies)
10. [Build and deployment procedure](#10-build-and-deployment-procedure)
11. [Export data schema (JSON/CSV)](#11-export-data-schema-jsoncsv)

---

## 1. Purpose of the document

This document is a formal technical output of grant **Activity 2** (modelling / simulation) of project
RAP_126. It describes the architecture, components, communication protocols and data model of the
**VitalWork** system — a sensor control module for monitoring an operator's physiological state during
five simulated biofeedback work scenarios.

The system is a prototype intended for the **technical verification of physiological-parameter
measurement** under controlled conditions. A second device paired over local Wi-Fi lets the operator
watch the monitored device's live screen during a scenario, purely as an observation aid — it plays no
part in the measurement itself.

---

## 2. System overview

### 2.1 Architecture diagram

*See diagram 01 — System architecture:* `diagrams/png/01_system_architecture_en.png`

The VitalWork system consists of four physical components interconnected over local Wi-Fi and
Bluetooth:

| Component | Role |
|-----------|------|
| **Monitored device** (Android tablet/phone, client role) | Central node: sensor data collection, session management, local export, and upload to the server |
| **eSense Pulse** (Mindfield Biosignals) | Heart-rate and R-R interval sensor over BLE |
| **eSense Respiration** (Mindfield Biosignals) | Respiration-rate sensor over the monitored device's audio jack |
| **Galaxy Watch 8** (Samsung) | EDA, heart-rate and IBI sensor; streams data over the Wearable Data Layer to the monitored device |
| **Viewer device** (Android tablet/phone, server role) | The operator's second device; watches the monitored device's live screen over a direct Wi-Fi link |

The central VitalWork server (off-device) receives uploaded sessions over HTTP.

### 2.2 Measurement objective

The system records the operator's **physiological state** while they work through five biofeedback
scenarios (A–E): Reference State, Cognitive Load, Distracting Environment, Long-Term Fatigue, and
Reaction Tasks. All timestamps come from a single NTP-corrected clock on the monitored device
(`TimeProvider`), so every sensor's samples share the same UTC timeline with no additional
device-to-device clock synchronisation step.

The following physiological signals are recorded in parallel:

- Heart rate (BPM) and R-R intervals (ms) — eSense Pulse
- Respiration amplitude — eSense Respiration
- Heart rate (BPM), IBI (ms) and electrodermal activity EDA (µS) — Galaxy Watch 8

These signals make it possible to correlate the operator's physiological state with the demands of
each scenario — the module's primary analytical output.

---

## 3. Hardware components

### 3.1 eSense Pulse (Mindfield Biosignals)

| Property | Value |
|----------|-------|
| Type | Chest strap — PPG sensor |
| Interface | Bluetooth Low Energy (BLE), Heart Rate Profile (Bluetooth SIG 0x180D) |
| Signals | Heart rate (BPM), R-R intervals (ms) |
| Sampling rate | ≈ 4.5 Hz (225 ms period; nominal 5 Hz) |
| Accuracy | ±2 BPM (range 30–240 BPM) |
| Internal PPG | 500 Hz, processed on-device before BLE transmission |

**Device identification:** the `eSense` name prefix or Manufacturer ID `0xFF0C` in the BLE
advertising data. The device uses a random BLE address type.

**Protocol:** GATT characteristic `0x2A37` (Heart Rate Measurement), notifications enabled by writing
to CCCD `0x2902`. Each notification carries a BPM value (UINT8 or UINT16) and optionally R-R intervals
(UINT16 LE, resolution 1/1024 s, converted to ms). Zero-valued R-R values are discarded.

### 3.2 eSense Respiration (Mindfield Biosignals)

| Property | Value |
|----------|-------|
| Type | Chest strap — chest-expansion sensor |
| Interface | Audio jack (3.5 mm) |
| Signal | Respiration amplitude (RA) — raw waveform of chest expansion and contraction |
| Sampling rate | 5 Hz (configurable via the SDK) |
| Derived parameter (UI only) | Breathing rate (breaths/min) from breath-onset detection (smoothing, baseline detrending, amplitude-scaled hysteresis) over a 60-second window — computed for the live display, not persisted as a sample |
| Live signal-quality warnings | **Signal lost** (RA below 0.8 — strap slipped off the chest) and **No breathing detected** (healthy RA level but no breathing waveform) — shown as on-screen banners during setup and recording |

**Nyquist rationale:** normal breathing rate is 0.2–0.33 Hz; at 5 Hz it is oversampled more than
15-fold — an ample margin.

### 3.3 Samsung Galaxy Watch 8

| Property | Value |
|----------|-------|
| Type | Smartwatch (Wear OS 6 / One UI Watch) |
| SDK | Samsung Health Sensor SDK (local AAR, `:wear` module) |
| Interface | Wearable Data Layer (Bluetooth; no internet) |
| Signals | HR (BPM), IBI (ms), EDA (µS) |
| Sampling rate | 1 Hz (native) |

> **Key design rationale:** the Galaxy Watch 8 has no standard BLE Heart Rate Profile and no accessible
> BLE GATT for EDA. The Samsung Health Sensor SDK runs exclusively **on the watch**. A two-app
> architecture is therefore required: a companion app (the `:wear` module) reads the watch sensors and
> streams the readings to the monitored device over the Wearable Data Layer.

**Store-and-forward:** the watch persists every HR/IBI/EDA reading to a local file
(`WatchSampleStore`), so a screen-off (Doze) session loses no data. At session end the monitored device
sends a `FLUSH` command; the watch returns the stored rows as `DataClient` DataItems (reliable
transfer, buffered across short outages). Once received, the monitored device sends `FLUSH_ACK` and
the watch truncates the store up to the acknowledged timestamp.

**Continuous background delivery** requires a foreground `health` service on the watch,
`BODY_SENSORS_BACKGROUND`, and a 1 Hz `HealthTracker.flush()` loop. **The link must run over direct
Bluetooth** — if the phone's Bluetooth is off, the Data Layer silently falls back to a cloud relay that
cannot deliver to a dozing phone; the watch UI shows a Bluetooth-off warning to prevent this failure
mode.

---

## 4. Software architecture

### 4.1 Module structure

The project consists of two Gradle modules:

| Module | Description | Min SDK |
|--------|-------------|---------|
| `:app` | Monitored / viewer device — Android tablet or phone | API 24 (Android 7.0) |
| `:wear` | Companion app — Galaxy Watch (Wear OS) | API 28 (Android 9.0) |

**Technology stack (`:app`):**

| Layer | Technology |
|-------|------------|
| Language | Kotlin 2.3.0 |
| UI | Jetpack Compose (BOM 2026.01.00), Material Design 3 |
| Dependency injection | Hilt / Dagger |
| Local database | Room 2.7.1 |
| HTTP client (upload) | Ktor 3.3.0 (CIO) |
| NTP synchronisation | Kronos (Lyft) |
| Peer link | Java-WebSocket + mDNS (Wi-Fi, port 9090) |
| Screen mirroring | stream-webrtc-android (WebRTC, peer-to-peer) |
| Watch link | Play Services Wearable (Data Layer) |

### 4.2 Layered architecture

*See diagram 01 — System architecture*

```
MainActivity
└── VitalWorkApplication      (Hilt application class)
    └── VitalWorkTheme        (Material 3 theme, forced light)
        └── AppNavigation     (NavHost — Compose navigation)
            └── Composable screens
```

The presentation layer (ViewModels + Compose) communicates exclusively through the data layer
(repositories and singleton receivers). No UI class accesses the database or network clients directly.

**Key singleton components (Hilt):**

| Component | Responsibility |
|-----------|----------------|
| `PeerLinkManager` | Device-to-device WebSocket link (server/client role, mDNS discovery) |
| `WatchSensorReceiver` | Receiver of live watch readings + flush DataItems; link-status state machine |
| `ScenarioRecordingRepositoryImpl` | Recording control (start/stop) + persisting samples to the DB |
| `TimeProvider` | NTP-corrected wall-clock (Kronos) |
| `KeepAliveCoordinator` | Reason-set (SESSION / LINK / SCREEN_SHARE) driving the foreground-service lifecycle |

### 4.3 Foreground service

`BackgroundConnectionService` is a single app-wide foreground service that keeps the process, the peer
link and screen mirroring alive while the screen is off, for as long as any keep-alive reason
(`SESSION`, `LINK`, `SCREEN_SHARE`) is active. It starts the moment the reason set becomes non-empty
and stops once every reason has cleared.

### 4.4 Navigation routes

| Route | Screen | Description |
|-------|--------|--------------|
| `mode` | ModeSelectionScreen | First-launch Server/Client picker |
| `tutorial` | TutorialScreen | First-launch onboarding |
| `home` | HomeScreen | Mode-aware dashboard (Server vs Client layout) |
| `link/{role}` | PeerLinkScreen | Device-to-device link: pairing, diagnostics, screen-mirror controls |
| `settings` | SettingsScreen | Device prefix (A/B/C/D) and Server/Client mode switch |
| `sensors` | SensorsScreen | List of available sensors |
| `sensors/{sensorId}` | SensorDetailScreen | Router to the vendor-specific sensor screen |
| `participants/new` | ParticipantEntryScreen | Anonymised participant entry (creates participant + session) |
| `sessions` | SessionsScreen | List of completed sessions |
| `sessions/setup/{sessionId}` | SessionControlScreen (setup mode) | One-time sensor-connection gate after participant entry |
| `sessions/scenario-select/{sessionId}` | ScenarioSelectionScreen | Scenario hub: pick A–E or end the session |
| `sessions/active/{sessionId}` | SessionControlScreen | Records the picked scenario (auto-start + countdown) |
| `sessions/review/{sessionId}` | SessionDetailScreen | Session review, local export, upload |

---

## 5. Communication protocols

### 5.1 Device-to-device link (viewer ↔ monitored device)

*See diagram 01 — System architecture:* `diagrams/png/01_system_architecture_en.png`

**Roles:**

| Device | Role |
|--------|------|
| Viewer device | WebSocket server (Java-WebSocket, port 9090), advertised via mDNS |
| Monitored device | WebSocket client — discovers the viewer and connects |

**Discovery and pairing (client-initiated):**

1. The viewer device (server role) advertises itself over mDNS.
2. The monitored device (client role) discovers the service and opens a WebSocket connection to
   `ws://{viewerIp}:9090`.
3. The same WebSocket carries pairing, a free-text diagnostics log, and **WebRTC signalling**
   (offer/answer/ICE candidates) for screen mirroring.
4. Once signalling completes, the monitored device streams its live screen to the viewer
   **peer-to-peer over WebRTC/UDP** — the video itself never passes through the WebSocket.

The link runs in **one role at a time** per device (`PeerLinkManager.activeRole`) and is kept alive
across sleep by `BackgroundConnectionService`. Cleartext traffic is permitted only for the LAN
(`network_security_config.xml`), and screen capture requires the
`FOREGROUND_SERVICE_MEDIA_PROJECTION` and `POST_NOTIFICATIONS` permissions.

**Cost model:** screen mirroring is free to run — no media server, no cloud relay, only Google's free
public STUN server for LAN address discovery. The only real costs are battery/heat on the sharing
device and local Wi-Fi bandwidth. Off-LAN use would require a TURN relay, which this system does not
use.

### 5.2 BLE (eSense Pulse)

The monitored device scans for BLE devices (unfiltered, low-latency), identifies the eSense Pulse by
name or Manufacturer ID, connects over GATT and enables notifications on characteristic `0x2A37`. The
parser (`BleParsers.kt`) handles both UINT8 and UINT16 formats and extracts the R-R intervals.

### 5.3 Audio (eSense Respiration)

The eSense SDK reads the analogue signal from the audio jack (3.5 mm). The app configures a 5 Hz
sampling rate. The breathing rate shown in the UI is derived by a breath-onset estimator over a
60-second sliding window: the RA waveform is smoothed, detrended against a moving baseline, and
breath onsets are detected with an amplitude-scaled hysteresis; the rate is the trimmed median of
the inter-breath intervals. The estimator was tuned on real chest-strap recordings and also reports
two quality verdicts used for the live warnings: *signal lost* (RA < 0.8 — strap off the chest) and
*no breathing* (RA at a normal level but with no breathing modulation).

### 5.4 Wearable Data Layer (Galaxy Watch 8)

*See diagram 05 — Galaxy Watch store-and-forward:* `diagrams/png/05_watch_store_forward_en.png`

**Live data:** `WatchSensorService` (watch) → `MessageClient` → `WatchListenerService` (monitored
device) → `WatchSensorReceiver`.

**Flush (session end):**
- `WatchCommandSender` sends `FLUSH`
- `WatchCommandListenerService` (watch) drains any SDK-buffered samples into the store, then
  `WatchFlushWriter` packs the stored rows into chunked DataItems
- `WatchListenerService.onDataChanged` (monitored device) ingests them and sends `FLUSH_COMPLETE`
- `WatchSessionDrainer` attributes each row to a scenario by timestamp window and de-duplicates against
  samples already written live
- The monitored device sends `FLUSH_ACK:<maxTimestamp>` and the watch truncates the store up to that
  timestamp, keeping any un-acked tail for the next flush

**Link status:** a low-rate `HEARTBEAT` every 30 s keeps the watch-link status shown on the monitored
device in a DOZING state (not a false "Disconnected") when the live stream pauses during Doze — the
data is safe in the store and the flush retrieves it. Auto-wake of the watch on flush is best-effort;
a manual tap on the watch is the fallback, but no data is ever lost because the store is durable until
acknowledged.

### 5.5 NTP synchronisation

The monitored device uses Kronos (`TimeProvider.nowMs()`) for all persisted and exported timestamps.
Every sensor's samples — eSense, Galaxy Watch, and any timestamp recorded during a scenario — share
this single clock, so cross-stream alignment needs no additional device-to-device synchronisation step.

---

## 6. Data model

*See diagram 03 — Data model (ER schema):* `diagrams/png/03_data_model_en.png`

Room database (schema v6) with 4 entities; cascade delete on all foreign keys. The schema uses
`fallbackToDestructiveMigration`, so a version bump wipes old local rows without a hand-written
migration — acceptable because sessions are already exported/uploaded by the time the schema changes.

| Entity | Table | Purpose |
|--------|-------|---------|
| `ParticipantEntity` | `participants` | Anonymised participant (`participantCode`) |
| `SessionEntity` | `sessions` | Session (FK → participants; status: ACTIVE, COMPLETED, UPLOADED) |
| `ScenarioEntity` | `scenarios` | One biofeedback scenario run within a session (FK → sessions; code, `startedAt`/`endedAt`) |
| `SensorSampleEntity` | `sensor_samples` | Time series of samples (FK → scenarios; `timestampMs`, `elapsedMs`, `sensorType`, `value`) |

### 6.1 Enumerations

| Enum | Values |
|------|--------|
| `SessionStatus` | `ACTIVE`, `COMPLETED`, `UPLOADED` |
| `ScenarioCode` | `REFERENCE_STATE` (A), `COGNITIVE_LOAD` (B), `DISTRACTING_ENVIRONMENT` (C), `LONG_TERM_FATIGUE` (D), `REACTION_TASKS` (E) |
| `SensorType` | `ESENSE_HEART_RATE`, `ESENSE_RR_INTERVAL`, `RESPIRATION`, `WATCH_HR`, `WATCH_IBI`, `WATCH_EDA` |

`ScenarioCode` carries a short official code (A–E) and a display label as enum properties — the
constant *name* (e.g. `REFERENCE_STATE`) is what is stored in the database and sent to the server, so
descriptive labels can change without breaking existing rows.

### 6.2 Identifier encoding

Each device carries a prefix (A/B/C/D) configured in the settings (`SettingsRepository`,
SharedPreferences, default `A`). The prefix tags both participant codes (`A-001-260722-143022`) and
session codes (`VW-A-yyMMdd-HHmmss`), so multiple devices testing in parallel never mint colliding
codes. Operators must agree beforehand which device owns which letter; the counter is scoped per
prefix (`ParticipantDao.getParticipantCountByPrefix`), so collisions are only avoided across devices
with distinct letters.

### 6.3 Sample-count caching

`SessionEntity` caches per-type sample counts (`hrSampleCount`, `respirationSampleCount`,
`rrIntervalSampleCount`, `edaSampleCount`, `watchHrSampleCount`, `watchIbiSampleCount`) plus the
scenario count, so the overview screen can render without re-querying `sensor_samples`. These counts
are computed once, when the session ends, from the samples recorded in the database.

### 6.4 Schema history

The schema evolved through the biofeedback pivot: v2 added `WATCH_IBI`; v3 split per-device sensor
types; v4 added the watch sample counters; v5 dropped the reaction-time/VR fields
(`scenarioCategory`, `eventTimestampMs`, `reactionTimestampMs`) and `sessions.notes`; v6 renamed the
nine industrial scenario codes to the five biofeedback scenarios described in §6.1. The former VR
phase — a Meta Quest link, an on-device HTTP server, and a UDP discovery beacon — was removed entirely
in this pivot and is not part of the current system.

---

## 7. Data flow and processing

*See diagram 04 — Data flow pipeline:* `diagrams/png/04_data_flow_pipeline_en.png`

### 7.1 Scenario recording

```
Operator picks scenario A–E → ScenarioRecordingRepositoryImpl.startRecording()
    ├─ eSense Pulse (~4.5 Hz)    → ESENSE_HEART_RATE + ESENSE_RR_INTERVAL
    ├─ eSense Respiration (5 Hz) → RESPIRATION
    └─ Galaxy Watch (1 Hz)       → WATCH_HR + WATCH_IBI + WATCH_EDA (live stream)

Samples are batched (50 samples or a 1 s flush interval, whichever comes first) before being written
to the Room DB. Watch data skips this buffer — it arrives in one batch at session end (§7.2).

Operator returns to the scenario hub → ScenarioRecordingRepositoryImpl.stopRecording()
    → writes endedAt into ScenarioEntity
    → ends sample collection for this scenario
```

### 7.2 Session end and watch flush

*See diagram 05 — Galaxy Watch store-and-forward:* `diagrams/png/05_watch_store_forward_en.png`

```
Operator ends the session
    → WatchCommandSender.sendFlush()
    → The watch drains its SDK buffer, then ships the stored rows (chunked DataClient DataItems)
    → WatchListenerService.onDataChanged() ingests the rows; the watch sends FLUSH_COMPLETE
    → WatchSessionDrainer attributes samples to scenarios by timestamp window, de-duplicating
      against live-streamed samples
    → ScenarioRecordingRepositoryImpl writes the matched rows to the DB
    → FLUSH_ACK:<maxTimestamp> → the watch truncates the store up to that timestamp
    → SessionEntity.status = COMPLETED
```

### 7.3 Export and upload

```
Session end (automatic)
    → SessionHttpUploader (Ktor client) → POST /api/sessions/upload to the VitalWork server
        (a single idempotent POST, keyed on sessionCode; safe to retry)
    → On success: SessionEntity.status = UPLOADED

Optionally, at the operator's request (Session Review screen)
    → SessionExportService → JSON + CSV written to the Documents folder
```

Upload timeout: `REQUEST_TIMEOUT_MS = 600 000 ms` — the total body-transfer time; long sessions with
thousands of samples can be slower over a weaker connection. A failed upload can be retried manually
from the Session Review screen.

### 7.4 Analytical module

**Implemented in the prototype:**
- Gap detection in sensor data (`GapDetector`) — per sensor type, per scenario
- Watch-flush completeness report (`WatchReconciliationReport` — claimed/received/in-window/DB rows)

**Proposed (not implemented in the current prototype):**
- Automatic comparison of measured values against reference physiological thresholds
- Aggregation and statistical analysis across multiple sessions

---

## 8. Security and permissions

### 8.1 Android permissions (`:app`)

| Permission | Purpose |
|------------|---------|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Upload to the VitalWork server, peer-link connectivity |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS discovery of the peer device |
| `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` | eSense Respiration (audio jack) |
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` | BLE for eSense Pulse (Android 12+) |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | BLE for older Android versions (≤ API 30) |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Required by Android for BLE scanning |
| `WRITE_EXTERNAL_STORAGE` (≤ API 28) | Legacy local export |
| `FOREGROUND_SERVICE` + variants (microphone, connected device, data sync, media projection) | Keeping the session, peer link, and screen mirroring alive while the screen is off |
| `WAKE_LOCK` | Wi-Fi lock keeping network access alive under Doze |
| `POST_NOTIFICATIONS` | Foreground-service notification (API 33+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Optional exemption so OEM power managers don't kill long sessions |
| `WRITE_SETTINGS` | Optional: dims the sharer's backlight during screen mirroring while capture continues |

### 8.2 Anonymisation

The system stores no personal identifiers. Participants are recorded solely via an automatically
generated code (`A-001-…`) with no name, date of birth, or other personal data. The participant-code
field in the UI is read-only to keep the scheme typo-proof.

---

## 9. External tool dependencies

| Tool / Library | Version | Source |
|----------------|---------|--------|
| Kotlin | 2.3.0 | Maven Central |
| Jetpack Compose BOM | 2026.01.00 | Google Maven |
| Hilt / Dagger | (via BOM) | Google Maven |
| Room | 2.7.1 | Google Maven |
| Ktor (CIO client) | 3.3.0 | Maven Central |
| Kronos (Lyft NTP) | 0.0.1-alpha11 | Maven Central |
| Java-WebSocket | current | Maven Central |
| stream-webrtc-android | current | Maven Central |
| Play Services Wearable | current | Google Maven |
| Samsung Health Sensor SDK | current | Local AAR (`wear/libs/`) |
| eSense SDK | 2.x | Local JAR (`app/libs/eSense_sdk_2_lib.jar`) |

All versions are centrally managed in `gradle/libs.versions.toml`.

---

## 10. Build and deployment procedure

### 10.1 Requirements

- **JDK 17+** — the bundled JBR from Android Studio is recommended
- Android Studio (current) or the command line with Gradle 9.3.0
- A connected Android device (API 24+ for `:app`, API 28+ for `:wear`)

### 10.2 Commands

```bash
# Debug build
./gradlew assembleDebug

# Install on a connected device
./gradlew :app:installDebug        # monitored / viewer device (tablet or phone)
./gradlew :wear:installDebug       # Galaxy Watch companion

# Release AAB (requires the keystore in local.properties)
./gradlew bundleRelease

# Tests
./gradlew test                     # unit tests (no device)
./gradlew connectedAndroidTest      # instrumented tests (device / emulator)

# Clean
./gradlew clean
```

### 10.3 local.properties configuration

For signing a release build:

```properties
KEYSTORE_PATH=...
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

The VitalWork server base URL used by `SessionHttpUploader` is also configured in
`local.properties`.

### 10.4 Companion app installation (Galaxy Watch)

The procedure for installing the `:wear` module on the Galaxy Watch 8 (over ADB via Wi-Fi or directly)
is described in detail in [`doc/install_watch_app.md`](../install_watch_app.md).

---

## 11. Export data schema (JSON/CSV)

*See diagram 06 — Export schema:* `diagrams/png/06_export_schema_en.png`

At session end the session is automatically uploaded to the VitalWork server as a single idempotent
JSON POST (`/api/sessions/upload`, keyed on `sessionCode`). Saving JSON/CSV files locally on the
device is a separate, optional step the operator can trigger — the exported JSON is the authoritative,
fully nested bundle; the per-scenario CSV files are a flat, spreadsheet-friendly view of the same
samples.

### 11.1 File layout

| File | Scope | Example name |
|------|-------|--------------|
| `{sessionCode}_export.json` | One file per **session** (participant + session + all scenarios + all samples) | `VW-A-260722-171532_export.json` |
| `{sessionCode}_NN_{SCENARIO}.csv` | One file per **scenario** (`NN` = 01, 02, …, ordinal) | `VW-A-260722-171532_02_COGNITIVE_LOAD.csv` |

### 11.2 JSON structure (schema version 2.2.0)

The root object (`SessionExport`) carries `version`, `exportedAt` and three nested blocks —
`participant`, `session` and `scenarios[]`.

| Path | Type | Notes |
|------|------|-------|
| `version` | string | Export schema version, currently `"2.2.0"` (2.2.0 added `scenarios[].respirationIssues`; otherwise identical to 2.1.0) |
| `exportedAt` | string | ISO-8601 UTC timestamp of the export |
| `participant.participantCode` | string | Anonymised code (e.g. `A-007-260722-063337`) |
| `participant.age` | int? | Nullable |
| `participant.gender` | string? | Nullable |
| `session.sessionCode` | string | `VW-{prefix}-yyMMdd-HHmmss` |
| `session.startedAt` | string | ISO-8601 UTC |
| `session.endedAt` | string? | ISO-8601 UTC, nullable |
| `session.status` | string | `COMPLETED` or `UPLOADED` |
| `session.statistics.*` | int | Seven per-type counts: `scenarioCount`, `hrSampleCount`, `respirationSampleCount`, `rrIntervalSampleCount`, `edaSampleCount`, `watchHrSampleCount`, `watchIbiSampleCount` |
| `scenarios[]` | array | One entry per biofeedback scenario run (see below) |

**Each element of `scenarios[]`:**

| Path | Type | Notes |
|------|------|-------|
| `scenarioCode` | string | One of `REFERENCE_STATE`, `COGNITIVE_LOAD`, `DISTRACTING_ENVIRONMENT`, `LONG_TERM_FATIGUE`, `REACTION_TASKS` |
| `startedAt` | string | ISO-8601 UTC |
| `endedAt` | string? | ISO-8601 UTC, nullable |
| `gaps` | object? | Per-sensor gap report (`heartRate`, `rrInterval`, `respiration`); each = `gapCount`, `gapTotalMs`, `gaps[]{startElapsedMs, endElapsedMs, gapMs}` |
| `respirationIssues` | object? | Stretches where respiration samples kept arriving but are unusable (a slipped strap produces no gap): `signalLostCount`, `signalLostTotalMs`, `noBreathingCount`, `noBreathingTotalMs`, `events[]{reason, startElapsedMs, endElapsedMs, durationMs}`; `reason` = `SIGNAL_LOST` or `NO_BREATHING`. `null` when there is nothing to report — including when the sensor was simply not connected. Derived at export from the recorded RA waveform. |
| `samples[]` | array | Time series (see below) |

**Each element of `samples[]`:**

| Field | Type | Notes |
|-------|------|-------|
| `timestampMs` | long | NTP-corrected UTC epoch ms |
| `elapsedMs` | long | ms since scenario start |
| `sensorType` | string | See §11.4 |
| `value` | float | Reading in the unit for that `sensorType` |

**Example (abbreviated):**

```json
{
  "version": "2.2.0",
  "exportedAt": "2026-07-22T08:20:00Z",
  "participant": {
    "participantCode": "A-007-260722-063337",
    "age": 32,
    "gender": "F"
  },
  "session": {
    "sessionCode": "VW-A-260722-063346",
    "startedAt": "2026-07-22T06:33:46Z",
    "endedAt": "2026-07-22T08:18:31Z",
    "status": "UPLOADED",
    "statistics": {
      "scenarioCount": 5,
      "hrSampleCount": 25834,
      "respirationSampleCount": 27000,
      "rrIntervalSampleCount": 7475,
      "edaSampleCount": 5364,
      "watchHrSampleCount": 5117,
      "watchIbiSampleCount": 1214
    }
  },
  "scenarios": [
    {
      "scenarioCode": "COGNITIVE_LOAD",
      "startedAt": "2026-07-22T06:50:38Z",
      "endedAt": "2026-07-22T07:10:38Z",
      "gaps": null,
      "respirationIssues": null,
      "samples": [
        { "timestampMs": 1782283839046, "elapsedMs": 279, "sensorType": "watch_hr", "value": 82.0 }
      ]
    }
  ]
}
```

### 11.3 CSV structure (one file per scenario)

Each CSV begins with a **commented metadata header** (lines prefixed with `#`) describing the
scenario, followed by a single data-header row and the sample rows. A comment line is emitted only
when the underlying value exists (e.g. a `*_gaps` line is omitted for a scenario with no detected
gaps).

**Metadata header (`# key,value`):** `session_code`, `scenario_code`, `start_time`, `end_time`,
`esense_hr_samples`, `respiration_samples` (always emitted), plus `rr_interval_samples`,
`watch_hr_samples`, `watch_ibi_samples`, `watch_eda_samples` (only if > 0), and the per-sensor gap
counters (`*_gaps`, `*_gap_total_ms`, only if any). Respiration signal-quality issues, if detected,
add `respiration_signal_lost` / `respiration_no_breathing` count lines with a `*_total_ms` line and
one positional line per event (`# respiration_signal_lost_1,<startElapsedMs>,<endElapsedMs>`).

**Data columns:**

| Column | Type | Notes |
|--------|------|-------|
| `timestamp_ms` | long | NTP-UTC epoch ms |
| `elapsed_ms` | long | ms since scenario start |
| `sensor_type` | string | See §11.4 |
| `value` | float | Reading |

```
# session_code,VW-A-260722-063346
# scenario_code,COGNITIVE_LOAD
timestamp_ms,elapsed_ms,sensor_type,value
1782283839046,279,watch_hr,82.0
1782283839237,470,watch_eda,21.434
1782283840047,1280,esense_heart_rate,78.0
```

### 11.4 sensorType values and units

The JSON `sensorType` field and the CSV `sensor_type` column use the same lowercase vocabulary (these
are the wire values, distinct from the internal `SensorType` enum names):

| Wire value | Unit | Source |
|------------|------|--------|
| `esense_heart_rate` | BPM | eSense Pulse |
| `rr_interval` | ms | eSense Pulse |
| `respiration` | amplitude (raw waveform) | eSense Respiration |
| `watch_hr` | BPM | Galaxy Watch 8 |
| `watch_ibi` | ms | Galaxy Watch 8 |
| `watch_eda` | µS | Galaxy Watch 8 |

> **Note:** the gap statistics are **derived at export** — they are not stored as raw columns in the
> database. The upload to the server uses an equivalent JSON body carrying the same values; the
> endpoint is idempotent on `sessionCode`, so re-upload replaces the session's scenarios and samples.

---

*This document is part of the formal output of project RAP_126, Activity 2 (modelling / simulation).*
*All diagrams are available in the `doc/grant_RAP_126/diagrams/` directory as a `.png` render and an interactive `.html`.*
