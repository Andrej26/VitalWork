# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BioMetrixOperator is an Android mobile application written in Kotlin using Jetpack Compose. It is a **clinical VR therapy control application** for claustrophobia exposure therapy (BioMetrix).

**Package:** `com.biometrix.operator`

**Tech Stack:**
- Kotlin 2.3.0 with Jetpack Compose (BOM 2026.01.00)
- Material Design 3
- Gradle 9.3.0 with Kotlin DSL and version catalog
- Hilt/Dagger for dependency injection
- Room 2.7.1 for local database
- OkHttp 5.3.2 for WebSocket
- Vico 2.1.2 for charts
- Target: Android API 24–36

## Build Commands

```bash
# Build (requires JDK 17+; Android Studio's bundled JBR works)
./gradlew build                    # Full build
./gradlew assembleDebug            # Debug APK only
./gradlew bundleRelease            # Signed release AAB (uses keystore from local.properties)

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

**Module Structure:** Single `:app` module with standard Android source sets:
- `app/src/main/` — Production code
- `app/src/test/` — Unit tests (JUnit 4)
- `app/src/androidTest/` — Instrumented tests (AndroidX Test)

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
- `mdslib-3.27.0-release.aar` — Movesense MDS library for Fibion Flash sensor

## Git Workflow

- `main` — Production branch
- `dev` — Development/integration branch (default for PRs)
- Feature branches: `feature/<name>` or `<author>/<name>`

## Supported Sensors

| Sensor | Vendor | Connection | Data Collected |
|--------|--------|------------|----------------|
| eSense Pulse | Mindfield | BLE | Heart rate (BPM), RR intervals |
| eSense Respiration | Mindfield | Audio jack | Respiration rate |
| Fibion Flash | Fibion (Movesense) | BLE | Heart rate (BPM), ECG (125 Hz), RR intervals |

**BLE Requirements:**
- Android 12+: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` permissions
- Android 11 and below: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` permissions
- **Location Services must be enabled** on the device for BLE scanning to work

## Package Structure

```
com.biometrix.operator/
├── MainActivity.kt
├── BioMetrixOperatorApplication.kt        # Hilt application class
├── di/
│   └── AppModule.kt                        # Hilt dependency injection module
├── data/
│   ├── db/                                 # Room database (v1, 4 entities, 4 DAOs)
│   │   ├── AppDatabase.kt
│   │   ├── Converters.kt                   # Enum type converters
│   │   ├── TestEntity.kt                   # Test + TestStatus enum
│   │   ├── TestDao.kt
│   │   ├── RecordingEntity.kt              # Recording + RecordingStatus enum
│   │   ├── RecordingDao.kt
│   │   ├── SensorSampleEntity.kt           # Sensor samples + SensorType enum
│   │   ├── SensorSampleDao.kt
│   │   ├── SudsEventEntity.kt              # SUDS score events
│   │   └── SudsEventDao.kt
│   ├── export/                             # Test data export (JSON + CSV)
│   │   ├── TestExportService.kt
│   │   └── model/
│   │       └── TestExportModel.kt
│   ├── model/
│   │   └── ConnectionState.kt
│   ├── network/
│   │   └── NetworkChecker.kt               # LAN connectivity checker
│   ├── prefs/
│   │   ├── HeartRateDevicePreferences.kt   # HR sensor selection (eSense Pulse vs Fibion Flash)
│   │   └── TutorialPreferencesRepository.kt
│   ├── recording/
│   │   ├── GapDetector.kt                  # Sensor data gap detection
│   │   ├── SensorRecordingRepository.kt
│   │   ├── SensorRecordingRepositoryImpl.kt
│   │   └── model/
│   │       └── RecordingSession.kt
│   ├── repository/
│   │   ├── ConnectionRepository.kt
│   │   ├── RecordingRepository.kt
│   │   ├── SudsRepository.kt
│   │   └── TestRepository.kt
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
│   │   └── fibion/
│   │       ├── FibionFlashManager.kt        # Fibion Flash interface
│   │       ├── FibionFlashManagerImpl.kt    # Fibion Flash implementation (Movesense MDS)
│   │       ├── FibionFlashEvent.kt
│   │       └── model/
│   │           └── FibionFlashData.kt
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
│   │   ├── HeartRateDeviceSelectionDialog.kt
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
│       │   ├── fibion/
│       │   │   └── flash/
│       │   │       ├── FibionFlashScreen.kt
│       │   │       └── FibionFlashViewModel.kt
│       │   └── mindfield/
│       │       ├── pulse/
│       │       │   ├── EsensePulseScreen.kt
│       │       │   └── EsensePulseViewModel.kt
│       │       └── respiration/
│       │           ├── EsenseRespirationScreen.kt
│       │           └── EsenseRespirationViewModel.kt
│       ├── tests/
│       │   ├── RecordingUiState.kt
│       │   ├── TestControlScreen.kt
│       │   ├── TestControlViewModel.kt
│       │   ├── TestDetailScreen.kt
│       │   ├── TestDetailViewModel.kt
│       │   ├── TestsScreen.kt
│       │   ├── TestsViewModel.kt
│       │   └── components/
│       │       ├── ActiveTestBanner.kt
│       │       ├── DeviceSensorGroup.kt
│       │       ├── LiveSensorCard.kt
│       │       ├── SensorSummaryCard.kt
│       │       └── TestNotesField.kt
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

