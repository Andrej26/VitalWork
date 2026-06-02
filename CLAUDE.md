# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BioMetrixOperator is an Android mobile application written in Kotlin using Jetpack Compose. It is the **operator-side control app** for a research study measuring **operator reaction time in VR-simulated logistics and industrial scenarios** (Project 3 with PBN partner — see `test/Assignment for PBN Partner_Project 3.docx`). The tablet pairs with a Meta Quest VR headset over local Wi-Fi, captures physiological data from BLE / audio-jack sensors during each scenario, and exports the bundled dataset (participant + session + scenarios + samples) to a central server at session end.

**Package:** `com.biometrix.operator`

**Tech Stack:**
- Kotlin 2.3.0 with Jetpack Compose (BOM 2026.01.00)
- Material Design 3
- Gradle 9.3.0 with Kotlin DSL and version catalog
- Hilt/Dagger for dependency injection
- Room 2.7.1 for local database
- OkHttp 5.3.2 for WebSocket
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
└── BioMetrixOperatorApplication (Hilt application class)
    └── BioMetrixOperatorTheme (Material 3 theme wrapper)
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

The app has three main responsibilities:

1. **VR Control** — WebSocket client connecting to the BioMetrix VR app on a Meta Quest headset over local Wi-Fi
2. **Sensor Data Collection** — Gather physiological data (heart rate, RR intervals, ECG, respiration) from BLE and audio jack sensors
3. **Test Management** — Organize anonymous clinical test sessions with recordings, SUDS scores, local storage, and JSON/CSV export

**Target devices:** Android tablet and phone

## Key Configuration

**Dependency Management:** All versions and dependencies are centralized in `gradle/libs.versions.toml`. Add new dependencies there first, then reference them in `app/build.gradle.kts` using the `libs.` accessor.

**App Configuration (`app/build.gradle.kts`):**
- Application ID: `com.biometrix.operator`
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

**Galaxy Watch 8 (Phase 1 — live display only):** the Watch 8 has **no BLE Heart Rate Profile**;
its sensors (especially EDA) are reachable only through Samsung's Health Sensor SDK running **on the
watch**. The `:wear` companion reads them and streams JSON readings to the tablet via `MessageClient`
(Bluetooth, no internet); the tablet receives them in `WatchListenerService` → `WatchSensorReceiver`
(Hilt singleton) and shows them under **Sensors → Galaxy Watch**. **No DB/recording/export wiring
yet.** Continuous screen-off delivery requires a foreground `health` service, `BODY_SENSORS_BACKGROUND`,
and a 1 Hz `HealthTracker.flush()` loop — see [doc/sensor_galaxy_watch.md](doc/sensor_galaxy_watch.md)
for the full rationale (incl. what does *not* work).

## Package Structure

```
com.biometrix.operator/
├── MainActivity.kt
├── BioMetrixOperatorApplication.kt        # Hilt application class
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
│   │   ├── SensorSampleEntity.kt           # Sensor samples + SensorType enum (HR/Resp/RR/GSR)
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
│   ├── prefs/
│   │   └── TutorialPreferencesRepository.kt
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
│   │       ├── WatchListenerService.kt     # WearableListenerService; parses incoming messages
│   │       ├── WatchSensorReceiver.kt      # Hilt singleton sink + inferred connection state
│   │       └── model/
│   │           └── WatchReading.kt
│   └── vr/
│       ├── VRWebSocketClient.kt
│       ├── MdnsDiscoveryService.kt          # mDNS headset auto-discovery
│       └── model/
│           ├── DiscoveredVrDevice.kt
│           └── WebSocketMessage.kt
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
│       │   ├── HomeScreen.kt
│       │   └── HomeViewModel.kt
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
│       ├── tutorial/
│       │   ├── TutorialScreen.kt
│       │   └── TutorialViewModel.kt
│       └── vr/
│           ├── VRConnectionScreen.kt
│           └── VRConnectionViewModel.kt
└── ui/theme/
    ├── Color.kt
    ├── Theme.kt
    └── Type.kt
```

**`:wear` module** (`com.biometrix.operator.wear`):

```
com.biometrix.operator.wear/
├── MainActivity.kt           # Minimal Start/Stop watch UI + runtime permission requests
├── WatchSensorService.kt     # Foreground health service; owns the Samsung SDK, flush() loop, sends STOP
├── WatchDataSender.kt        # MessageClient sender; resolves/caches the biometrix_phone node
└── WatchMessage.kt           # Builds JSON lines (reading, capabilities, batch, stop)
```

## Navigation Routes

| Route | Screen | Description |
|-------|--------|-------------|
| `tutorial` | TutorialScreen | First-launch onboarding |
| `home` | HomeScreen | Main dashboard with navigation cards |
| `vr_control` | VRConnectionScreen | VR headset connection (manual IP or mDNS discovery) |
| `sensors` | SensorsScreen | List of available sensors |
| `sensors/{sensorId}` | SensorDetailScreen | Router to vendor-specific sensor screen |
| `participants/new` | ParticipantEntryScreen | Anonymized participant entry (creates participant + session) |
| `sessions` | SessionsScreen | List of completed sessions |
| `sessions/active/{sessionId}` | SessionControlScreen | Active session control panel |
| `sessions/review/{sessionId}` | SessionDetailScreen | Session review with export to Documents |

