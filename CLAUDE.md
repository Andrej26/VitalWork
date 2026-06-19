# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VitalWork is an Android mobile application written in Kotlin using Jetpack Compose. It is the **operator-side control app** for a research study measuring **operator reaction time in VR-simulated logistics and industrial scenarios** (Project 3 with PBN partner — see `test/Assignment for PBN Partner_Project 3.docx`). The tablet pairs with a Meta Quest VR headset over local Wi-Fi, captures physiological data from BLE / audio-jack sensors during each scenario, and exports the bundled dataset (participant + session + scenarios + samples) to a central server at session end.

**Package:** `com.vitalwork.app`

**Tech Stack:**
- Kotlin 2.3.0 with Jetpack Compose (BOM 2026.01.00)
- Material Design 3
- Gradle 9.3.0 with Kotlin DSL and version catalog
- Hilt/Dagger for dependency injection
- Room 2.7.1 for local database
- Ktor 3.3.0 (CIO embedded HTTP server) — receives VR scenario events from the Quest
- Vico 2.1.2 for charts
- Play Services Wearable (Data Layer) for the watch ↔ tablet link
- Samsung Health Sensor SDK (local AAR, `:wear` only) for Galaxy Watch sensors
- Target: Android API 24–36 (`:app`); `:wear` floors at API 28 (Samsung SDK requirement)

## Build Commands

```bash
# Build (requires JDK 17+; Android Studio's bundled JBR works)
./gradlew build                    # Full build
./gradlew assembleDebug            # Debug APK only
./gradlew bundleRelease            # Signed release AAB (uses keystore from local.properties)

# Per-module (two modules: :app tablet, :wear watch)
./gradlew :app:assembleDebug       # Tablet/phone APK
./gradlew :wear:assembleDebug      # Wear OS companion APK

# Run
./gradlew installDebug             # Install debug APK on connected device/emulator

# Test
./gradlew test                     # Run unit tests (host)
./gradlew testDebugUnitTest        # Run debug unit tests only
./gradlew connectedAndroidTest     # Run instrumented tests (device/emulator)

# Clean
./gradlew clean
```

**Note:** The system JDK may be Java 11, which is too old for Gradle 9. Set `JAVA_HOME` to Android Studio's bundled JBR (`C:/Program Files/Android/Android Studio/jbr`) or add `org.gradle.java.home=...` to `gradle.properties`.

## Architecture

**Single Activity + Compose:**

```
MainActivity (entry point)
└── VitalWorkApplication (Hilt application class)
    └── VitalWorkTheme (Material 3 theme wrapper)
        └── AppNavigation (NavHost)
            └── Composable screens
```

**Module Structure:** Two Gradle modules:
- `:app` — the tablet/phone application (minSdk 24).
  - `app/src/main/` — Production code
  - `app/src/test/` — Unit tests (JUnit 4)
  - `app/src/androidTest/` — Instrumented tests (AndroidX Test)
- `:wear` — a thin Wear OS companion (minSdk 28, **same** `applicationId`) that reads Galaxy
  Watch sensors via the Samsung Health Sensor SDK and streams them to the tablet over the Wearable
  Data Layer. See [doc/sensor_galaxy_watch.md](doc/sensor_galaxy_watch.md). The Samsung SDK is a
  local AAR at `wear/libs/`. `settings.gradle.kts` uses `FAIL_ON_PROJECT_REPOS`, so module-level
  `repositories {}` blocks are forbidden — repos (incl. the `flatDir` for both `app/libs` and
  `wear/libs`) live only in `settings.gradle.kts`.

## Application Purpose

The app has four main responsibilities:

1. **VR Control** — embedded HTTP server (Ktor) that the Meta Quest VR app POSTs scenario events to over local Wi-Fi; the tablet advertises itself via a UDP presence beacon (Unreal Engine has no mDNS). The tablet stamps each event's arrival time (tablet clock only; no device sync)
2. **Sensor Data Collection** — Gather physiological data (heart rate, RR intervals, ECG, respiration) from BLE and audio jack sensors
3. **Test Management** — Organize anonymous clinical test sessions with recordings, SUDS scores, local storage, and JSON/CSV export
4. **Device-to-Device Link + Screen Mirroring** — a direct tablet↔tablet/phone link over local Wi-Fi (server hosts, client connects; mDNS discovery + a WebSocket on port 9090) that also carries WebRTC signaling so the operator (server) can watch the monitored device's (client) live screen, peer-to-peer with no media server or cloud cost. See **Device-to-Device Link** below.

