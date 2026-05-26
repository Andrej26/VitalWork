# Beurer BC 87 — Sensor Reference

## Device Overview

| Property | Value |
|----------|-------|
| Vendor | Beurer GmbH |
| Product | BC 87 |
| Connection | Bluetooth Low Energy (BLE) |
| Measured signals | Systolic blood pressure (mmHg), Diastolic blood pressure (mmHg) |
| Optional signals | Pulse Rate (bpm), Mean Arterial Pressure (mmHg — derived by app) |
| Measurement type | Episodic — one result per patient-initiated measurement |

The Beurer BC 87 is a wrist oscillometric blood pressure monitor. Unlike continuous streaming sensors (eSense Pulse, eSense Respiration), the BC 87 is an **episodic** device: the patient presses OK on the device, a single BP measurement is taken, stored in device memory, and the device then activates BLE and advertises for approximately 30 seconds for the app to retrieve the result.

### Memory Capacity

| Slot | Capacity | Overflow Behaviour |
|------|----------|--------------------|
| M1   | 120 measurements | Circular FIFO — oldest overwritten |
| M2   | 120 measurements | Circular FIFO — oldest overwritten |
| **Total** | **240 measurements** | |

When BLE indications are enabled, the device sends **all stored records** from the active slot, ordered oldest-to-newest. The last indication received is therefore always the most recently taken measurement.

### Device Clock

The BC 87 has an optional internal clock. In practice, the clock is typically not set, so the timestamp fields in the `0x2A35` characteristic are almost always absent. The app does not depend on the device clock.

## Communication Protocol

### Device Identification

Scanning uses a service UUID filter matched to the Bluetooth SIG Blood Pressure Service:

| Filter | Value |
|--------|-------|
| Service UUID | `0x1810` (Blood Pressure Service) |

The device uses a **static BLE address**. Its complete advertisement contains:

| Field | Value |
|-------|-------|
| Device Name | `BC87` |
| Manufacturer ID | `0x0611` (Beurer GmbH) |
| Advertising Interval | 104 ms |
| Advertising Duration | ~30 seconds per measurement |
| Service UUID | `0x1810` |
| Flags | `0x06` (LE General Discoverable, BR/EDR Not Supported) |

**Bonding:** "Just Works" — Android handles the first-connection bond silently with no PIN prompt. All subsequent connections proceed directly.

### GATT Services and Characteristics

| Service | UUID | Type |
|---------|------|------|
| Generic Access | `0x1800` | Primary |
| Generic Attribute | `0x1801` | Primary |
| Device Information | `0x180A` | Primary |
| **Blood Pressure** | **`0x1810`** | Primary |

> **Note:** The BC 87 has **no Battery Service** (`0x180F`). Battery level cannot be read from this device over BLE.

#### Blood Pressure Service (`0x1810`)

| Characteristic | UUID | Properties | Description |
|---------------|------|-----------|-------------|
| Blood Pressure Measurement | `0x2A35` | **Indication** | Primary measurement data |
| Blood Pressure Feature | `0x2A49` | Read | Supported feature flags (static) |
| Record Access Control Point (RACP) | `0x2A52` | Indicate, Write | Stored record retrieval |

**CCCD:** Write `0x0002` (not `0x0001`) to enable indications. Indications require per-packet acknowledgment; notifications do not.

### RACP — Non-Compliant Behaviour (Critical)

The BC 87 exposes the RACP characteristic (`0x2A52`) but does not fully comply with the Bluetooth SIG Blood Pressure Profile specification:

| RACP Opcode | Operation | Spec Status | BC 87 Behaviour |
|-------------|-----------|-------------|----------------|
| `0x01` | Report All Stored Records | Mandatory | **Supported** |
| `0x06` | Report Last Record | Optional | **NOT supported** — returns error `0x03` (Operator Not Supported) |

**Critical finding:** After delivering all records, the BC 87 **does not send** the expected RACP "All Records Reported" success response (`0x06 0x00 0x01 0x01`). It simply disconnects. The app therefore emits the reading inside `onConnectionStateChange(STATE_DISCONNECTED)`, not on an RACP callback.

## Quirks and Known Behaviors

**Device sends ALL stored records, not just the latest**
Each BLE connection triggers the delivery of every record in the active slot (oldest first). The app overwrites `pendingReading` on each `0x2A35` indication; when the device disconnects, the last value written (newest record) is emitted as the result.

