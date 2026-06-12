# Samsung Galaxy Watch 8 — Sensor Reference

## Device Overview

| Property | Value |
|----------|-------|
| Vendor | Samsung |
| Product | Galaxy Watch 8 (Wear OS 6 / One UI Watch) |
| SDK | Samsung Health Sensor SDK `samsung-health-sensor-api-1.4.1.aar` |
| Connection | **Indirect** — companion app on the watch streams to the tablet over the Wearable Data Layer (Bluetooth, no internet) |
| Measured signals | Heart Rate (BPM), Inter-Beat Interval / IBI (ms), Electrodermal Activity / EDA (µS) |
| Additional data | Battery level (%), supported-tracker capability list |
| Optional (off by default) | PPG (25 Hz), SpO₂ (on-demand), Skin Temperature (1 Hz) |

> **Why this is different from eSense Pulse/Respiration.** The Watch 8 exposes **no standard
> Bluetooth Heart Rate Profile** and **no BLE GATT** for its sensors. EDA in particular is
> **only** reachable through Samsung's proprietary Health Sensor SDK, which runs **on the watch**.
> There is therefore no way for the tablet to read the watch directly the way `BleManager` reads
> the eSense Pulse. The integration is a **two-app design**: a thin Wear OS "sensor faucet" app
> reads the sensors on the watch and forwards each reading to the tablet over the Wearable Data
> Layer. This lives in the same Gradle project as a second module, `:wear`.

> **Status: store-and-forward implemented (2026-06).** The watch no longer only *streams* — it also
> **persists every HR/IBI/EDA reading to its own durable file store** as it samples, so a screen-off/
> Doze session loses nothing. At session end the phone sends a **remote FLUSH command** and the watch
> ships its stored rows back as `DataClient` DataItems (which buffer and auto-sync across a brief
> disconnect). EDA/HR/IBI are recorded into the DB and appear in the session export. See
> **Store-and-Forward + Remote Flush** below. The original live-only path still runs for the
> diagnostic display; the rest of this doc's platform rationale (transport, Doze, cloud relay) is
> unchanged and still load-bearing.

## Architecture

```
┌─────────────────────── Galaxy Watch 8 (:wear module) ───────────────────────┐
│  Samsung Health Sensor SDK (HealthTrackingService)                           │
│    └─► HEART_RATE_CONTINUOUS tracker  → HR + IBI (one HeartRateSet)           │
│    └─► EDA_CONTINUOUS tracker         → skin conductance                      │
│         │                                                                     │
│  WatchSensorService (foreground service, type=health)                        │
│    • owns the SDK lifecycle, registers trackers                               │
│    • emit(): persists HR/IBI/EDA to WatchSampleStore AND streams live          │
│    • flush() loop @1 Hz forces screen-off batches out immediately             │
│    • HEARTBEAT @~30 s ("alive, just dozing")                                  │
│    └─► WatchDataSender (MessageClient, live readings)                         │
│  WatchSampleStore (append-only JSON-lines file; truncate-after-ack)           │
│  WatchCommandListenerService (receives START/FLUSH/STOP/FLUSH_ACK)            │
│    └─► WatchFlushWriter (DataClient DataItems = reliable bulk transfer)       │
└──────────────┬───────────────────────────────────────────┬──────────────────┘
       live ▲  │ readings/heartbeat (MessageClient)          │ flush (DataClient DataItems)
            │  ▼  "/biometrix/sensors"                        ▼  "/biometrix/flush"
        commands ▲ "/biometrix/command" (MessageClient: START/FLUSH/STOP/FLUSH_ACK)
                 │  Wearable Data Layer — Bluetooth-direct *if phone BT is on* (else cloud relay, dies in Doze!)
                 ▼
┌─────────────────────────── Android Tablet (:app module) ─────────────────────┐
│  WatchListenerService : WearableListenerService (auto-started on event)       │
│    • onMessageReceived → parse JSON line(s) (live + HEARTBEAT)                │
│    • onDataChanged    → ingest flushed DataItems, delete (=ack), FLUSH_ACK    │
│    └─► WatchSensorReceiver (Hilt @Singleton)                                  │
│          • latestByType / availableTrackers / batteryLevel                   │
│          • connectionState (coarse) + linkStatus (LIVE/DOZING/DISCONNECTED)   │
│          • watchSampleFlow (EDA+HR+IBI) + onFlushedReadings (historical)      │
│  WatchCommandSender (MessageClient → watch: START/FLUSH/STOP/FLUSH_ACK)       │
│                │                                                               │
│   ┌────────────┼─────────────────────┬──────────────────────┐                │
│   ▼            ▼                     ▼                      ▼                │
│ Connection  WatchSensorViewModel  ScenarioRecording   SessionControlVM        │
│ Repository  ──► WatchSensorScreen  RepositoryImpl      (end: FLUSH + drain)   │
│             ("dozing — buffering") (buffer→drainer→DB)                        │
└───────────────────────────────────────────────────────────────────────────────┘
```

## Transport: Wearable Data Layer (MessageClient)

The watch↔tablet link uses **`MessageClient`**, not a socket, not BLE GATT, not the VR WebSocket.

| Property | Value |
|----------|-------|
| API | `MessageClient.sendMessage(nodeId: String, path: String, data: ByteArray): Task<Int>` |
| Path | `/biometrix/sensors` |
| Node discovery | `CapabilityClient.getCapability("biometrix_phone", FILTER_REACHABLE)` |
| Delivery | Best-effort, fire-and-forget (a dropped message loses one sample) |
| Cost | Free — Google Play Services is a system component; no internet required |