**Device mode (launch picker):** on first launch the app asks whether this device is **Server** or **Client** (`ModeSelectionScreen`); the choice is persisted (`DeviceModePreferencesRepository`) so later launches skip straight to Home. Home is mode-aware: in **Server** mode it shows only **Connect as Server** plus a **Change Mode** button; in **Client** mode it shows the full operator home (sessions, sensors, etc.) minus **Connect as Server**. The mode can be changed any time via **Change Mode** on Home.

**Target devices:** Android tablet and phone

## Key Configuration

**Dependency Management:** All versions and dependencies are centralized in `gradle/libs.versions.toml`. Add new dependencies there first, then reference them in `app/build.gradle.kts` using the `libs.` accessor.

**App Configuration (`app/build.gradle.kts`):**
- Application ID: `com.vitalwork.app`
- Min SDK 24, Target/Compile SDK 36
- Java 11 compatibility
- Compose build feature enabled
- Release signing configured via `local.properties` (KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)

**Local SDKs (`app/libs/`):**
- `eSense_sdk_2_lib.jar` — eSense Respiration sensor SDK

## Git Workflow

- `main` — Production branch
- `dev` — Development/integration branch (default for PRs)
- Feature branches: `feature/<name>` or `<author>/<name>`

## Supported Sensors

| Sensor | Vendor | Connection | Data Collected |
|--------|--------|------------|----------------|
| eSense Pulse | Mindfield | BLE | Heart rate (BPM), RR intervals |
| eSense Respiration | Mindfield | Audio jack | Respiration rate |
| Galaxy Watch 8 | Samsung | Wearable Data Layer (via `:wear` companion) | Heart rate (BPM), IBI (ms), EDA (µS), battery |

Per-sensor references live in [doc/](doc/): [sensor_esense_pulse.md](doc/sensor_esense_pulse.md),
[sensor_esense_respiration.md](doc/sensor_esense_respiration.md),
[sensor_galaxy_watch.md](doc/sensor_galaxy_watch.md).

**BLE Requirements (eSense Pulse):**
- Android 12+: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` permissions
- Android 11 and below: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` permissions
- **Location Services must be enabled** on the device for BLE scanning to work

**Galaxy Watch 8:** the Watch 8 has **no BLE Heart Rate Profile**; its sensors (especially EDA) are
reachable only through Samsung's Health Sensor SDK running **on the watch**. The `:wear` companion
reads them and streams JSON readings to the tablet via `MessageClient` over the Wearable Data Layer;
the tablet receives them in `WatchListenerService` → `WatchSensorReceiver` (Hilt singleton) and shows
them under **Sensors → Galaxy Watch**. Continuous screen-off delivery requires a foreground `health`
service, `BODY_SENSORS_BACKGROUND`, and a 1 Hz `HealthTracker.flush()` loop. **The link must run over
direct Bluetooth** — if the phone's Bluetooth is off the Data Layer silently falls back to the Google
cloud relay, which can't deliver to a dozing phone (presents as "connection drops whenever the phone
sleeps"); the Galaxy Watch screen shows a Bluetooth-off warning to prevent this.

**Store-and-forward + remote flush (implemented 2026-06):** the watch **persists** every HR/IBI/EDA
reading to a local append-only file (`WatchSampleStore`) as it samples, so a screen-off/Doze session
loses nothing. At session end the phone sends a remote `FLUSH` command (`WatchCommandSender` →
`WatchCommandListenerService`); the watch ships its stored rows back as **`DataClient` DataItems**
(`WatchFlushWriter`), which the phone ingests (`WatchListenerService.onDataChanged`), acks (deletes the
item + `FLUSH_ACK:<ts>`), and the watch then truncates its store. EDA + HR + IBI are recorded to the DB
and appear in the export. The watch also beats a low-rate `HEARTBEAT` so the phone shows a calm "Watch
dozing — buffering" state (`WatchSensorReceiver.linkStatus = LIVE/DOZING/DISCONNECTED`) instead of a
false "Disconnected" during expected Doze gaps. Auto-wake is **best-effort** (a deeply-dozing watch may
miss the command) with the manual tap as fallback — but data is never lost (the store is durable until
acked). See [doc/sensor_galaxy_watch.md](doc/sensor_galaxy_watch.md) for the full rationale (incl. why
FCM/Wi-Fi were rejected, and what does *not* work).

