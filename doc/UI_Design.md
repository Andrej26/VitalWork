# UI Design

## Overview

BioMetrix Operator is a tablet app for researchers conducting VR therapy sessions for claustrophobia treatment. The app orchestrates VR control, sensor data collection, and test management.

## Navigation Pattern

**Hub-and-spoke navigation** — Home screen is the central hub. Each section navigates forward with back buttons to return.

```
                    ┌─────────────────┐
                    │   Home Screen   │
                    │  (Entry Point)  │
                    └────────┬────────┘
                             │
       ┌─────────┬───────────┼───────────┬──────────┐
       │         │           │           │          │
       ▼         ▼           ▼           ▼          ▼
  Tutorial   Sensors    VR Control     Tests
               │                        │
               ▼                   ┌────┴─────┐
          Sensor Detail            ▼          ▼
                             Test Control  Test Review
                             (active)      (completed)
```

## Screens

### 1. Home Screen
Entry point with large navigation cards.

- App title "BioMetrix Operator"
- **Tutorial card** -> Tutorial screen
- **Sensors card** -> Configure sensor connections
- **VR Control card** -> Configure VR headset connection
- **Tests card** -> Start/manage therapy tests

### 2. Tutorial Screen
Guided walkthrough for VR setup and sensor connection.

- Route: `tutorial`
- Triggers VR tutorial events (`trigger_event` with target `NCEvents`, event `StartTutorial`)
- Can navigate to Tests screen after completion

### 3. Sensors Screen
List of supported sensor types.

- Back button -> Home
- **eSense Pulse card** (Heart Rate + R-R, BLE) — shows connection status
- **eSense Respiration card** (Breathing, Audio Jack) — shows connection status
- **Fibion Flash card** (ECG / HR / R-R, BLE) — shows connection status
- **Beurer BC87 card** (Blood Pressure, BLE) — shows connection status
- Tap card -> opens Sensor Detail

### 4. Sensor Detail Screen
Configuration for a specific sensor. Routes to vendor-specific screens based on `sensorId`:

- `esense_pulse` -> EsensePulseScreen (BLE scan, connect, HR + R-R monitoring)
- `esense_respiration` -> EsenseRespirationScreen (audio connection, breathing rate)
- `fibion_flash` -> FibionFlashScreen (BLE scan, connect, ECG + HR + R-R monitoring)
- `beurer_bc87` -> BeurerBc87Screen (BLE scan, blood pressure readings)

### 5. VR Control Screen
Configure and test VR headset connection.

- Back button -> Home
- **mDNS discovery** — auto-discovers VR headsets on the local network (`_narrowingchamber._tcp.`)
- Manual IP address input as fallback
- Port display (:9090)
- Connect/Disconnect button
- Connection status indicator
- VR commands: Load scene (StressChamber), Trigger events, Reload scene
- Command log showing sent/received messages

### 6. Tests Screen (List)
Browse all tests.

- Back button -> Home
- **Filter chips row:** All | In Progress | Completed | Exported
- Test count display
- List of tests with: Test ID, date, duration, status badge (Active, Completed, Exported)
- Tap to open -> Test Review (completed) or Test Control (active)
- **"New Test" FAB button** -> Test Control (new)

### 7. Test Control Screen
Main operational screen during active therapy. This is where researchers spend most time.

- Back button -> Tests list
- **Recording panel** (prominent, top):
  - Two states: IDLE and RECORDING
  - VR events (`start_recording` / `stop_recording`) auto-trigger recording transitions
  - Duration counter during recording
- **Connection Status section:**
  - VR Headset status (mDNS-discovered or manual IP)
  - eSense Pulse status
  - eSense Respiration status
  - Fibion Flash status
  - Beurer BC87 status (scanner active during test)
- **VR Controls:**
  - Tutorial button (trigger_event)
  - StressChamber button (load scene)
  - Disabled when VR not connected