**No RACP success response**
The reading is emitted in the `STATE_DISCONNECTED` handler. The RACP success callback path exists in the implementation as a fallback but is never reached in practice with the BC 87.

**MAP always reported as 0 by the device**
Bytes 5–6 of `0x2A35` (Mean Arterial Pressure) always decode to zero. The app ignores this value and calculates MAP using the standard clinical formula: `MAP = (systolic + 2 × diastolic) / 3`.

**Device clock typically not set**
The timestamp fields in `0x2A35` are technically optional per spec. In practice the BC 87's clock is rarely configured, so the timestamp is absent from every packet. The parser advances the offset past the timestamp bytes when the flag bit is set, but the values are never stored. The app uses its own wall-clock (`System.currentTimeMillis()`) as `timestampMs` and the elapsed test time as `elapsedTestMs`.

**30-second advertisement window**
After taking a measurement the device advertises for approximately 30 seconds. If the app misses this window, the patient simply takes another measurement to open a new window. There is no data loss — the reading is still in device memory.

**Scan auto-restart after disconnect**
To stay within Android BLE scan limits (which throttle apps that restart scans too rapidly), the implementation waits 60 seconds before restarting after a disconnect. This is managed internally by `BeurerbC87ManagerImpl` and is transparent to the rest of the app.

**Scanner only runs during an active test**
The scanner is started by `TestControlViewModel` when a test begins and stopped when the test ends. If the BC 87 advertises while no test is active, no connection is made and no data is recorded.

## Data Format

### `0x2A35` Blood Pressure Measurement Byte Layout

```
Byte 0:     FLAGS (bitmask)
              Bit 0: Units — 0 = mmHg, 1 = kPa
              Bit 1: Timestamp present (7 bytes follow: Year×2, Month, Day, Hour, Minute, Second)
              Bit 2: Pulse Rate present (2 bytes follow, IEEE SFLOAT)
              Bit 3: User ID present (1 byte follows — M1 = 0x01, M2 = 0x02)
              Bit 4: Measurement Status present (2 bytes follow)

Bytes 1–2:  Systolic blood pressure        (IEEE SFLOAT, little-endian)
Bytes 3–4:  Diastolic blood pressure       (IEEE SFLOAT, little-endian)
Bytes 5–6:  Mean Arterial Pressure (MAP)   (IEEE SFLOAT, little-endian) ← always 0, ignored

[If Bit 1 set — 7 bytes timestamp, then:]
[If Bit 2 set — 2 bytes pulse rate (IEEE SFLOAT, little-endian)]
[If Bit 3 set — 1 byte user ID]
[If Bit 4 set — 2 bytes measurement status]
```

### IEEE SFLOAT Decoding