## Device-to-Device Link & Screen Mirroring

A direct tablet↔tablet/phone link over local Wi-Fi, separate from the VR link. One device is the
**server** (host) and the other the **client**; the server opens a `WebSocketServer` on **port 9090**
(advertised via mDNS), the client discovers and connects. The same WebSocket carries (a) pairing +
a free-text diagnostics log and (b) **WebRTC signaling** (offer/answer/ICE) for **screen mirroring** —
the operator (server) watches the monitored device's (client) live screen. The video itself flows
**peer-to-peer over WebRTC/UDP**, not through the WebSocket. Screen mirroring is **free to run** (no
media server, no cloud relay, no recurring cost — only Google's free public STUN for LAN address
discovery); the only real costs are battery/heat and local Wi-Fi bandwidth. Off-LAN use would need a
TURN relay (not used here).

The link runs in **one role at a time** (`PeerLinkManager.activeRole`), kept alive across sleep by a
foreground `BackgroundConnectionService`. Cleartext is allowed only for the LAN
(`network_security_config.xml`); screen capture needs `FOREGROUND_SERVICE_MEDIA_PROJECTION` +
`POST_NOTIFICATIONS`.

Docs (in [doc/](doc/)): [peer_link_websocket.md](doc/peer_link_websocket.md) (link reference),
[webrtc_screen_share.md](doc/webrtc_screen_share.md) (screen-mirror reference + cost analysis),
[screen_share_reproduction.md](doc/screen_share_reproduction.md) (step-by-step recipe).

## Package Structure