**Why MessageClient and not ChannelClient.** An earlier version used a `ChannelClient` byte stream.
It froze: any transient write hiccup nulled the `OutputStream` and every later write silently
no-op'd with no reconnect. HR/EDA are only ~1 Hz, so a persistent stream buys nothing. MessageClient
is **stateless** — each reading is an independent message, nothing to stall, nothing to reconnect.

**Why the phone declares a "capability."** The watch must locate *this specific tablet's* node.
The tablet advertises a capability named `biometrix_phone` in `app/src/main/res/values/wear.xml`
(`android_wear_capabilities`); the watch resolves it via `CapabilityClient` and caches the node id.
On send failure the cached id is cleared so the next send re-resolves.

> **CRITICAL — "Bluetooth-direct" is the *best case*, not a guarantee (verified on-device).** The
> Data Layer has two transports between the same two nodes: a **direct Bluetooth** link (the node is
> `isNearby == true`) and a **Google cloud relay** (a non-nearby node, routed via Wi-Fi/internet —
> visible as `CloudNode` RPC traffic in logcat). **If the phone's Bluetooth is OFF, there is no
> nearby node and the link silently falls back to the cloud relay.** The cloud relay only delivers
> while *both* devices are awake; the instant the phone's screen turns off / it Dozes, cloud delivery
> stalls and **the phone receives nothing** — the watch keeps sending fine. This presents exactly as
> "the connection drops every time the phone sleeps." It is a **transport** problem, not a Doze-policy
> or wake-lock problem — disabling Doze on the phone does **not** fix it; turning the phone's
> Bluetooth on does.
>
> Two compounding factors made this sticky:
> 1. `WatchDataSender.resolveNodeId()` picked `firstOrNull { isNearby } ?: firstOrNull()` — i.e. it
>    **fell back to the cloud node** when no nearby node existed, and cached it.
> 2. The cache is only cleared on a send **failure**, but cloud sends report **success** (queued to
>    the relay), so once latched onto the cloud node it never re-resolved to Bluetooth even after BT
>    came back — a watch Stop→Start was needed to force re-resolution.
>
> Verify which transport is live:
> ```bash
> # On the WATCH: is there a nearby (Bluetooth) node, or only cloud?
> adb -s <watch> shell dumpsys activity service WearableService | grep -E "Nearby node ID|connected out of|Status:"
> #   "Nearby node ID: null" + "Status: DISCONNECTED"  → running over the cloud relay (will die in Doze)
> # On the PHONE: is Bluetooth even on?
> adb -s <phone> shell dumpsys bluetooth_manager | grep -E "^  state:"
> ```
> The phone surfaces a **"Bluetooth Disabled" warning card** on Sensors → Galaxy Watch when its
> adapter is off (see UI section), mirroring eSense Pulse, so the operator is told to turn it on.

**Why the listener is a `WearableListenerService`.** Declared with a `MESSAGE_RECEIVED`
intent-filter, it **auto-starts the tablet app on a matching message even if the app isn't
running** — so the Hilt singleton receiver is created on demand and data is captured without the
user opening the watch screen.

### Bluetooth-direct vs. cloud relay — why this is not a "Wi-Fi or Bluetooth" choice

It is tempting to think of the link as choosing between *Bluetooth* and *Wi-Fi*. It isn't. Both
transports are the **same** Wearable Data Layer; what varies is the route the Data Layer picks
underneath, and that is **not configurable** — it is decided by whether a *nearby* node exists. The
"Wi-Fi" path is **not** a clean local socket between watch and tablet — it is **Google's cloud
relay routed over the internet**. That distinction is what makes Bluetooth-direct the only viable
choice for this project:

| Criterion | Bluetooth-direct (nearby node) | Cloud relay (over Wi-Fi/internet) |
|-----------|--------------------------------|-----------------------------------|
| **Stability** | Drops only if out of radio range | **Cannot deliver to a dozing/screen-off phone** — stalls the instant the phone Dozes (see CRITICAL note above) |
| **Latency** | Low, local radio hop | Higher — round-trips through Google's servers |
| **System requirements** | None — no Wi-Fi, router, or internet; just BT on + BT-bonded via Galaxy Wearable | Internet on **both** devices, both signed into the same Google account |
| **Privacy** | Physiological data (HR/IBI/**EDA**) never leaves the two devices | Data is **routed through Google's servers** — a real consideration for a clinical/research study |
| **Battery (radio)** | Low (BLE-class radio) | Higher (Wi-Fi radio + internet round-trips) **and** still fails in Doze |

**Conclusion:** Bluetooth-direct wins on every axis, decisively on stability. Note the dominant
battery cost of this system is **not** the transport radio but the screen-off 1 Hz `flush()` loop
holding the AP busy (the open item in *The Screen-Off Problem*) — that cost is the same regardless
of which transport the Data Layer picks.

## Message Format

Newline-free single-object JSON, hand-built (no serializer) to keep the payload tiny. The tablet
parses the same shape.

```jsonc
// One reading:
{"t":1717245600000,"type":"WATCH_HR","value":72.0,"accuracy":0}
{"t":1717245600000,"type":"WATCH_EDA","value":50.5,"accuracy":0}
{"t":1717245600000,"type":"WATCH_IBI","value":833.0,"accuracy":0}
{"t":1717245600000,"type":"BATTERY","value":86.0,"accuracy":0}

// Capability list (resent ~5× early so a single drop isn't permanent):
{"t":1717245600000,"type":"CAPABILITIES","text":"HEART_RATE_CONTINUOUS,EDA_CONTINUOUS"}

// Several 1 Hz samples from one screen-off SDK callback, bundled into one message:
{"type":"BATCH","items":[ {…reading…}, {…reading…} ]}

// Explicit "tracking stopped" notice (instant DISCONNECT on the phone):
{"t":1717245600000,"type":"STOP"}

// Low-rate "alive, just dozing" beacon (~30 s) → phone shows DOZING, not DISCONNECTED:
{"t":1717245600000,"type":"HEARTBEAT"}
```

`t` is stamped on the watch with `System.currentTimeMillis()` — same clock convention as the rest of
the app, so watch samples align to VR events with no clock-sync step.

**Phone → watch commands** ride a separate `MessageClient` path `/biometrix/command` as plain strings:
`START`, `STOP`, `FLUSH`, and `FLUSH_ACK:<maxTimestampMs>`. **Bulk flush data does NOT use
`MessageClient`** — it uses `DataClient` DataItems on `/biometrix/flush/<batchId>/<chunk>` (see below).

## Store-and-Forward + Remote Flush (2026-06)

**Problem it solves.** Live streaming alone is fragile across screen-off/Doze: the `MessageClient`
feed stalls (cloud relay) or delivers in late bursts (Doze), and a dropped message loses that sample
outright. For a research dataset that is unacceptable. So the watch now **persists every reading
locally** and the phone **pulls the complete history** at session end.

**Watch side:**
- `WatchSampleStore` — an **append-only JSON-lines file** in the watch app's `filesDir` (same line
  format as the live messages). Every HR/IBI/EDA reading is appended in `WatchSensorService.emit()`
  (the single chokepoint that already status-gates HR/IBI), *in addition* to streaming live. Battery
  and heartbeat are **not** persisted. The write is inside the SDK `onDataReceived` callback, so it
  does **not** depend on the 1 Hz flush loop staying scheduled — it survives Doze. The store is
  cleared on a fresh `START`.
- `WatchCommandListenerService : WearableListenerService` (path `/biometrix/command`) handles phone
  commands. **`FLUSH` does its work inline (no foreground service)** — the listener is already alive
  for the callback. **Remote `START` does need the FGS**, which is only legal from this
  background-delivered callback via the **Companion exemption**
  `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` (declared in the `:wear` manifest);
  without it, `startForegroundService` throws `ForegroundServiceStartNotAllowedException`.
- `WatchFlushWriter` — writes the store's rows to the phone as **`DataClient` DataItems**, chunked
  (~300 rows/item, each well under the ~100 KB DataItem cap) on `/biometrix/flush/<batchId>/<chunk>`.
  `DataClient` (not `MessageClient`) is used for bulk because it **buffers while disconnected and
  auto-syncs on reconnect**, and starts the phone's listener even if the app wasn't running.

**Phone side:**
- `WatchCommandSender` (interface + `…Impl`, bound in `AppBindsModule`) resolves the watch node via
  the `biometrix_watch` capability (advertised in `wear/src/main/res/values/wear.xml`) and sends the
  command strings.
- `WatchListenerService.onDataChanged` ingests flushed DataItems → `WatchSensorReceiver.onFlushedReadings`
  (buffered **losslessly** in an unbounded list — NOT the bounded live `watchSampleFlow`, whose 256-slot
  DROP_OLDEST would silently discard all but the newest ~256 of a multi-thousand-row session flush and
  collapse everything into the last scenario; the drain pulls them via `takeFlushedReadings()` and
  corrects them onto the phone clock), reports chunk progress via
  `WatchSensorReceiver.onFlushChunk(batchId,index,count,maxTs)`, then **deletes each DataItem**
  (Data-Layer cleanup only — it does NOT touch the watch's durable store). **It no longer sends
  `FLUSH_ACK` here.** The ack (which truncates the store) is sent by the session-end flow *after* the
  rows are persisted, so a slow/partial flush can never destroy data the phone hasn't saved. The
  per-(scenario,type) high-water-mark de-dup (readings) + per-index chunk set (progress) make
  re-delivery idempotent. **Lossless by construction.**

**Flush handshake (`FLUSH_COMPLETE`):** after dispatching the DataItem chunks, the watch
(`WatchCommandListenerService.flushStore`) sends a `MessageClient` line
`{"type":"FLUSH_COMPLETE","batchId","chunkCount","rowCount"}` (`WatchMessage.flushComplete`). The phone
(`WatchSensorReceiver.onFlushComplete`) uses it to know exactly how many chunks to expect — so an empty
store (`chunkCount==0`) completes instantly and a real flush completes once all chunks are in, driving
`WatchSensorReceiver.flushState` (`Idle → InProgress(received,expected) → Complete(rows,maxWatchTs)`).

**Session end** (`SessionControlViewModel`, `EndSessionPhase` state machine): stop recording (closes
the last scenario window) → if the watch is dozing/gone, show **"Wake your watch"** and wait until it
is `LIVE` (or the operator picks "End without watch data") → `beginWatchFlush()` + send `FLUSH` and show
the **"Receiving data from watch…"** reconnecting-style spinner while `flushState` advances → on
`Complete`, drain the session buffer into per-scenario rows by timestamp window, **then** send
`FLUSH_ACK:<maxWatchTs>` (truncate the store), end the session, show the **green check**, and navigate.
Bounded by `WATCH_WAKE_TIMEOUT_MS` / `WATCH_TRANSFER_TIMEOUT_MS` → `EndSessionPhase.Failed` (Retry /
End-without-watch-data), so it never hangs. **Auto-wake is best-effort**; the manual tap is the
dependable trigger and **no data is lost** because the store is only truncated after a successful save.

**Reliability ceiling (verified research, recorded so it isn't re-litigated):** there is **no
documented guarantee** the Data Layer wakes a *deeply* dozing watch. Google's own deep-Doze wake is
high-priority FCM — rejected here because it (a) only escapes throttling with a *user-visible*
notification (a silent flush is exactly what FCM deprioritizes) and (b) needs an internet/Firebase
path, contradicting this app's no-internet Bluetooth-direct design. Wi-Fi as the link was also
rejected (Doze turns the Wi-Fi radio off — worse, not better).

## Sensor Data Specifics

### Heart Rate + IBI (one tracker)

`HEART_RATE_CONTINUOUS` delivers **both** HR and IBI in a single `HeartRateSet` — do **not** register
two trackers. Read via:

| Value | Key | Notes |
|-------|-----|-------|
| HR (BPM) | `ValueKey.HeartRateSet.HEART_RATE` | gated by `HEART_RATE_STATUS` |
| IBI (ms) | `ValueKey.HeartRateSet.IBI_LIST` | a **list** per data point |
| IBI status | `ValueKey.HeartRateSet.IBI_STATUS_LIST` | parallel list |

**Gotcha (Samsung spec):** IBI values for the whole tracking interval are packed into the IBI
**list**; the parser must **iterate the list**, not assume one IBI per callback. A value is valid
only when `ibiStatus == 0 && ibiValue != 0` — invalid entries are discarded.

### EDA (Electrodermal Activity / skin conductance)

| Value | Key |
|-------|-----|
| Skin conductance (µS) | `ValueKey.EdaSet.SKIN_CONDUCTANCE` |
| Status | `ValueKey.EdaSet.STATUS` |

EDA is the watch's stress signal and the primary reason this SDK was chosen — it is **not**
obtainable through the standard Android `SensorManager`.

### Sampling rates

| Signal | Native rate |
|--------|-------------|
| Heart Rate | 1 Hz |
| EDA | 1 Hz |
| Skin Temperature | 1 Hz |
| PPG | 25 Hz (optional) |
| Accelerometer | 25 Hz (optional) |
| SpO₂ | on-demand only |

1 Hz is the **native** delivery rate for HR + EDA — "send every second" is not throttling, it is the
sensor's own cadence.

## The Screen-Off Problem (the hard part — read this first if reusing)

**Requirement:** continuous ~1 Hz HR + EDA with the **screen off**, wrist down, watch dozing, for
hour-long back-to-back sessions, so the data can be compared 1:1 against a wired MindField sensor.

**What goes wrong by default.** When the watch screen blanks it enters `mWakefulness=Dozing` and the
application processor (AP) suspends. Two things then stall:
1. The SDK keeps sampling at 1 Hz but **buffers** ~10 s and flushes the whole batch in one callback
   when the AP next wakes (verified: an 11-sample burst after ~11 s of silence).
2. If the app lacks background-sensor permission, the SDK **stops delivering entirely** once
   backgrounded.

### What did NOT work — a wake lock cannot defeat Wear Doze (verified on-device twice; do not repeat)

A `PARTIAL_WAKE_LOCK` to keep the AP awake so the flush loop keeps ticking. **This does not work on
the Galaxy Watch 8 — it is a Wear OS platform limit, not a code bug.** Measured (2026-06):

- While the watch is **Awake**, the lock is honored: `dumpsys power` shows
  `PARTIAL_WAKE_LOCK 'biometrix:watchsensors' ACQ=…`.
- The instant `mWakefulness=Dozing` (both natural screen-off **and** forced `deviceidle`), the same
  lock flips to **`DISABLED`** — the OEM power HAL suppresses it. The AP then suspends, the flush
  coroutine's `delay()` freezes, and the phone receives nothing until the next maintenance wake
  (measured gaps of ~27 s, growing as Doze deepens).

This matches Google IssueTracker **#228086086 "Partial Wake Lock disabled by Doze in android Wear"**
— wake locks are deliberately disabled by Wear Doze. The earlier `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
+ `deviceidle whitelist` attempt failed for the same root reason. **There is no app-side lever that
keeps the AP awake through Wear Doze; the wake-lock code was removed.** (The Samsung blog *"Continuous
Heart Rate Tracking … Even with the Screen Off"*, 2026-04-23, that suggests a wake lock was validated
only on **Watch 5/6/7** via the generic `SensorManager` path — not the Watch 8 / Health Sensor SDK /
EDA path used here.)

### What this means: the data is COMPLETE, only LIVE delivery is delayed (the key insight)

Crucially, **the sensors keep sampling at a gapless 1 Hz on the watch the whole time it dozes** —
measured: EDA `onSensorDataReceived` timestamps exactly 1000 ms apart through `Dozing`, HR + IBI the
same. Doze suspends **delivery to the phone**, not **sampling**. Because every reading carries its own
`System.currentTimeMillis()` stamp, the burst-delivered batch reconstructs a **complete, correctly
ordered 1 Hz timeline** on the phone. So for **recording into the DB**, screen-off bursts lose no data
— only the *live preview* lags. This is acceptable and is the chosen model (Phase-1 decision 2026-06).

### What actually works (three load-bearing pieces — for SCREEN-ON continuity + screen-off completeness)

| # | Piece | Why it's required |
|---|-------|-------------------|
| 1 | Foreground service, **type `health`**, FGS type **asserted at runtime** via `ServiceCompat.startForeground(this, id, notif, FOREGROUND_SERVICE_TYPE_HEALTH)` | Keeps the **process** alive & resident. Without the runtime assertion on API 34+, the OS demotes the process to *cached* when the launching Activity leaves TOP, then `START_STICKY` recreates it — connect→unbind churn every ~2–10 s. |
| 2 | **`BODY_SENSORS_BACKGROUND`** permission (API 31+), granted separately (settings-routed) | Without it the SDK **stops delivering sensor data** once backgrounded / dozing. This is what stopped the *total* shutoff (vs. mere batching). |
| 3 | **`HealthTracker.flush()` on a `FLUSH_INTERVAL_MS` loop** while tracking | **Screen ON:** forces the SDK's buffer out each interval → continuous 1 Hz live feed. **Screen OFF in Doze:** the loop's `delay()` freezes with the AP, so this has no effect until the next maintenance wake — that is the platform limit above, not a bug. |

**Measured result (screen ON):** continuous ~1 Hz, no gap > ~1.4 s. **Screen OFF in Doze:** bursts
on each maintenance wake (gap grows over time), every sample timestamp-correct → complete recorded
data, lagged live preview.

> **For true continuous LIVE screen-off delivery (incl. EDA) the only mechanism is to prevent Doze by
> keeping the watch display ON during sessions** (charging/tethered). Health Services' MCU path keeps
> HR screen-off without a wake lock but exposes **no EDA**, so it cannot carry this study's full signal
> set. Not implemented; recorded-data completeness (above) was deemed sufficient for Phase 1.

> **Recording phase — edge-safety (now implemented, see Store-and-Forward).** Screen-off bursts mean a
> batch may land *after* the operator hits Stop, or a gap may straddle Start. Both are handled: (a)
> readings are **persisted on the watch** and pulled at session end via remote FLUSH, so nothing is
> lost to an unlucky record-window edge; and (b) `WatchSessionDrainer` attributes every reading to a
> scenario by **its own corrected `t`** (timestamp-window), never by arrival order — with per-(scenario,
> type) high-water marks so live-written and flushed rows never double up. Verified on-device 2026-06: a
> ~38 s recording with the screen off after ~10 s produced a **gapless 1 Hz EDA + HR + IBI** timeline.

> Open item: **battery cost** of the screen-on (no-Doze) mode for live sessions is unmeasured; quantify
> with `dumpsys batterystats` over a ~15 min mock session before relying on long untethered sessions.

## Connection State (inferred — there is no "connection" object)

Because the transport is stateless fire-and-forget, the tablet has **no event** telling it the watch
dropped. State is **inferred** in `WatchSensorReceiver`:

- Every message updates a `@Volatile lastMessageMs` and sets `CONNECTED`.
- A single poll loop (`POLL_INTERVAL_MS = 1 s`) sets `DISCONNECTED` if
  `now - lastMessageMs > INACTIVITY_TIMEOUT_MS` (**6 s** — headroom over the 1 Hz cadence so an
  occasional 1–2 s gap doesn't flap the state). One poll loop watching a volatile timestamp is
  race-free vs. rearming a debounce job from a binder thread. (Note: this only smooths brief gaps;
  it does **not** mask screen-off Doze bursts, whose gaps grow well past 6 s — see *Screen-Off Problem*.)
- A normal **Stop is signalled explicitly**: the watch sends `{"type":"STOP"}` before teardown, and
  `onStop()` flips to `DISCONNECTED` **instantly** (no watchdog wait). The watchdog is only the
  safety net for the abnormal case — watch dies / out of range — where no goodbye can be sent.

```
DISCONNECTED
  │ first reading arrives
  ▼
LIVE ◄─── reading within 6 s ───┐
  │                              │
  │ no reading > 6 s, but a      │ reading arrives again
  │ HEARTBEAT within ~95 s       │
  ▼                              │
DOZING (buffering) ─────────────┘
  │ no reading AND no heartbeat > ~95 s   │  (STOP message)
  ▼                                       ▼
DISCONNECTED  ◄───────────────────────────  (instant, normal Stop)
```

`WatchSensorReceiver` exposes both a coarse `connectionState` (CONNECTED while LIVE *or* DOZING;
DISCONNECTED only when truly gone — so existing consumers treat a dozing watch as present) and the
finer `linkStatus { LIVE, DOZING, DISCONNECTED }`. The UI renders DOZING as **"Watch dozing —
buffering"** so an expected screen-off gap doesn't look like a fault. `lastReadingMs` drives LIVE;
`lastMessageMs` (any message, incl. HEARTBEAT) drives DOZING-vs-gone.

## Watch-Side Service Lifecycle (gotchas)

The single biggest source of bugs was the foreground service being recreated, churning the SDK
connection. The fixes, all in `WatchSensorService`:

1. **Assert the FGS type at promotion** (see table above) — not the 2-arg `startForeground`.
2. **Promote first, on every non-STOP delivery** — including null-action sticky restarts — within
   ~5 s, or the OS throws `ForegroundServiceDidNotStartInTimeException`.
3. **Idempotency on the real instance:** guard SDK init on `trackingService != null` (an instance
   field), **never** on the static `isTracking` StateFlow (which is UI state and can be out of sync
   after recreation). This is what stops the connect→register→unbind churn.
4. `onStartCommand` returns **`START_REDELIVER_INTENT`** so restarts re-deliver the real
   `ACTION_START` (non-null) and hit the guard cleanly.

## Required Permissions (watch / `:wear`)

```xml
<uses-feature android:name="android.hardware.type.watch" />

<!-- Wear OS 6 / API 36+ replaced BODY_SENSORS with the health.* runtime permissions -->
<uses-permission android:name="android.permission.BODY_SENSORS" android:maxSdkVersion="35" />
<uses-permission android:name="android.permission.BODY_SENSORS_BACKGROUND" />   <!-- load-bearing -->
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.health.READ_HEART_RATE" />     <!-- API 36+ -->
<uses-permission android:name="com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA" /> <!-- EDA/ECG/PPG/BIA -->

<!-- Foreground service (health) to keep tracking with the screen off -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Lets the phone remotely START the foreground health service from the (background-delivered)
     command listener. Receiving a Data Layer message is NOT itself an FGS-from-background exemption
     (API 31+), so this Companion exemption is required or remote START throws
     ForegroundServiceStartNotAllowedException. -->
<uses-permission android:name="android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND" />
```

Service declarations:
```xml
<service android:name=".WatchSensorService" android:exported="false"
         android:foregroundServiceType="health" />

<!-- Receives phone → watch commands; exported + MESSAGE_RECEIVED so the system can deliver (and
     launch the app) even when the watch app is backgrounded — the mechanism behind remote wake. -->
<service android:name=".WatchCommandListenerService" android:exported="true">
  <intent-filter>
    <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
    <data android:scheme="wear" android:host="*" android:pathPrefix="/biometrix/command" />
  </intent-filter>
</service>
```
The watch also advertises a `biometrix_watch` capability (`wear/src/main/res/values/wear.xml`) so the
phone's `WatchCommandSender` can resolve its node.

> **Runtime grants on API 36+:** HR/EDA require `android.permission.health.READ_HEART_RATE` **and**
> the Samsung `READ_ADDITIONAL_HEALTH_DATA` — `BODY_SENSORS` alone returns PERMISSION_ERROR.
> `BODY_SENSORS_BACKGROUND` is requested **separately** and routed to a settings screen
> ("Allow all the time"). For testing it can be granted directly:
> `adb shell pm grant com.biometrix.operator android.permission.BODY_SENSORS_BACKGROUND`.

### Tablet side (`:app`)

```xml
<service android:name=".data.sensor.watch.WatchListenerService" android:exported="true">
  <intent-filter>   <!-- live readings + heartbeat -->
    <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
    <data android:scheme="wear" android:host="*" android:pathPrefix="/biometrix/sensors" />
  </intent-filter>
  <intent-filter>   <!-- historical store flush (DataItems) -->
    <action android:name="com.google.android.gms.wearable.DATA_CHANGED" />
    <data android:scheme="wear" android:host="*" android:pathPrefix="/biometrix/flush" />
  </intent-filter>
</service>
```
Plus `app/src/main/res/values/wear.xml` declaring the `biometrix_phone` capability. No body-sensor
permissions on the tablet — it only receives messages and DataItems.

## Project / Build Setup

- **Module:** `:wear` (`com.android.application`, same `applicationId "com.biometrix.operator"`),
  `minSdk 28` (Samsung Sensor SDK floor; Watch 8 far exceeds it), compile/target 36.
- **Samsung SDK** is a local AAR at `wear/libs/samsung-health-sensor-api-1.4.1.aar`, wired as:
  ```kotlin
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
  ```
  Do **not** use `implementation(":name@aar")` — that resolves against a repository, not the file.
- **`settings.gradle.kts`** uses `FAIL_ON_PROJECT_REPOS`, so per-module `repositories {}` are
  forbidden; the central `flatDir` lists both `app/libs` and `wear/libs`. `include(":wear")`.
- **`play-services-wearable`** (Data Layer) is a dependency of **both** `:app` and `:wear`.
- **Samsung developer mode** must be enabled on the watch for the SDK to deliver data prior to
  Samsung Partner approval (Health Platform → tap the title ~10×).

## App Implementation

### Key Files

| File | Role |
|------|------|
| [wear/.../WatchSensorService.kt](../wear/src/main/java/com/biometrix/operator/wear/WatchSensorService.kt) | Foreground `health` service; owns the Samsung SDK, registers trackers, runs the `flush()` loop, sends `STOP` |
| [wear/.../WatchDataSender.kt](../wear/src/main/java/com/biometrix/operator/wear/WatchDataSender.kt) | `MessageClient` sender; resolves & caches the `biometrix_phone` node |
| [wear/.../WatchMessage.kt](../wear/src/main/java/com/biometrix/operator/wear/WatchMessage.kt) | Builds the JSON lines (`reading`, `capabilities`, `batch`, `stop`) |
| [wear/.../MainActivity.kt](../wear/src/main/java/com/biometrix/operator/wear/MainActivity.kt) | Minimal Start/Stop watch UI; requests runtime permissions |
| [app/.../data/sensor/watch/WatchListenerService.kt](../app/src/main/java/com/biometrix/operator/data/sensor/watch/WatchListenerService.kt) | `WearableListenerService`; parses messages → receiver |
| [app/.../data/sensor/watch/WatchSensorReceiver.kt](../app/src/main/java/com/biometrix/operator/data/sensor/watch/WatchSensorReceiver.kt) | Hilt singleton sink; exposes flows; inferred connection state + watchdog |
| [app/.../data/sensor/watch/model/WatchReading.kt](../app/src/main/java/com/biometrix/operator/data/sensor/watch/model/WatchReading.kt) | `WatchReading(type, value, accuracy, t)` |
| [app/.../presentation/screens/sensors/watch/WatchSensorScreen.kt](../app/src/main/java/com/biometrix/operator/presentation/screens/sensors/watch/WatchSensorScreen.kt) | Sensors → Galaxy Watch live-readings screen |
| [app/.../presentation/screens/sensors/watch/WatchSensorViewModel.kt](../app/src/main/java/com/biometrix/operator/presentation/screens/sensors/watch/WatchSensorViewModel.kt) | Reads receiver flows for the screen; also tracks the **phone's Bluetooth adapter** state (`ACTION_STATE_CHANGED` receiver → `bluetoothEnabled` flow) |
| [app/.../presentation/components/BluetoothDisabledCard.kt](../app/src/main/java/com/biometrix/operator/presentation/components/BluetoothDisabledCard.kt) | Reusable "Bluetooth Disabled — tap to enable" warning card, shared with the eSense Pulse screen |

### Phone-side Bluetooth warning (UI)

The watch link needs the phone's **Bluetooth on** to use the direct (nearby) transport. So
`WatchSensorScreen` shows the shared `BluetoothDisabledCard` at the top whenever
`viewModel.bluetoothEnabled` is false; tapping it launches `BluetoothAdapter.ACTION_REQUEST_ENABLE`.
The card disappears reactively when BT turns on (observed via the `ACTION_STATE_CHANGED` receiver in
the ViewModel). This mirrors the eSense Pulse screen's `BluetoothDisabledCard` exactly. It is a
**warning only** — it does not change which Data Layer transport the *watch* picks; that is decided
on the watch by `WatchDataSender` (see *Transport*).

### Receiver State (flows)

| Flow | Type | Purpose |
|------|------|---------|
| `connectionState` | `StateFlow<ConnectionState>` | inferred CONNECTED/DISCONNECTED (also via `ConnectionRepository.watchConnectionState`) |
| `latestByType` | `StateFlow<Map<String, WatchReading>>` | most recent reading per signal (HR/IBI/EDA/BATTERY) |
| `availableTrackers` | `StateFlow<List<String>>` | trackers the watch reports as supported |
| `batteryLevel` | `StateFlow<Int?>` | watch battery % |

### Recording / Database Integration (implemented 2026-06)

Watch readings are recorded to the DB and export. Each `SensorType` maps to **exactly one physical
sensor** so HR from the watch and the eSense Pulse (recorded simultaneously) never merge — wire types
`WATCH_HR`/`WATCH_IBI`/`WATCH_EDA` map to **`SensorType.WATCH_HR`**, **`SensorType.WATCH_IBI`** (a
distinct value so watch HRV is attributable vs. eSense RR), and **`SensorType.WATCH_EDA`**; the eSense
Pulse uses **`SensorType.ESENSE_HEART_RATE`**. The DB is at version 3 (v3 split per-device HR/EDA;
`accuracy` is intentionally dropped at ingest — not a DB column); enums store as strings under
`fallbackToDestructiveMigration`, so the rename needed no hand-written `Migration` (the destructive
fallback wipes old local rows, which are already exported/uploaded). Export maps each type to a
distinct lowercase string (`watch_hr`/`watch_ibi`/`watch_eda`/`esense_heart_rate`) in both JSON
(`SessionExportMapper`) and CSV (`SessionExportService`). The
session-long buffer + `WatchSessionDrainer` (per-(scenario,type) timestamp-window attribution, with
high-water-mark de-dup against live-written rows) handle live readings and flushed history identically.
See **Store-and-Forward + Remote Flush**.

## Troubleshooting and Edge Cases

**Connection flaps CONNECTED↔DISCONNECTED repeatedly when the watch screen turns off / sleeps**
- **Expected on this platform — not a bug.** Screen-off the watch dozes, the AP suspends, and the
  Samsung SDK delivers in bursts on each maintenance wake (gap grows over time). The phone's inferred
  state flaps because data arrives in clumps separated by long silences. A `PARTIAL_WAKE_LOCK` does
  **not** fix it — Wear Doze forcibly DISABLES it (Google issue #228086086; see *Screen-Off Problem →
  What did NOT work*). The data itself is **not lost**: each sample is timestamped 1 Hz on the watch
  and arrives complete, just late. For continuous **live** screen-off delivery the only option is to
  keep the watch **display on** during sessions (prevents Doze).

**Phone shows "disconnected" most of the time while data clearly flows (screen ON)**
- The watchdog infers from message arrival. With the screen on, ensure the `flush()` loop is running
  (continuous 1 Hz) and `INACTIVITY_TIMEOUT_MS` is sensible (**6 s** for a 1 Hz stream).

**Data appears then freezes / "connected but frozen"**
- Historical symptom of the old `ChannelClient` stream nulling its `OutputStream`. The current
  `MessageClient` design has no stream to freeze. If it recurs, suspect the FGS recreation churn
  (see lifecycle gotchas) — check `adb logcat` for repeating `unbind Tracker Service` /
  `Health SDK connection ended`.

**Connection drops every time the PHONE sleeps / screen turns off (watch still sending)**
- The phone's **Bluetooth is off**, so the link is running over the **cloud relay**, which can't
  deliver to a dozing phone. This is *not* a Doze/wake-lock problem on the phone — disabling Doze
  won't fix it. **Turn the phone's Bluetooth on** (and ensure the watch is BT-bonded to *this* phone
  via Galaxy Wearable so a `isNearby` node exists). After enabling BT, **Stop→Start tracking on the
  watch** so `WatchDataSender` re-resolves onto the nearby node instead of staying latched on the
  cloud node. See the CRITICAL note in *Transport* above. Confirm: on the watch,
  `dumpsys activity service WearableService` should show a non-null `Nearby node ID` and
  `Status: CONNECTED`.

**SDK stops firing entirely the moment the screen blanks**
- `BODY_SENSORS_BACKGROUND` is not granted. Grant it (settings, "Allow all the time"), or via adb
  for testing.

**Watch keeps "restarting" / connect→unbind every few seconds**
- FGS type not asserted at runtime, or the init guard checks the static `isTracking` flag instead of
  the `trackingService` instance. See lifecycle gotchas.

**`PERMISSION_ERROR` on HR/EDA (API 36)**
- Need `health.READ_HEART_RATE` **and** Samsung `READ_ADDITIONAL_HEALTH_DATA`, not `BODY_SENSORS`.

**No data at all, no errors**
- Samsung developer mode (Health Platform) not enabled, or the `biometrix_phone` capability isn't
  declared/resolved (watch can't find the tablet node).

**Tap Start on the watch and "nothing happens" — watch shows "Phone not found", phone gets nothing**
- The watch resolves the tablet's `biometrix_phone` capability node *at the moment of Start*. The
  tablet only advertises that capability **while its app process is alive**, and the capability can
  take several seconds to propagate across the Data Layer — **noticeably longer on a cross-vendor
  pairing** (Galaxy Watch ↔ non-Samsung phone, bridged by `com.samsung.wearable.watchuniteplugin`).
  If the tablet app wasn't already open when you tapped Start, a single lookup lost the race and the
  watch gave up.
- **Fix in code:** `WatchDataSender.connect()` now **retries node resolution with backoff**
  (`CONNECT_RETRIES`×`CONNECT_RETRY_DELAY_MS`) and **never caches a null** id — so the watch keeps
  looking and links up on its own once the tablet app appears, instead of latching the failure.
- **Operational rule:** open the **tablet app first**, then tap **Start** on the watch. With the
  retry fix the order is forgiving, but opening the tablet first avoids the wait entirely.
- Verify the capability is actually reachable from the watch:
  ```bash
  adb -s <watch> shell dumpsys activity service \
    com.google.android.gms.wearable.service.WearableService | grep -E "biometrix_phone|Reachable|isNearby"
  #   the tablet node should be listed under "Reachable Nodes" with isNearby=true,
  #   and "+ biometrix_phone" should appear under that node.
  ```
- On a healthy link the watch logs `WatchDataSender: Resolved tablet node <id>`.

**Messages arrive at the phone's GMS but `WatchListenerService` never fires (commonly right after a reinstall)**
- Symptom that looks identical to "no data," but the transport is actually fine — GMS *received* the
  messages, it just didn't dispatch them to the app. GMS caches which app components are bound to
  which message path, and **reinstalling the tablet app leaves that dispatch table stale**: the bytes
  are received and accounted to the package, but `WatchListenerService.onMessageReceived` is never
  called.
- **Tell-tale:** the per-package read counter climbs while no `rx` log appears. Check both:
  ```bash
  # GMS is receiving on our path (read count climbs ~1/s)?
  adb -s <phone> shell dumpsys activity service \
    com.google.android.gms.wearable.service.WearableService | grep "com.biometrix.operator: writes/reads"
  # ...but the listener isn't logging the message?
  adb -s <phone> logcat -s WatchListenerService    # expect "rx {...}" lines once healthy
  ```
  Read count rising + no `rx` lines = stale GMS binding.
- **Fix:** `adb -s <phone> shell am force-stop com.biometrix.operator`, then relaunch the app. This
  clears GMS's cached binding; data lands immediately. (`WatchListenerService` logs each accepted
  message as `rx <body>` to make this unambiguous — previously it only logged on parse errors, so a
  working-but-silent success path looked dead.)

> **Note — the watch is almost never the culprit for "Start does nothing".** Verified on-device: the
> watch can be sampling HR/IBI/EDA and `flush()`-ing at 1 Hz perfectly while the phone shows nothing.
> Both failure modes above are **off-watch** (node resolution race, or stale GMS dispatch on the
> phone). Confirm the watch side first with `adb -s <watch> logcat -s WatchSensorService` — if you see
> `SHS#HeartRate… onDataReceived`, the watch is fine and the problem is downstream.

**Useful adb diagnostics**
```bash
# Is the watch dozing? Is our wake lock honored (it won't be — that's expected)?
adb -s <watch> shell dumpsys power | grep -E "mWakefulness=|BioMetrix"
# Is the SDK firing 1/s or in bursts?
adb -s <watch> logcat -s SHS#EDAContinuousSensor WatchDataSender
# Is the foreground service resident and typed HEALTH?
adb -s <watch> shell dumpsys activity services com.biometrix.operator
# Did the watch resolve the tablet node? (healthy: "Resolved tablet node <id>")
adb -s <watch> logcat -s WatchDataSender
# Can the watch even see the tablet's capability? (expect the tablet under Reachable Nodes, isNearby=true)
adb -s <watch> shell dumpsys activity service \
  com.google.android.gms.wearable.service.WearableService | grep -E "biometrix_phone|Reachable|isNearby"
# Are messages arriving on the phone? (healthy: "rx {...}" lines ~1/s)
adb -s <phone> logcat -s WatchListenerService
# Transport sanity: is GMS receiving on our path but NOT dispatching? (read count climbs, no rx lines = stale binding → force-stop the app)
adb -s <phone> shell dumpsys activity service \
  com.google.android.gms.wearable.service.WearableService | grep "com.biometrix.operator: writes/reads"
```

> **Note (Windows / PowerShell):** `adb` may not be on `PATH` — it lives at
> `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. To attach the watch over wireless adb you must
> **pair first** (`adb pair <ip:pairingPort> <6-digit-code>` from the watch's *Wireless debugging →
> Pair new device* dialog — the pairing port differs from the connect port) **before**
> `adb connect <ip:connectPort>`. Use `findstr` in place of `grep` on PowerShell.

## References

- Samsung Health Sensor SDK overview: https://developer.samsung.com/health/sensor/overview.html
- Getting started (trackers, `flush()`, `onFlushCompleted()`): https://developer.samsung.com/health/sensor/guide/getting-started.html
- Data specifications (HeartRateSet, EdaSet, status semantics): https://developer.samsung.com/health/sensor/guide/data-specifications.html
- "Continuous Heart Rate Tracking on Galaxy Watch, Even with the Screen Off" (2026-04-23): https://developer.samsung.com/galaxy-watch/blog/en/2026/04/23/continuous-heart-rate-tracking-on-galaxy-watch-even-with-the-screen-off
- See also [sensor_sampling_rates.md](sensor_sampling_rates.md) for multi-sensor synchronization context.