## Database Schema

Room database (version 2) with 4 entities. Cascade-delete on all foreign keys.

| Entity | Table | Purpose |
|--------|-------|---------|
| ParticipantEntity | `participants` | Anonymized test subjects (unique `participantCode`) |
| SessionEntity | `sessions` | Per-participant session run (FK → participants; status: ACTIVE, COMPLETED, UPLOADED) |
| ScenarioEntity | `scenarios` | One VR scenario run within a session (FK → sessions; scenarioCode + scenarioCategory + event/reaction timestamps) |
| SensorSampleEntity | `sensor_samples` | Time-series sensor data (FK → scenarios; carries `timestampMs` + `elapsedMs`) |

**Enums**

| Enum | Stored values |
|------|---------------|
| `SessionStatus` | `ACTIVE`, `COMPLETED`, `UPLOADED` |
| `ScenarioCategory` | `A`, `B`, `C` |
| `ScenarioCode` | `FALLING_PALLET`, `BLIND_CORNER`, `EQUIPMENT_COLLISION`, `FLOOR_OBSTACLE`, `MACHINE_JAM`, `CONVEYOR_ACCELERATION`, `MEDIUM_LEAKAGE`, `ELECTRICAL_SHORT`, `SLING_FAILURE` |
| `SensorType` | `HEART_RATE`, `RESPIRATION`, `ESENSE_RR_INTERVAL`, `GSR` |

`ScenarioCode` carries the official short code (e.g. `A1`) and display label as enum properties — they're stored descriptively in the DB so renumbering doesn't break old rows.

Reaction time is **derived** at export from `reactionTimestampMs − eventTimestampMs`; not stored. Session duration is derived from `endedAt − startedAt`. All timestamps come from Android's `System.currentTimeMillis()` so cross-stream alignment needs no clock-sync.

## Data Flow

```
Meta Quest VR ◄──WebSocket──► VRWebSocketClient ──► VRConnectionViewModel ──► UI
                               ▲
                      MdnsDiscoveryService (auto-discovery)

eSense Pulse  ◄────BLE──────► BleManager ──────────► EsensePulseViewModel ──► UI
eSense Resp.  ◄────Audio────► MindfieldRespiration ► EsenseRespirationViewModel ► UI

All sensors ──► ScenarioRecordingRepository ──► Room DB ──► SessionExportService ──► JSON/CSV
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
| `data/vr/model/WebSocketMessageTest.kt` | `WebSocketMessage.kt` | `ServerMessage` JSON serialization: minimal/full/failure decoding, round-trip, malformed JSON, missing fields, unknown fields |
| `data/repository/ParticipantRepositoryTest.kt` | `ParticipantRepository.kt` | Code generation (`P-001`…), uniqueness validation, fetch by ID/code |
| `data/repository/SessionRepositoryTest.kt` | `SessionRepository.kt` | Session lifecycle: `sessionCode` format (BMX-yyMMdd-HHmmss), participant FK, sample-count aggregation from scenarios at end, status transitions, notes persistence, deletion |
| `data/repository/ScenarioRepositoryTest.kt` | `ScenarioRepository.kt` | Scenario lifecycle: create with derived `scenarioCategory`, event/reaction timestamp updates, end (sets `endedAt`), batch sample insert |
| `data/export/SessionExportMapperTest.kt` | `SessionExportMapper.kt` | Export data transformation (Section 7 shape): participant + session + scenarios + samples; sensor type mapping, gap detection per scenario, derived reaction time |
| `data/recording/ScenarioRecordingRepositoryImplTest.kt` | `ScenarioRecordingRepositoryImpl.kt` | Start/stop state machine, sensor detection, sample buffering + flushing, scenario-end finalization |
| `data/sensor/audio/MindfieldRespirationTest.kt` | `MindfieldRespiration.kt` | Zero-crossing breathing rate algorithm and signal verification logic |
| `presentation/screens/participants/ParticipantEntryViewModelTest.kt` | `ParticipantEntryViewModel.kt` | Form validation, duplicate-code rejection, success emission, active-session redirect |
| `presentation/screens/sessions/SessionControlViewModelTest.kt` | `SessionControlViewModel.kt` | Session loading, scenario-driven recording, end-session flow |
| `presentation/screens/sessions/SessionDetailViewModelTest.kt` | `SessionDetailViewModel.kt` | Session/scenario loading, export workflow with `markUploaded` transition |
| `presentation/screens/vr/VRConnectionViewModelTest.kt` | `VRConnectionViewModel.kt` | VR connection state machine, command sending, discovery lifecycle |

Tests mirror the production package structure (e.g., `GapDetectorTest.kt` is in the same package as `GapDetector.kt`). This enables Android Studio's **Ctrl+Shift+T** navigation between production code and its test.