```
com.vitalwork.app/
├── MainActivity.kt
├── VitalWorkApplication.kt        # Hilt application class
├── di/
│   └── AppModule.kt                        # Hilt dependency injection module
├── data/
│   ├── db/                                 # Room database (v2, 4 entities, 4 DAOs)
│   │   ├── AppDatabase.kt
│   │   ├── Converters.kt                   # Enum type converters
│   │   ├── ParticipantEntity.kt            # Anonymized test subject
│   │   ├── ParticipantDao.kt
│   │   ├── SessionEntity.kt                # Session + SessionStatus enum
│   │   ├── SessionDao.kt
│   │   ├── ScenarioEntity.kt               # VR scenario run + ScenarioCode + ScenarioCategory enums
│   │   ├── ScenarioDao.kt
│   │   ├── SensorSampleEntity.kt           # Sensor samples + SensorType enum (HR/Resp/eSenseRR/EDA/WatchIBI)
│   │   └── SensorSampleDao.kt
│   ├── export/                             # Session export (Section 7 shape; JSON + CSV)
│   │   ├── SessionExportService.kt
│   │   ├── SessionExportMapper.kt
│   │   ├── SessionUploader.kt
│   │   └── model/
│   │       └── SessionExportModel.kt
│   ├── model/
│   │   └── ConnectionState.kt
│   ├── network/
│   │   └── NetworkChecker.kt               # LAN connectivity checker
│   ├── link/                               # Device-to-device peer link (WebSocket on :9090 + mDNS)
│   │   ├── PeerLinkManager.kt              # interface: start server/client, connection state, activeRole
│   │   ├── PeerLinkManagerImpl.kt           # Java-WebSocket server/client + WebRTC signaling relay
│   │   ├── PeerRole.kt                     # SERVER | CLIENT
│   │   ├── PeerMdnsService.kt              # advertise/discover the peer over mDNS
│   │   ├── LanAddress.kt                   # local LAN IP helper
│   │   └── model/
│   │       ├── PeerDevice.kt
│   │       └── PeerMessage.kt              # @Serializable link envelope (greeting/test-msg/signaling)
│   ├── webrtc/                             # Screen mirroring (server views client's screen, P2P)
│   │   ├── WebRtcEngine.kt                 # libwebrtc (stream-webrtc-android) peer connection
│   │   └── ScreenShareController.kt         # MediaProjection capture + offer/answer/ICE driving
│   ├── prefs/
│   │   ├── TutorialPreferencesRepository.kt
│   │   ├── DeviceModePreferencesRepository.kt # Persisted Server/Client launch mode (SharedPrefs)
│   │   └── SettingsRepository.kt            # Device prefix (A/B/C/D) for code generation; interface + SharedPrefs impl
│   ├── recording/
│   │   ├── GapDetector.kt                  # Sensor data gap detection
│   │   ├── ScenarioRecordingRepository.kt
│   │   ├── ScenarioRecordingRepositoryImpl.kt
│   │   └── model/
│   │       └── ScenarioRecordingSession.kt
│   ├── repository/
│   │   ├── ConnectionRepository.kt
│   │   ├── ParticipantRepository.kt
│   │   ├── ScenarioRepository.kt
│   │   └── SessionRepository.kt
│   ├── sensor/
│   │   ├── SensorDevice.kt                 # Sensor interface
│   │   ├── audio/
│   │   │   └── MindfieldRespiration.kt     # eSense Respiration SDK wrapper
│   │   ├── ble/
│   │   │   ├── BleManager.kt               # eSense Pulse BLE interface
│   │   │   ├── BleManagerImpl.kt            # eSense Pulse BLE implementation
│   │   │   └── model/
│   │   │       ├── BleDevice.kt
│   │   │       └── BleGattService.kt
│   │   └── watch/                          # Galaxy Watch (Data Layer receiver side)
│   │       ├── WatchListenerService.kt     # WearableListenerService; parses live messages + ingests flush DataItems (onDataChanged)
│   │       ├── WatchSensorReceiver.kt      # Hilt singleton sink; linkStatus (LIVE/DOZING/DISCONNECTED) + flushed-reading ingest
│   │       ├── WatchCommandSender.kt       # phone→watch commands (START/FLUSH/STOP/FLUSH_ACK); interface + impl
│   │       └── model/
│   │           └── WatchReading.kt
│   └── vr/                                 # VR link (tablet = HTTP server; Quest = client)
│       ├── VrHttpServer.kt                  # Ktor CIO server; 4 routes → VrEventReceiver
│       ├── VrEventReceiver.kt              # Hilt singleton sink; accept/reject + ack-after-write + inferred connection state
│       ├── VrUdpBeacon.kt                   # UDP presence broadcast (ip/port/sessionId) every 5 s
│       ├── VrEvent.kt                       # sealed VrEvent (ScenarioStart/StimulusEvent/Reaction/ScenarioStop) + VrEventResult
│       └── http/
│           └── VrHttpDtos.kt                # @Serializable wire DTOs (ScenarioRequest + responses)
├── presentation/
│   ├── components/                          # Reusable UI components
│   │   ├── BioSensorCard.kt
│   │   ├── BleDialogTypes.kt
│   │   ├── ConnectionStatusBadge.kt
│   │   ├── LowSignalWarningBanner.kt
│   │   ├── NavigationCard.kt
│   │   ├── RecordingIndicator.kt
│   │   ├── RecordingPanel.kt
│   │   └── SensorTypeCard.kt
│   ├── log/
│   │   ├── LogEntry.kt
│   │   └── BleLogEntry.kt
│   ├── navigation/
│   │   └── AppNavigation.kt
│   └── screens/
│       ├── home/
│       │   ├── HomeScreen.kt               # Mode-aware dashboard (Server vs Client layout)
│       │   ├── HomeViewModel.kt
│       │   └── components/
│       │       ├── PrimaryActionButton.kt
│       │       └── SecondaryNavRow.kt
│       ├── mode/                           # First-launch Server/Client picker (+ Change Mode)
│       │   ├── ModeSelectionScreen.kt
│       │   └── ModeSelectionViewModel.kt
│       ├── link/                           # Peer-link diagnostics + screen-mirror controls
│       │   ├── PeerLinkScreen.kt
│       │   └── PeerLinkViewModel.kt
│       ├── sensors/
│       │   ├── SensorDetailScreen.kt        # Router to vendor-specific screens
│       │   ├── SensorsScreen.kt
│       │   ├── SensorsViewModel.kt
│       │   ├── components/
│       │   │   ├── BleDebugLog.kt
│       │   │   ├── BleDeviceItem.kt
│       │   │   ├── BleServiceExplorer.kt
│       │   │   ├── HeartRateDisplay.kt
│       │   │   └── RrIntervalDisplay.kt
│       │   ├── mindfield/
│       │   │   ├── pulse/
│       │   │   │   ├── EsensePulseScreen.kt
│       │   │   │   └── EsensePulseViewModel.kt
│       │   │   └── respiration/
│       │   │       ├── EsenseRespirationScreen.kt
│       │   │       └── EsenseRespirationViewModel.kt
│       │   └── watch/                       # Galaxy Watch live-readings screen
│       │       ├── WatchSensorScreen.kt
│       │       └── WatchSensorViewModel.kt
│       ├── participants/
│       │   ├── ParticipantEntryScreen.kt
│       │   └── ParticipantEntryViewModel.kt
│       ├── sessions/
│       │   ├── ScenarioRecordingUiState.kt
│       │   ├── SessionControlScreen.kt
│       │   ├── SessionControlViewModel.kt
│       │   ├── SessionDetailScreen.kt
│       │   ├── SessionDetailViewModel.kt
│       │   ├── SessionsScreen.kt
│       │   ├── SessionsViewModel.kt
│       │   └── components/
│       │       ├── ActiveSessionBanner.kt
│       │       ├── DeviceSensorGroup.kt
│       │       ├── LiveSensorCard.kt
│       │       ├── SensorSummaryCard.kt
│       │       ├── SessionCard.kt
│       │       └── SessionNotesField.kt
│       ├── settings/                       # Device prefix selection (A/B/C/D)
│       │   ├── SettingsScreen.kt
│       │   └── SettingsViewModel.kt
│       ├── tutorial/
│       │   ├── TutorialScreen.kt
│       │   └── TutorialViewModel.kt
│       └── vr/
│           ├── VRConnectionScreen.kt
│           └── VRConnectionViewModel.kt
├── service/
│   ├── BackgroundConnectionService.kt       # Foreground service keeping link + screen-share alive
│   └── BatteryOptimizationHelper.kt         # Prompts Doze exemption so the link survives sleep
└── ui/theme/
    ├── Color.kt
    ├── Theme.kt
    └── Type.kt
```