## Navigation Routes

| Route | Screen | Description |
|-------|--------|-------------|
| `tutorial` | TutorialScreen | First-launch onboarding |
| `home` | HomeScreen | Main dashboard with navigation cards |
| `vr_control` | VRConnectionScreen | VR headset connection (manual IP or mDNS discovery) |
| `sensors` | SensorsScreen | List of available sensors |
| `sensors/{sensorId}` | SensorDetailScreen | Router to vendor-specific sensor screen |
| `tests` | TestsScreen | List of therapy tests |
| `tests/active/{testId}` | TestControlScreen | Active test control panel |
| `tests/review/{testId}` | TestDetailScreen | Test review with timeline chart and CSV export |

## Database Schema

Room database (version 1) with 4 entities:

| Entity | Table | Purpose |
|--------|-------|---------|
| TestEntity | `tests` | Therapy test sessions (status: ACTIVE, COMPLETED, EXPORTED) |
| RecordingEntity | `recordings` | Individual recordings within a test (FK → tests) |
| SensorSampleEntity | `sensor_samples` | Time-series sensor data points (FK → recordings) |
| SudsEventEntity | `suds_events` | Subjective Units of Distress scores (FK → tests) |

**SensorType enum:** HEART_RATE, RESPIRATION, FIBION_HEART_RATE, FIBION_ECG, FIBION_RR_INTERVAL, ESENSE_RR_INTERVAL

## Data Flow

```
Meta Quest VR ◄──WebSocket──► VRWebSocketClient ──► VRConnectionViewModel ──► UI
                               ▲
                      MdnsDiscoveryService (auto-discovery)

eSense Pulse  ◄────BLE──────► BleManager ──────────► EsensePulseViewModel ──► UI
Fibion Flash  ◄────BLE/MDS──► FibionFlashManager ──► FibionFlashViewModel ──► UI
eSense Resp.  ◄────Audio────► MindfieldRespiration ► EsenseRespirationViewModel ► UI

All sensors ──► SensorRecordingRepository ──► Room DB ──► TestExportService ──► JSON/CSV
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

| File | Target | Tests | What it covers |
|------|--------|-------|----------------|
| `data/recording/GapDetectorTest.kt` | `GapDetector.kt` | 18 | Gap detection edge cases: empty input, startup threshold, boundary conditions, mixed sensor types, unsorted input, per-sensor-type routing |
| `data/vr/model/WebSocketMessageTest.kt` | `WebSocketMessage.kt` | 8 | `ServerMessage` JSON serialization: minimal/full/failure decoding, round-trip, malformed JSON, missing fields, unknown fields |
| `data/repository/TestRepositoryTest.kt` | `TestRepository.kt` | 7 | Test lifecycle: creation format (BMX-yyMMdd-HHmmss), sample count aggregation from completed recordings, status transitions, notes persistence, deletion |
| `data/repository/RecordingRepositoryTest.kt` | `RecordingRepository.kt` | 8 | Recording lifecycle: identifier format (BMX-...-R01), sequence auto-increment, sensor flags, sample count aggregation per sensor type, duration/status on complete, batch sample insert |
| `data/export/TestExportMapperTest.kt` | `TestExportMapper.kt` | 14 | Export data transformation: sensor type mapping (6 types), sensor enable/disable flags, Fibion sub-sensor population, gap detection conditional logic, statistics aggregation, SUDS event mapping, recording sample inclusion, test field mapping |
| `data/sensor/audio/MindfieldRespirationTest.kt` | `MindfieldRespiration.kt` | 12 | Zero-crossing breathing rate algorithm (edge cases, accuracy at normal/rapid rates, buffer windowing), signal verification logic (insufficient samples, out-of-range, no movement, valid signal transition) |
| `presentation/screens/vr/VRConnectionViewModelTest.kt` | `VRConnectionViewModel.kt` | 23 | VR connection state machine, command sending with validation, discovery lifecycle, error flow, log management |

Tests mirror the production package structure (e.g., `GapDetectorTest.kt` is in the same package as `GapDetector.kt`). This enables Android Studio's **Ctrl+Shift+T** navigation between production code and its test.