- **SUDs tracking** — receives SUDs values from VR events during StressChamber
- **Notes** — auto-saved with 500ms debounce
- **End Test** -> navigates to Test Review

### 8. Test Review Screen
View completed test data and export.

- Route: `tests/review/{testId}`
- Test summary: duration, sample counts per sensor, recording count
- Timeline chart (HR and respiration data)
- Blood pressure readings
- SUDs events
- Export button (CSV + JSON)
- Delete button

## Key UI Components

### Recording Panel
Most important visual element — must be immediately visible.

| State | Color | Display |
|-------|-------|---------|
| IDLE | Gray | "IDLE" |
| RECORDING | Red + animation | "RECORDING" + duration counter |

Recording is auto-triggered by VR biofeedback events. Manual start/stop is also available.

### Connection Status Badge
Reusable for VR and all sensors:

| State | Color | Visual |
|-------|-------|--------|
| Disconnected | Gray | filled circle |
| Connecting | Amber | spinner |
| Connected | Green | filled circle |
| Error | Red | filled circle + "Retry" button |

### Navigation Card
Large touch-friendly cards for Home screen: Icon (40dp) + Title + Description.

### Sensor Type Card
For Sensors list: Icon + Name + Connection status badge + Description.

## Design Decisions

1. **Hub-and-spoke navigation** — simple, clear flow. No persistent navigation bar.
2. **Tests list is main entry** — click test or "New Test" to open control screen.
3. **Recording panel always visible** in Test Control screen.
4. **Large touch targets** — app used on tablet during clinical sessions.
5. **Status always visible** — researchers need immediate feedback on connections.
6. **Two recording states** — IDLE/RECORDING, auto-triggered by VR events. Prevents operator confusion.
7. **Inline error recovery** — "Retry" buttons appear in ERROR state for quick reconnection.
8. **Test filtering** — filter chips reduce cognitive load when managing many tests.

## File Structure

```
presentation/
├── navigation/
│   └── AppNavigation.kt              # Routes and NavHost
├── components/
│   ├── BioSensorCard.kt              # Sensor info card
│   ├── BleDialogTypes.kt             # BLE dialog models
│   ├── ConnectionStatusBadge.kt      # Reusable status indicator
│   ├── LowSignalWarningBanner.kt     # Respiration low-signal warning
│   ├── NavigationCard.kt             # Large nav card for Home
│   ├── RecordingIndicator.kt         # Pulsing recording indicator
│   ├── RecordingPanel.kt             # Recording state panel
│   └── SensorTypeCard.kt             # Sensor list item
├── log/
│   ├── BleLogEntry.kt                # BLE debug log entry
│   └── LogEntry.kt                   # General log entry
└── screens/
    ├── home/
    │   ├── HomeScreen.kt
    │   └── HomeViewModel.kt
    ├── tutorial/
    │   ├── TutorialScreen.kt
    │   └── TutorialViewModel.kt
    ├── sensors/
    │   ├── SensorsScreen.kt
    │   ├── SensorsViewModel.kt
    │   ├── SensorDetailScreen.kt      # Router to vendor-specific screens
    │   ├── components/                # Shared sensor UI components
    │   ├── mindfield/pulse/           # eSense Pulse (HR + R-R)
    │   ├── mindfield/respiration/     # eSense Respiration
    │   ├── fibion/flash/              # Fibion Flash (ECG + HR + R-R)
    │   └── beurer/bc87/              # Beurer BC87 (Blood Pressure)
    ├── vr/
    │   ├── VRConnectionScreen.kt
    │   └── VRConnectionViewModel.kt
    └── tests/
        ├── TestsScreen.kt            # Test list
        ├── TestsViewModel.kt
        ├── TestControlScreen.kt       # Active test control
        ├── TestControlViewModel.kt
        ├── TestDetailScreen.kt        # Test review/detail
        ├── TestDetailViewModel.kt
        ├── RecordingUiState.kt
        └── components/               # Test-specific UI components
```