**`:wear` module** (`com.vitalwork.wear`):

```
com.vitalwork.wear/
├── MainActivity.kt           # Minimal Start/Stop watch UI + runtime permission requests
├── WatchSensorService.kt          # Foreground health service; owns the Samsung SDK, flush() loop, heartbeat; emit() persists to store + streams
├── WatchSampleStore.kt            # Append-only JSON-lines durable store; truncate-after-ack (store-and-forward)
├── WatchCommandListenerService.kt # WearableListenerService; handles START/FLUSH/STOP/FLUSH_ACK from the phone
├── WatchFlushWriter.kt            # Pushes stored rows to the phone as chunked DataClient DataItems
├── WatchDataSender.kt             # MessageClient sender; resolves/caches the vitalwork_phone node
└── WatchMessage.kt                # Builds JSON lines (reading, capabilities, batch, stop, heartbeat)
```

## Navigation Routes

**Start destination** is chosen in `MainActivity` from the persisted device mode: `mode` (the picker) if no mode is set yet, otherwise `home`.

| Route | Screen | Description |
|-------|--------|-------------|
| `mode` | ModeSelectionScreen | First-launch Server/Client picker; also reachable from Home via **Change Mode** |
| `tutorial` | TutorialScreen | First-launch onboarding |
| `home` | HomeScreen | Mode-aware dashboard (Server shows only Connect-as-Server + Change Mode; Client shows the full home) |
| `link/{role}` | PeerLinkScreen | Device-to-device link (`server`/`client`): pairing, diagnostics, and screen-mirror controls |
| `vr_control` | VRConnectionScreen | VR link diagnostics (tablet IP/port + live received-event log) |
| `settings` | SettingsScreen | Device prefix (A/B/C/D) tagging participant + session codes so parallel tablets don't collide |
| `sensors` | SensorsScreen | List of available sensors |
| `sensors/{sensorId}` | SensorDetailScreen | Router to vendor-specific sensor screen |
| `participants/new` | ParticipantEntryScreen | Anonymized participant entry (creates participant + session) |
| `sessions` | SessionsScreen | List of completed sessions |
| `sessions/active/{sessionId}` | SessionControlScreen | Active session control panel |
| `sessions/review/{sessionId}` | SessionDetailScreen | Session review with export to Documents |