IEEE SFLOAT is a 16-bit format (not IEEE 754):
- Top 4 bits (12–15): **signed exponent** (two's complement)
- Bottom 12 bits (0–11): **signed mantissa** (two's complement)
- Value = mantissa × 10^exponent

```kotlin
private fun sfloatToInt(lsb: Byte, msb: Byte): Int? {
    val raw = (lsb.toInt() and 0xFF) or ((msb.toInt() and 0xFF) shl 8)
    var exponent = raw shr 12
    if (exponent > 7) exponent -= 16       // sign-extend 4-bit
    var mantissa = raw and 0x0FFF
    if (mantissa > 0x07FF) mantissa -= 0x1000  // sign-extend 12-bit

    // Reject special values: NaN, NRes, +Inf, -Inf
    if (mantissa == 0x07FF || mantissa == -0x800 || mantissa == 0x07FE || mantissa == -0x7FE) return null

    return (mantissa * 10.0.pow(exponent)).toInt()
}
```

### Example

Real device reading from debug log: `113/79 mmHg · Pulse: 65 bpm`

```
MAP = (113 + 2 × 79) / 3 = 271 / 3 = 90 mmHg
```

### Output

| Property | Value |
|----------|-------|
| Unit | mmHg (the BC 87 always uses mmHg, not kPa) |
| Systolic range | Typical clinical range 60–250 mmHg |
| Diastolic range | Typical clinical range 40–150 mmHg |
| Pulse Rate | Optional; bpm; absent if flag bit 2 is not set |
| Measurement type | Episodic — one discrete result per patient action, not a stream |

## App Implementation

### Key Files

| File | Role |
|------|------|
| [data/sensor/ble/BeurerbC87Manager.kt](../app/src/main/java/com/biometrix/operator/data/sensor/ble/BeurerbC87Manager.kt) | Interface (`Bc87State` sealed class, `startScanning`, `stopScanning`, state and reading flows) |
| [data/sensor/ble/BeurerbC87ManagerImpl.kt](../app/src/main/java/com/biometrix/operator/data/sensor/ble/BeurerbC87ManagerImpl.kt) | Android BLE implementation; singleton |
| [data/model/BloodPressureReading.kt](../app/src/main/java/com/biometrix/operator/data/model/BloodPressureReading.kt) | In-memory model for a parsed reading |
| [data/db/BloodPressureEventEntity.kt](../app/src/main/java/com/biometrix/operator/data/db/BloodPressureEventEntity.kt) | Room entity — `blood_pressure_events` table |
| [data/db/BloodPressureEventDao.kt](../app/src/main/java/com/biometrix/operator/data/db/BloodPressureEventDao.kt) | Room DAO — insert, query by test, count, delete |
| [data/repository/BloodPressureRepository.kt](../app/src/main/java/com/biometrix/operator/data/repository/BloodPressureRepository.kt) | Repository wrapping the DAO |
| [.../beurer/bc87/BeurerBc87ViewModel.kt](../app/src/main/java/com/biometrix/operator/presentation/screens/sensors/beurer/bc87/BeurerBc87ViewModel.kt) | UI state — recent readings (max 10), debug log (max 200 entries) |
| [.../beurer/bc87/BeurerBc87Screen.kt](../app/src/main/java/com/biometrix/operator/presentation/screens/sensors/beurer/bc87/BeurerBc87Screen.kt) | Compose UI — state card, readings, debug log |
| [presentation/log/BleLogEntry.kt](../app/src/main/java/com/biometrix/operator/presentation/log/BleLogEntry.kt) | Shared log entry model (timestamp, message, isError) |

`BeurerbC87ManagerImpl` is a singleton. It survives screen navigation changes; its lifecycle is managed by `TestControlViewModel`.

### Architecture: Test-Scoped vs. Recording-Scoped

The existing sensors (eSense Pulse, eSense Respiration) are **recording-scoped**: they collect data only within the `start_recording` → `stop_recording` VR window.

Blood pressure is different — readings are taken **before** and **after** VR exposure, outside that window. The BC 87 is therefore **test-scoped**:
- Scanner starts when the clinician opens an active test
- Scanner stops when the test is ended
- Readings are stored in a separate `blood_pressure_events` table linked to `testId` (not `recordingId`)
- The VR recording lifecycle has no effect on the BC 87 scanner

### Data Flow

```
BC87 measurement (patient presses OK)
  └─► BeurerbC87ManagerImpl (BLE GATT, singleton)
        └─► logFlow (SharedFlow<Pair<String,Boolean>>) ──► BeurerBc87ViewModel ──► BleDebugLog UI
        └─► readingFlow (SharedFlow<BloodPressureReading>)
              ├─► BeurerBc87ViewModel ──► recent readings UI (max 10)
              └─► TestControlViewModel
                    └─► BloodPressureEventDao.insert()
                          └─► blood_pressure_events (testId)

  (VR recording window — completely independent)
  SensorRecordingRepositoryImpl ──► HR + Respiration samples (recordingId)
```

### Connection State Machine

```
IDLE
  │ startScanning()
  ▼
SCANNING ──(no test active: stopScanning())──► IDLE
  │ BC87 advertisement detected (service UUID 0x1810)
  ▼
CONNECTING
  │ GATT STATE_CONNECTED → discoverServices()
  │ Enable indications on 0x2A35 (BP Measurement)
  │ Enable indications on 0x2A52 (RACP)
  │ Write RACP request: [0x01, 0x01] (Report All Records)
  ▼
RECEIVING ── device sends all stored records as 0x2A35 indications (~400ms)
  │   pendingReading is overwritten on each indication (oldest → newest)
  │
  │ Device disconnects WITHOUT RACP success response
  │   → emit pendingReading (last received = most recent measurement)
  │   → closeGatt(), reset state
  ▼
SCANNING ──(60s anti-throttle delay)──► SCANNING (restarted)
```

**Fallback mode:** If the RACP characteristic (`0x2A52`) is not found during service discovery, the implementation falls back to a 3-second silence timeout — after 3 seconds with no new indications, the last received reading is emitted. In practice the BC 87 always has RACP, so fallback mode is never triggered.

### Database Schema

```sql
CREATE TABLE blood_pressure_events (
    id               INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    testId           INTEGER NOT NULL,         -- FK to tests.id (CASCADE DELETE)
    timestampMs      INTEGER NOT NULL,         -- wall-clock receipt time (ms since epoch)
    elapsedTestMs    INTEGER NOT NULL,         -- ms since test.createdAt
    systolicMmHg     INTEGER NOT NULL,
    diastolicMmHg    INTEGER NOT NULL,
    meanArterialMmHg INTEGER,                  -- calculated: (sys + 2 × dia) / 3
    pulseRateBpm     INTEGER                   -- nullable (absent if flag bit 2 not set)
);
CREATE INDEX index_blood_pressure_events_testId ON blood_pressure_events (testId);
```

`elapsedTestMs` is the number of milliseconds from the moment the test was started to when the reading was received. This is the `+00:46`, `+01:48`-style offset shown in the test detail screen.

**AppDatabase version:** `blood_pressure_events` was added in version 5. `fallbackToDestructiveMigration()` is configured in `AppModule.kt` for the pre-release development period.

### Export Format

#### JSON (`{testIdentifier}_export.json`)

`bloodPressureEvents` appears before `sudsEvents` in the output:

```json
{
  "test": {
    "bloodPressureEvents": [
      {
        "timestampMs": 1742379270000,
        "elapsedTestMs": 46000,
        "systolicMmHg": 114,
        "diastolicMmHg": 71,
        "meanArterialMmHg": 85,
        "pulseRateBpm": 71
      }
    ],
    "sudsEvents": []
  }
}
```

#### CSV (`{testIdentifier}_bp.csv`)

Six columns — no device timestamp column:

```csv
timestamp_ms,elapsed_test_ms,systolic_mmhg,diastolic_mmhg,map_mmhg,pulse_bpm
1742379270000,46000,114,71,85,71
1742379402000,166000,121,76,91,67
```

Written to `Documents/BioMetrix/{testIdentifier}/` alongside the recording CSV files.

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
- The BC 87 only advertises for ~30 seconds after a measurement. If the window was missed, have the patient take another measurement.
- Verify Location Services are enabled on the tablet.
- The device must not already be connected to another host.

**App shows "Receiving" state but no reading appears**
- This is the known RACP non-compliance: the device disconnects without sending a success response. The implementation handles this by emitting the reading in `STATE_DISCONNECTED`. If no reading is emitted, `pendingReading` may have been null — meaning no `0x2A35` indications arrived before disconnect. Check the debug log on the BC 87 sensor screen.

**Reading received but MAP seems wrong**
- MAP is always calculated by the app as `(sys + 2 × dia) / 3`. The device always transmits 0 in the MAP field; this is expected and intentional.

**No reading emitted and device has no records**
- The BC 87 advertises even if its memory is empty (e.g., after a factory reset). In this case RACP returns "No Records" and nothing is emitted. Have the patient take a measurement first.

**Concurrent scanning with eSense Pulse**
- Both sensors can scan simultaneously. They use different scan filters (`0xFF0C` manufacturer ID for eSense Pulse, `0x1810` service UUID for BC 87). Android BLE supports concurrent scans with different filters without conflict.

**First use on a new tablet (device not bonded)**
- Android performs "Just Works" bonding automatically on the first connection. No PIN dialog appears. Subsequent connections proceed immediately.

**Export button disabled despite having BP readings**
- The export button is enabled when `bpEvents.isNotEmpty()` OR `recordings.isNotEmpty()`. If the button is still disabled, verify the test has been ended (readings are only finalized on test completion).

**DB migration during development**
- `fallbackToDestructiveMigration()` is configured in `AppModule.kt`. The `blood_pressure_events` table was introduced in AppDatabase version 5. This setting clears the database on any schema mismatch during active development; it should be removed before production release.