## Database Schema

Room database (version 6) with 4 entities. Cascade-delete on all foreign keys. Uses
`fallbackToDestructiveMigration` (enums are stored as strings), so adding/removing a `SensorType`
value or column is a version bump with no hand-written `Migration` — the destructive fallback wipes
old local rows (sessions are already exported/uploaded). History: v2 added `WATCH_IBI`; v3 split
per-device sensor types; v4 added the watch sample counters; v5 dropped the reaction-time/VR
fields (`scenarioCategory`, `eventTimestampMs`, `reactionTimestampMs`) and `sessions.notes` for the
biofeedback-only pivot; **v6** renamed the nine industrial scenario codes to the five biofeedback
scenarios.

| Entity | Table | Purpose |
|--------|-------|---------|
| ParticipantEntity | `participants` | Anonymized test subjects (unique `participantCode`) |
| SessionEntity | `sessions` | Per-participant session run (FK → participants; status: ACTIVE, COMPLETED, UPLOADED; per-type sample counters) |
| ScenarioEntity | `scenarios` | One scenario run within a session (FK → sessions; `scenarioCode` + `startedAt`/`endedAt`) |
| SensorSampleEntity | `sensor_samples` | Time-series sensor data (FK → scenarios; carries `timestampMs` + `elapsedMs`) |

**Enums**

| Enum | Stored values |
|------|---------------|
| `SessionStatus` | `ACTIVE`, `COMPLETED`, `UPLOADED` |
| `ScenarioCode` | `REFERENCE_STATE`, `COGNITIVE_LOAD`, `DISTRACTING_ENVIRONMENT`, `LONG_TERM_FATIGUE`, `REACTION_TASKS` |
| `SensorType` | `ESENSE_HEART_RATE`, `RESPIRATION`, `ESENSE_RR_INTERVAL`, `WATCH_HR`, `WATCH_IBI`, `WATCH_EDA` |

`ScenarioCode` carries a short official code (`A`…`E`) and a display label (e.g. `Scenario A –
Reference State`) as enum properties — the constant *name* (e.g. `REFERENCE_STATE`) is what's stored
in the DB and sent to the server, so the descriptive labels can change without breaking old rows. (The
`ScenarioCategory` concept and the `scenarioCategory` column were removed in v5.)

Session duration is derived from `endedAt − startedAt`. All timestamps come from an NTP-corrected
clock (`TimeProvider`) on the same UTC timeline, so cross-stream alignment needs no clock-sync.

**Device prefix (multi-tablet testing):** each tablet picks a one-time prefix (A/B/C/D) under **Settings** (`SettingsRepository`, SharedPreferences, default `A`). The prefix tags both the generated participant code (`A-001`) and session code (`VW-A-yyMMdd-HHmmss`), so several tablets testing in parallel never mint colliding codes that would look like one duplicated participant after the server merge. The participant-code field is read-only (auto-generated) to keep the scheme typo-proof; participant numbering is counted **per prefix** (`ParticipantDao.getParticipantCountByPrefix`). Operators must agree beforehand which device owns which letter — collisions are only prevented across devices with *distinct* letters.

## Data Flow

```
Meta Quest VR ──HTTP POST──► VrHttpServer ──► VrEventReceiver ──► SessionControlViewModel ──► UI
                  ▲                               │ (ack-after-write)
       VrUdpBeacon (tablet advertises ip/port/sessionId)   └──► ScenarioRepository (event/reaction timestamps)

eSense Pulse  ◄────BLE──────► BleManager ──────────► EsensePulseViewModel ──► UI
eSense Resp.  ◄────Audio────► MindfieldRespiration ► EsenseRespirationViewModel ► UI

All sensors ──► ScenarioRecordingRepository ──► Room DB ──► SessionExportService ──► JSON/CSV

Server (operator) ⇄ WebSocket :9090 (signaling) ⇄ Client (monitored)   via PeerLinkManager
Server (viewer)   ◄── WebRTC P2P/UDP (live screen video) ── Client (sharer)   via WebRtcEngine/ScreenShareController
```

## Required Permissions

```xml
<!-- Network -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

<!-- Audio (eSense Respiration) -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

<!-- Bluetooth - Android 12+ (API 31+) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

<!-- Bluetooth - Android 11 and below (API 30 and below) -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />

<!-- Location (required by Android for BLE scanning; not used for geolocation) -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- Storage (legacy export, API <29 only) -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />

<!-- Foreground services (peer link kept alive + screen mirroring) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />

<!-- Notifications (foreground-service notification; Android 13+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Unit Tests

Unit tests live under `app/src/test/` and run on the host JVM (no device/emulator needed).

```bash
./gradlew test                     # Run all unit tests
./gradlew testDebugUnitTest        # Run debug unit tests only
```

**Current test files:**

| File | Target | What it covers |
|------|--------|----------------|
| `data/recording/GapDetectorTest.kt` | `GapDetector.kt` | Gap detection edge cases: empty input, startup threshold, boundary conditions, mixed sensor types, unsorted input, per-sensor-type routing |
| `data/vr/VrEventReceiverTest.kt` | `VrEventReceiver.kt` | Event/reaction persistence + accept, reject when no active scenario, first-write-wins, late-reaction grace window, heartbeat-less liveness watchdog |
| `data/repository/ParticipantRepositoryTest.kt` | `ParticipantRepository.kt` | Code generation (`A-001`…, per-device-prefix scoped), uniqueness validation, fetch by ID/code |
| `data/repository/SessionRepositoryTest.kt` | `SessionRepository.kt` | Session lifecycle: `sessionCode` format (VW-{prefix}-yyMMdd-HHmmss), participant FK, sample-count aggregation from scenarios at end, status transitions, notes persistence, deletion |
| `data/repository/ScenarioRepositoryTest.kt` | `ScenarioRepository.kt` | Scenario lifecycle: create, end (sets `endedAt`), close dangling scenarios, batch sample insert |
| `data/export/SessionExportMapperTest.kt` | `SessionExportMapper.kt` | Export data transformation (Section 7 shape): participant + session + scenarios + samples; sensor type mapping, gap detection per scenario, derived reaction time |
| `data/recording/ScenarioRecordingRepositoryImplTest.kt` | `ScenarioRecordingRepositoryImpl.kt` | Start/stop state machine, sensor detection, sample buffering + flushing, scenario-end finalization |
| `data/sensor/audio/MindfieldRespirationTest.kt` | `MindfieldRespiration.kt` | Zero-crossing breathing rate algorithm and signal verification logic |
| `presentation/screens/participants/ParticipantEntryViewModelTest.kt` | `ParticipantEntryViewModel.kt` | Form validation, duplicate-code rejection, success emission, active-session redirect |
| `presentation/screens/sessions/SessionControlViewModelTest.kt` | `SessionControlViewModel.kt` | Session loading, scenario-driven recording, end-session flow |
| `presentation/screens/sessions/SessionDetailViewModelTest.kt` | `SessionDetailViewModel.kt` | Session/scenario loading, export workflow with `markUploaded` transition |
| `data/recording/WatchSessionDrainerTest.kt` | `WatchSessionDrainer.kt` | Per-(scenario,type) timestamp-window attribution + de-dup for EDA/HR/IBI; gap/boundary/back-to-back rules |
| `data/sensor/watch/WatchLinkStatusTest.kt` | `WatchSensorReceiver.kt` | LIVE/DOZING/DISCONNECTED transitions (reading→LIVE, heartbeat→DOZING, STOP→DISCONNECTED) |
| `data/sensor/watch/WatchSensorReceiverBatteryAlertTest.kt` | `WatchSensorReceiver.kt` | Low-battery alert tier snapshot (`currentBatteryAlert`) |

**`:wear` module tests** (`wear/src/test/`): `WatchSampleStoreTest.kt` — append, truncate-after-ack
(inclusive boundary, keep-unparseable, keep-un-acked-tail), clear, `shouldPersist` filtering.

Tests mirror the production package structure (e.g., `GapDetectorTest.kt` is in the same package as `GapDetector.kt`). This enables Android Studio's **Ctrl+Shift+T** navigation between production code and its test.
