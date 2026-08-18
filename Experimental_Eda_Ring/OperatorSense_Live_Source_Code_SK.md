# OperatorSense Live — zdrojový kód

Kompletný zdrojový kód súboru `operatorsense_live.html` s komentárom. Súbor je sebestačný: značky,
štýly aj logika sú v jednom dokumente, bez build kroku, bez knižníc, bez závislostí. Na spustenie
stačí otvoriť ho v prehliadači.

Súvisiaci dokument: **OperatorSense Live — EDA Ring: Technical Documentation** (hardvér, formát dát,
kalibrácia, používanie).

> Čísla riadkov odkazujú na `operatorsense_live.html` priložený k tomuto dokumentu. Výpis je
> kompletný — každý riadok súboru je v niektorej z častí, okrem štruktúrnych značiek `</head>`
> a `<script>`, ktoré ich oddeľujú.
>
> **Kód vrátane komentárov v ňom je ponechaný v origináli (anglicky).** Preložené sú iba popisy
> okolo neho.

---

## 1. Ako je kód usporiadaný

```
operatorsense_live.html
├── <style>         rozloženie a tmavá téma             (riadky 7-81)
└── <script>
    ├── calibration     ADC kód -> ohmy -> mikrosiemensy    (riadky 200-263)
    ├── upload config   predvoľby servera + schéma dát      (riadky 265-305)
    ├── state           premenné modulu                     (riadky 308-329)
    ├── connection      otvorenie/čítanie/zatvorenie portu  (riadky 331-398)
    ├── parsing         sériový riadok -> objekt vzorky     (riadky 400-439)
    ├── raw log         posledných 200 prijatých riadkov    (riadky 441-450)
    ├── stats + render  obnovovacia slučka 20 Hz            (riadky 452-507)
    ├── chart           kreslenie na canvas                 (riadky 509-587)
    ├── recording       nahrávanie / značky / vymazanie     (riadky 589-653)
    ├── server upload   nastavenia, payload, POST           (riadky 655-859)
    └── ui state        stav tlačidiel a stavového riadku   (riadky 861-890)
```

### Tok dát cez kód

```
  bajty zo sériového portu
      │
      ▼
  readLoop()            číta dáta, delí ich na CR/LF, neúplný koniec riadku si odkladá
      │
      ▼
  handleLine(line) ──► appendRaw(line)          surový log
      │
      ├──► parseLine(line)                      "D8730:01[CEE3];CAFE;1068" -> objekt
      ├──► chartData[]                          posuvné okno pre graf
      ├──► rateStamps[]                         živé meranie vzorkovacej frekvencie
      └──► recorded[]                           iba počas nahrávania
                │
                ├──► downloadCsv()              CSV súbor s prepočítanými hodnotami
                └──► sendCapture() ──► buildPayload() ──► postPayload()   JSON POST

  render()  každých 50 ms: štatistiky, prepočítaný údaj, drawChart()
```

**Zásady, ktoré kód dodržiava**

- **Žiadne závislosti.** Graf, zápis CSV aj HTTP klient sú napísané priamo nad API prehliadača, takže
  súbor funguje offline a nemôže sa pokaziť zmenou externej knižnice.
- **Surová hodnota sa nikdy nestratí.** Prepočty sa robia nad ADC kódom; pôvodný kód aj pôvodný
  sériový riadok idú do každého exportu.
- **Bez nastavenia sa nikam nič neodosiela.** Cesta na server je nečinná, kým sa nezadá endpoint.

---

## 2. Štruktúra stránky (HTML)

Hlavička dokumentu — kódovanie, viewport a názov v záložke prehliadača:

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>OperatorSense Live</title>
```

Telo stránky je statické; skript doň iba dopĺňa hodnoty a prepína stavy. Každý prvok, ktorého sa
skript dotýka, má `id`.

```html
<body>
  <h1>OperatorSense Live</h1>
  <div class="sub">Reads the FTDI USB dongle directly in the browser (Web Serial). No PuTTY, no install.</div>

  <div id="unsupported" class="banner err hidden">
    ⚠ This browser doesn't support Web Serial. Please open this file in <b>Google Chrome</b> or
    <b>Microsoft Edge</b> on a desktop PC.
  </div>

  <div class="bar">
    <button id="connectBtn" class="go">🔌 Connect dongle</button>
    <button id="disconnectBtn" disabled>Disconnect</button>
    <label class="status">Baud
      <select id="baud">
        <option>9600</option>
        <option>19200</option>
        <option selected>38400</option>
        <option>57600</option>
        <option>115200</option>
      </select>
    </label>
    <span class="status">8&nbsp;data&nbsp;/&nbsp;No&nbsp;parity&nbsp;/&nbsp;1&nbsp;stop</span>
    <span class="spacer"></span>
    <span class="status"><span id="dot" class="dot"></span><span id="statusText">Disconnected</span></span>
  </div>

  <div class="grid">
    <div class="card big">
      <div class="label">Current reading (raw ADC)</div>
      <div class="val"><span id="cur">—</span></div>
      <div class="unit" id="ohms">Resistance: — &nbsp;<span style="opacity:.7">(vendor calibration, per ring address)</span></div>
    </div>
    <div class="card"><div class="label">Min</div><div class="val"><span id="min">—</span></div></div>
    <div class="card"><div class="label">Max</div><div class="val"><span id="max">—</span></div></div>
    <div class="card"><div class="label">Average</div><div class="val"><span id="avg">—</span></div></div>
    <div class="card"><div class="label">Sample rate</div><div class="val"><span id="interval">—</span> <span class="unit">s/sample</span></div><div class="unit"><span id="rate">—</span> Hz</div></div>
    <div class="card"><div class="label">Duration</div><div class="val"><span id="duration">0:00</span></div></div>
    <div class="card"><div class="label">Recorded</div><div class="val"><span id="recCount">0</span> <span class="unit">samples</span></div></div>
    <div class="card"><div class="label">Events</div><div class="val"><span id="evtCount">0</span></div></div>
  </div>

  <div class="panel">
    <h2>Live signal</h2>
    <canvas id="chart"></canvas>
    <div class="decoded" id="decoded">Last packet: —</div>
  </div>

  <div class="panel">
    <h2>Recording &amp; export</h2>
    <div style="display:flex; flex-wrap:wrap; gap:10px; align-items:center;">
      <button id="recBtn" class="go" disabled>● Start recording</button>
      <button id="markBtn" class="ghost" disabled>⚑ Mark event</button>
      <button id="csvBtn" class="ghost" disabled>⬇ Download CSV</button>
      <button id="uploadBtn" class="ghost" disabled>⬆ Send to server</button>
      <button id="clearBtn" class="ghost" disabled>Clear recording</button>
      <span class="hint">Recording captures every reading with a timestamp for CSV export. “Mark event” drops a labelled marker (e.g. touch / release / stimulus) onto the graph and into the CSV. Watching the live graph does not require recording.</span>
    </div>

    <div class="subpanel">
      <div class="row">
        <b style="font-size:13px;">Server upload</b>
        <span id="uploadState" class="status">Not configured</span>
        <span class="spacer"></span>
        <button id="uploadCfgBtn" class="ghost">⚙ Server settings</button>
      </div>

      <div id="uploadCfg" class="hidden">
        <div class="cfggrid">
          <label class="field">
            <span>Endpoint URL</span>
            <input id="cfgEndpoint" type="url" placeholder="https://api.example.org/v1/eda/captures" autocomplete="off" />
          </label>
          <label class="field">
            <span>Authentication</span>
            <select id="cfgScheme">
              <option value="bearer">Bearer token (Authorization)</option>
              <option value="apikey">API key header</option>
              <option value="none">None (open endpoint)</option>
            </select>
          </label>
          <label class="field">
            <span>API key / token</span>
            <input id="cfgToken" type="password" placeholder="paste the key issued by your server" autocomplete="off" />
          </label>
          <label class="field">
            <span>API key header name</span>
            <input id="cfgHeader" type="text" placeholder="X-API-Key" autocomplete="off" />
          </label>
          <label class="field">
            <span>Session label (optional)</span>
            <input id="cfgLabel" type="text" placeholder="e.g. operator-07 / pilot-run-2" autocomplete="off" />
          </label>
        </div>
        <div class="row">
          <button id="cfgSaveBtn" class="go">Save settings</button>
          <button id="cfgTestBtn" class="ghost">Test connection</button>
          <button id="cfgForgetBtn" class="ghost">Forget credentials</button>
        </div>
        <div class="hint">
          Nothing is sent anywhere until an endpoint is filled in here — the upload is wired up but
          points at no server by default. Settings are stored in this browser only (localStorage);
          <b>“Forget credentials”</b> wipes them. The payload is a single JSON <code>POST</code>
          (schema documented at the top of the script). The server must return 2xx and allow CORS —
          pages opened as <code>file://</code> send <code>Origin: null</code>, so serving this file over
          <code>http://</code> is the easier path.
        </div>
      </div>
    </div>
  </div>

  <div class="panel">
    <h2>Raw serial log</h2>
    <div id="rawlog"></div>
    <div class="hint">Showing the last 200 lines as they arrive from the dongle.</div>
  </div>

```

---

## 3. Štýly (CSS)

Tmavá téma postavená na CSS premenných: zmenou hodnôt v `:root` sa prefarbí celá stránka. Rozloženie
používa flexbox pre lišty a CSS grid pre kartičky s údajmi, takže sa prispôsobí aj úzkemu oknu bez
media queries.

```css
<style>
  :root{
    --bg:#0e1116; --panel:#161b22; --panel2:#1d2430; --border:#2a3340;
    --text:#e6edf3; --muted:#9aa7b4; --accent:#3fb950; --accent2:#58a6ff;
    --warn:#d29922; --err:#f85149;
  }
  *{box-sizing:border-box;}
  body{
    margin:0; font-family:"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
    background:var(--bg); color:var(--text); padding:16px;
  }
  h1{font-size:20px; margin:0 0 2px;}
  .sub{color:var(--muted); font-size:13px; margin-bottom:16px;}
  .bar{
    display:flex; flex-wrap:wrap; gap:10px; align-items:center;
    background:var(--panel); border:1px solid var(--border);
    border-radius:10px; padding:12px; margin-bottom:16px;
  }
  button{
    background:var(--accent2); color:#0b1622; border:none; border-radius:8px;
    padding:9px 14px; font-size:14px; font-weight:600; cursor:pointer;
  }
  button:hover{filter:brightness(1.08);}
  button:disabled{opacity:.45; cursor:not-allowed;}
  button.ghost{background:transparent; color:var(--accent2); border:1px solid var(--border);}
  button.danger{background:var(--err); color:#fff;}
  button.go{background:var(--accent); color:#06210d;}
  select{
    background:var(--panel2); color:var(--text); border:1px solid var(--border);
    border-radius:8px; padding:8px; font-size:14px;
  }
  .spacer{flex:1;}
  .status{font-size:13px; color:var(--muted);}
  .dot{display:inline-block; width:9px; height:9px; border-radius:50%; background:#555; margin-right:6px; vertical-align:middle;}
  .dot.on{background:var(--accent); box-shadow:0 0 8px var(--accent);}
  .dot.err{background:var(--err);}
  .grid{display:grid; grid-template-columns:repeat(auto-fit,minmax(120px,1fr)); gap:12px; margin-bottom:16px;}
  .card{background:var(--panel); border:1px solid var(--border); border-radius:10px; padding:14px;}
  .card .label{color:var(--muted); font-size:12px; text-transform:uppercase; letter-spacing:.04em;}
  .card .val{font-size:30px; font-weight:700; margin-top:4px; font-variant-numeric:tabular-nums;}
  .card.big .val{font-size:52px; color:var(--accent);}
  .card .unit{font-size:13px; color:var(--muted); font-weight:400;}
  .panel{background:var(--panel); border:1px solid var(--border); border-radius:10px; padding:14px; margin-bottom:16px;}
  .panel h2{font-size:14px; margin:0 0 10px; color:var(--muted); text-transform:uppercase; letter-spacing:.04em;}
  canvas{width:100%; height:280px; display:block; background:var(--panel2); border-radius:8px;}
  #rawlog{
    font-family:Consolas,"Courier New",monospace; font-size:12px; line-height:1.5;
    background:#0a0d12; border:1px solid var(--border); border-radius:8px;
    height:180px; overflow:auto; padding:8px; color:#b9c4cf; white-space:pre;
  }
  .banner{
    background:#3a2a08; border:1px solid var(--warn); color:#ffd98a;
    border-radius:10px; padding:12px 14px; margin-bottom:16px; font-size:14px;
  }
  .banner.err{background:#3a0d0d; border-color:var(--err); color:#ffb3ad;}
  .hidden{display:none;}
  .hint{color:var(--muted); font-size:12px; margin-top:8px;}
  a{color:var(--accent2);}
  .decoded{font-family:Consolas,monospace; font-size:12px; color:var(--muted);}
  .decoded b{color:var(--text);}
  input{
    background:var(--panel2); color:var(--text); border:1px solid var(--border);
    border-radius:8px; padding:8px; font-size:13px; width:100%;
  }
  input:disabled{opacity:.45;}
  .subpanel{margin-top:14px; border-top:1px solid var(--border); padding-top:12px;}
  .row{display:flex; flex-wrap:wrap; gap:10px; align-items:center;}
  .cfggrid{display:grid; grid-template-columns:repeat(auto-fit,minmax(230px,1fr)); gap:12px; margin:12px 0;}
  .field{display:flex; flex-direction:column; gap:4px;}
  .field > span{font-size:12px; color:var(--muted); text-transform:uppercase; letter-spacing:.04em;}
  .field select{width:100%;}
  .status.ok{color:var(--accent);}
  .status.err{color:var(--err);}
  .status.busy{color:var(--accent2);}
</style>
```

---

## 4. Kalibrácia

Prevádza prenášaný ADC kód na odpor a odpor na vodivosť. Nameraná tabuľka pochádza
z `CALIBRATION.xlsx`; každý prsteň má vlastný stĺpec a vyberá sa podľa adresy prsteňa, ktorá je
v každom pakete. Hodnoty medzi bodmi tabuľky sa interpolujú, hodnoty mimo nej sa extrapolujú
a označia príznakom.

```javascript
/* ============================================================================
   ADC -> resistance conversion (vendor calibration).

   Source: CALIBRATION.xlsx in this folder, measured by the device's developer.
   Reference resistors were connected to each ring and the reported ADC code
   noted, giving one table per ring:

        R [kOhm]   CAFE (band)   CAD1 (no band)
          0.55         --              --          <- below the detection floor
          1             3               3
          2.2           9              10
          5.55         27              28
          8.19         40              42
         17.9          93              96
         38.9         212             212
         68.3         361             377
        100.2         554             550

   The ring address travels in every packet, so the matching table is picked
   automatically; an unknown ring falls back to CALIBRATION_FALLBACK_RING.
   Values are piecewise-linearly interpolated between table points and linearly
   extrapolated outside them (the response is close to, but not exactly, linear).
   Readings outside 1-100 kOhm are therefore estimates - the UI flags them as
   "extrapolated" and every export carries an in-calibrated-range flag.

   To add a further ring, append its address and its measured pairs below.
   ============================================================================ */
let CONVERSION_ENABLED = true;

const CALIBRATION_TABLES = {          // ring address -> [[adcCode, kOhm], ...] ascending
  "CAFE": [[3,1],[9,2.2],[27,5.55],[40,8.19],[93,17.9],[212,38.9],[361,68.3],[554,100.2]],
  "CAD1": [[3,1],[10,2.2],[28,5.55],[42,8.19],[96,17.9],[212,38.9],[377,68.3],[550,100.2]]
};
const CALIBRATION_FALLBACK_RING = "CAFE";

function calibrationFor(ringAddr){
  const key = String(ringAddr || "").trim().toUpperCase();
  return CALIBRATION_TABLES[key] || CALIBRATION_TABLES[CALIBRATION_FALLBACK_RING];
}

/* Raw ADC code -> resistance in ohms (null when it cannot be converted). */
function adcToOhms(adc, ringAddr){
  if(adc === null || adc === undefined || Number.isNaN(adc)) return null;
  const t = calibrationFor(ringAddr);
  if(!t || t.length < 2) return null;
  let i;
  if(adc <= t[0][0])               i = 0;                // extrapolate below the table
  else if(adc >= t[t.length-1][0]) i = t.length - 2;     // extrapolate above the table
  else { i = 0; while(i < t.length - 2 && adc > t[i+1][0]) i++; }
  const [a0, r0] = t[i], [a1, r1] = t[i+1];
  const kOhm = r0 + (adc - a0) * (r1 - r0) / (a1 - a0);
  return Math.max(0, kOhm * 1000);
}

/* True while the reading sits inside the measured calibration range. */
function adcInCalibratedRange(adc, ringAddr){
  const t = calibrationFor(ringAddr);
  return !!t && adc >= t[0][0] && adc <= t[t.length-1][0];
}

/* Skin conductance in microsiemens - the usual unit for EDA analysis. */
function ohmsToMicroSiemens(ohms){
  return (ohms && ohms > 0) ? 1e6 / ohms : null;
}
```

---

## 5. Konfigurácia odosielania na server

Predvolené hodnoty a schéma odosielaných dát. Všetko sa dá za behu prepísať nastaveniami, ktoré si
operátor uloží v prehliadači — odovzdávaná kópia teda môže byť prázdna aj predvyplnená.

```javascript
/* ============================================================================
   SERVER UPLOAD — fully wired, deliberately pointed at NO server.

   Whoever takes this over only has to open "⚙ Server settings" in the
   "Recording & export" panel and fill in the endpoint URL + API key; no code
   change is needed. To ship a pre-filled build instead, put the values in
   UPLOAD_DEFAULTS below (saved settings in the browser still win).

   The server receives one JSON POST per capture:

     {
       "schemaVersion": "1.0",
       "source": "OperatorSense Live (EDA ring, FTDI Web Serial)",
       "sentAtIso": "2026-08-18T09:12:33.120Z",
       "test": false,                         // true = "Test connection" ping, no samples
       "capture": {
         "captureId": "os-20260818T091120-4f3a",   // stable per recording -> use it to de-duplicate
         "label": "operator-07",                   // optional, from the settings form
         "startedAtIso": "...", "endedAtIso": "...",
         "durationMs": 61234, "sampleCount": 612, "eventCount": 3, "baudRate": 38400
       },
       "device": { "dongleAddr": "CEE3", "ringAddr": "CAFE", "usbVendorId": "0x0403" },
       "signal": { "unit": "raw_adc", "ohmsAvailable": false },
       "samples": [ { "epochMs":1755506... , "elapsedMs":0, "valueAdc":1068, "ohms":null,
                      "packetId":"D8730", "channel":"01", "dongleAddr":"CEE3",
                      "ringAddr":"CAFE", "raw":"D8730:01[CEE3];CAFE;1068" }, ... ],
       "events":  [ { "epochMs":..., "elapsedMs":..., "label":"touch" }, ... ]
     }

   Any 2xx response counts as success; the recorded data is kept either way, so
   a failed upload can be retried or exported to CSV instead.
   ============================================================================ */
const UPLOAD_DEFAULTS = {
  endpoint:  "",           // e.g. "https://api.example.org/v1/eda/captures"
  scheme:    "bearer",     // "bearer" | "apikey" | "none"
  token:     "",           // API key / bearer token
  header:    "X-API-Key",  // header name used when scheme === "apikey"
  label:     ""            // optional session label sent with every capture
};
const UPLOAD_STORAGE_KEY = "operatorsense.upload.v1";
const UPLOAD_TIMEOUT_MS  = 30000;
```

---

## 6. Stav

Celý meniteľný stav je v niekoľkých premenných na úrovni modulu — žiadny framework, žiadny store.
`recorded[]` je záznamový buffer, `chartData[]` je ohraničené posuvné okno slúžiace len na kreslenie.

```javascript
const FTDI_VENDOR_ID = 0x0403; // FT232R / FT232RQ

let port = null, reader = null, keepReading = false, textBuffer = "";
let chartData = [];                 // rolling {t, v} for the graph
const CHART_MAX = 600;
let recording = false;
let recorded = [];                  // {epoch, value, pktId, channel, dongle, ring, raw}
let events = [];                    // {epoch, label}
let rateStamps = [];                // recent timestamps for rate calc
let lastValue = null;
let lastPacket = null;              // last successfully parsed packet (device addresses)
let captureId = null;               // identifies the current recording to the server
let firstSampleEpoch = null;        // for duration + chart time axis
let lastSampleEpoch = null;
let dirty = false;

const $ = id => document.getElementById(id);

if(!("serial" in navigator)){
  $("unsupported").classList.remove("hidden");
  $("connectBtn").disabled = true;
}
```

---

## 7. Sériové pripojenie

Otvára port cez Web Serial API, filtrovaný na vendor ID FTDI, s núdzovým nefiltrovaným výberom.
`readLoop()` dekóduje prichádzajúce bajty, delí ich na ktorýkoľvek z CR/LF/CRLF a neúplný koniec
riadku si odkladá, kým nepríde zvyšok — vďaka tomu nezáleží na tom, že ukončovací znak riadku nie je
zdokumentovaný.

```javascript
/* ---------- connection ---------- */
$("connectBtn").addEventListener("click", connect);
$("disconnectBtn").addEventListener("click", disconnect);

async function connect(){
  if(!("serial" in navigator)) return;
  // Try with an FTDI filter first; if the user cancels, offer the unfiltered chooser.
  try{
    port = await navigator.serial.requestPort({ filters:[{ usbVendorId: FTDI_VENDOR_ID }] });
  }catch(e){
    try{
      port = await navigator.serial.requestPort(); // no filter — show all serial ports
    }catch(e2){
      setStatus("No port selected", "muted");
      return;
    }
  }
  const baudRate = parseInt($("baud").value, 10);
  try{
    await port.open({ baudRate, dataBits:8, parity:"none", stopBits:1, flowControl:"none" });
  }catch(e){
    setStatus("Open failed: " + e.message, "err");
    port = null;
    return;
  }
  // reset the live view for a fresh session (kept: recorded data + event marks)
  chartData = []; rateStamps = []; lastValue = null;
  firstSampleEpoch = null; lastSampleEpoch = null;
  keepReading = true;
  setConnectedUI(true);
  setStatus("Connected — receiving", "on");
  startRender();
  readLoop();
}

async function readLoop(){
  const decoder = new TextDecoder();
  textBuffer = "";
  while(port && port.readable && keepReading){
    reader = port.readable.getReader();
    try{
      while(true){
        const { value, done } = await reader.read();
        if(done) break;
        if(value && value.length){
          textBuffer += decoder.decode(value, { stream:true });
          const lines = textBuffer.split(/\r\n|\r|\n/);
          textBuffer = lines.pop();            // keep the incomplete tail
          for(const ln of lines) handleLine(ln);
        }
      }
    }catch(e){
      setStatus("Read error: " + e.message, "err");
    }finally{
      try{ reader.releaseLock(); }catch(_){}
    }
  }
}

async function disconnect(){
  keepReading = false;
  try{ if(reader) await reader.cancel(); }catch(_){}
  try{ if(port) await port.close(); }catch(_){}
  port = null; reader = null;
  stopRender();
  setConnectedUI(false);
  setStatus("Disconnected", "muted");
}
```

---

## 8. Parsovanie

`parseLine()` berie ako hodnotu celé číslo za **posledným** `;` a úvodné polia považuje za voliteľné
metadáta. Je to zámer: aj keby sa hlavička paketu neskôr interpretovala inak, samotné meranie sa
naďalej prečíta.

```javascript
/* ---------- parsing ---------- */
// Expected: D8730:01[CEE3];CAFE;1068   (value = integer after the last ';')
function parseLine(line){
  line = line.trim();
  if(!line) return null;
  const parts = line.split(";");
  if(parts.length < 2) return null;
  const value = parseInt(parts[parts.length - 1].trim(), 10);
  if(Number.isNaN(value)) return null;
  let pktId = "", channel = "", dongle = "", ring = "";
  const m = parts[0].match(/^([^:]+):([^\[]+)\[([^\]]+)\]$/);
  if(m){ pktId = m[1]; channel = m[2]; dongle = m[3]; }
  if(parts.length >= 3) ring = parts[parts.length - 2].trim();
  return { value, pktId, channel, dongle, ring, raw: line };
}

function handleLine(line){
  appendRaw(line);
  const p = parseLine(line);
  if(!p) return;
  const now = Date.now();
  if(firstSampleEpoch === null) firstSampleEpoch = now;
  lastSampleEpoch = now;
  lastValue = p.value;
  chartData.push({ t: now, v: p.value });
  if(chartData.length > CHART_MAX) chartData.shift();

  rateStamps.push(now);
  if(rateStamps.length > 50) rateStamps.shift();

  if(recording){
    recorded.push({ epoch: now, value: p.value, pktId:p.pktId, channel:p.channel,
                    dongle:p.dongle, ring:p.ring, raw:p.raw });
  }
  lastPacket = p;
  $("decoded").innerHTML = "Last packet: " +
    `pkt <b>${p.pktId||"?"}</b> · ch <b>${p.channel||"?"}</b> · ` +
    `dongle <b>${p.dongle||"?"}</b> · ring <b>${p.ring||"?"}</b> · value <b>${p.value}</b>`;
  dirty = true;
}
```

---

## 9. Surový sériový log

Drží posledných 200 riadkov presne tak, ako prišli, a automaticky posúva iba vtedy, keď je operátor
už dole — posúvanie späť kvôli kontrole teda neprerušujú nové dáta.

```javascript
/* ---------- raw log ---------- */
let rawLines = [];
function appendRaw(line){
  rawLines.push(line);
  if(rawLines.length > 200) rawLines.shift();
  const el = $("rawlog");
  const atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 4;
  el.textContent = rawLines.join("\n");
  if(atBottom) el.scrollTop = el.scrollHeight;
}
```

---

## 10. Štatistiky a vykresľovacia slučka

Všetky aktualizácie obrazovky riadi jediný časovač s frekvenciou 20 Hz. Prichádzajúce dáta iba
nastavia príznak `dirty`; slučka sama rozhodne, kedy prepočítať a prekresliť. Rozhranie tak zostáva
plynulé bez ohľadu na to, ako rýchlo chodia pakety.

```javascript
/* ---------- stats + chart render loop ---------- */
let renderTimer = null;
function startRender(){ if(!renderTimer) renderTimer = setInterval(render, 50); }
function stopRender(){ if(renderTimer){ clearInterval(renderTimer); renderTimer = null; } render(); }

function render(){
  // big number always reflects latest
  $("cur").textContent = lastValue === null ? "—" : lastValue;
  if(CONVERSION_ENABLED && lastValue !== null){
    const ring = lastPacket ? lastPacket.ring : null;
    const ohms = adcToOhms(lastValue, ring);
    if(ohms === null){
      $("ohms").textContent = "Resistance: —";
    }else{
      const us = ohmsToMicroSiemens(ohms);
      $("ohms").textContent = "Resistance: " + (ohms/1000).toFixed(2) + " kΩ · " +
        (us === null ? "—" : us.toFixed(2) + " µS") +
        (adcInCalibratedRange(lastValue, ring) ? "" : "  (extrapolated)");
    }
  }
  updateDuration();
  if(!dirty){ updateRate(); return; }
  dirty = false;

  if(chartData.length){
    const vals = chartData.map(d => d.v);
    const mn = Math.min(...vals), mx = Math.max(...vals);
    const sum = vals.reduce((a,b)=>a+b,0);
    $("min").textContent = mn;
    $("max").textContent = mx;
    $("avg").textContent = Math.round(sum / vals.length);
  }
  $("recCount").textContent = recorded.length;
  $("evtCount").textContent = events.length;
  refreshUploadUi();
  updateRate();
  drawChart();
}

function updateDuration(){
  if(firstSampleEpoch === null){ $("duration").textContent = "0:00"; return; }
  const secs = Math.floor((lastSampleEpoch - firstSampleEpoch) / 1000);
  const m = Math.floor(secs / 60), s = secs % 60;
  $("duration").textContent = m + ":" + String(s).padStart(2, "0");
}

function updateRate(){
  if(rateStamps.length >= 2){
    const span = (rateStamps[rateStamps.length-1] - rateStamps[0]) / 1000;
    if(span > 0){
      const hz = (rateStamps.length - 1) / span;
      $("rate").textContent = hz.toFixed(2);
      $("interval").textContent = (1 / hz).toFixed(1);
    }
  }
}
```

---

## 11. Graf

Graf sa kreslí priamo do prvku `<canvas>`: škálovanie osí, mriežka, značky udalostí, čiara signálu
a zvýraznený posledný bod. Plátno sa prispôsobuje pomeru pixelov zariadenia, takže čiara zostáva
ostrá aj na displejoch s vysokým rozlíšením.

```javascript
/* ---------- canvas chart ---------- */
const canvas = $("chart");
const ctx = canvas.getContext("2d");
function sizeCanvas(){
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, h = canvas.clientHeight;
  canvas.width = w * dpr; canvas.height = h * dpr;
  ctx.setTransform(dpr,0,0,dpr,0,0);
  drawChart();
}
window.addEventListener("resize", sizeCanvas);

function drawChart(){
  const w = canvas.clientWidth, h = canvas.clientHeight;
  ctx.clearRect(0,0,w,h);
  // grid
  ctx.strokeStyle = "rgba(255,255,255,0.06)";
  ctx.lineWidth = 1;
  for(let i=0;i<=4;i++){ const y = (h/4)*i; ctx.beginPath(); ctx.moveTo(0,y); ctx.lineTo(w,y); ctx.stroke(); }
  if(chartData.length < 2) return;

  const vals = chartData.map(d => d.v);
  let mn = Math.min(...vals), mx = Math.max(...vals);
  if(mn === mx){ mn -= 1; mx += 1; }
  const pad = (mx - mn) * 0.1; mn -= pad; mx += pad;

  const tMin = chartData[0].t, tMax = chartData[chartData.length - 1].t;
  const tSpan = (tMax - tMin) || 1;
  const xForT = t => ((t - tMin) / tSpan) * w;
  const yFor = v => h - ((v - mn) / (mx - mn)) * h;

  // y labels
  ctx.fillStyle = "rgba(255,255,255,0.45)";
  ctx.font = "11px Consolas, monospace";
  ctx.textAlign = "left";
  ctx.fillText(Math.round(mx), 4, 14);
  ctx.fillText(Math.round(mn), 4, h - 6);

  // x time labels (elapsed seconds from the first sample of the session)
  const t0 = firstSampleEpoch === null ? tMin : firstSampleEpoch;
  ctx.fillText("t+" + ((tMin - t0) / 1000).toFixed(0) + "s", 30, h - 6);
  ctx.textAlign = "right";
  ctx.fillText("t+" + ((tMax - t0) / 1000).toFixed(0) + "s", w - 4, h - 6);
  ctx.textAlign = "left";

  // event markers (vertical dashed lines)
  ctx.strokeStyle = "rgba(210,153,34,0.85)";
  ctx.fillStyle = "rgba(210,153,34,0.95)";
  ctx.setLineDash([4, 3]);
  for(const ev of events){
    if(ev.epoch < tMin || ev.epoch > tMax) continue;
    const x = xForT(ev.epoch);
    ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke();
    ctx.fillText(ev.label, x + 3, 24);
  }
  ctx.setLineDash([]);

  // line
  ctx.strokeStyle = "#3fb950";
  ctx.lineWidth = 1.6;
  ctx.beginPath();
  for(let i=0;i<chartData.length;i++){
    const x = xForT(chartData[i].t), y = yFor(chartData[i].v);
    i===0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
  }
  ctx.stroke();

  // sample dots (only when sparse enough to stay readable)
  if(chartData.length <= 200){
    ctx.fillStyle = "#3fb950";
    for(const d of chartData){
      ctx.beginPath(); ctx.arc(xForT(d.t), yFor(d.v), 2, 0, Math.PI*2); ctx.fill();
    }
  }
  // highlight latest point
  const last = chartData[chartData.length - 1];
  ctx.fillStyle = "#7ee787";
  ctx.beginPath(); ctx.arc(xForT(last.t), yFor(last.v), 3.5, 0, Math.PI*2); ctx.fill();
}
```

---

## 12. Ovládanie nahrávania

Nahrávanie, značka udalosti a vymazanie. Každé nahrávanie dostane `captureId`, podľa ktorého vie
server rozpoznať zopakované odoslanie tých istých dát; vymazaním buffera vznikne nové.

```javascript
/* ---------- recording controls ---------- */
$("recBtn").addEventListener("click", ()=>{
  recording = !recording;
  if(recording && !captureId) captureId = newCaptureId();
  uploadNotice = null;
  $("recBtn").textContent = recording ? "■ Stop recording" : "● Start recording";
  $("recBtn").className = recording ? "danger" : "go";
  refreshUploadUi();
});
$("markBtn").addEventListener("click", ()=>{
  if(firstSampleEpoch === null) return;
  const def = "Event " + (events.length + 1);
  const input = prompt("Label for this event marker:", def);
  if(input === null) return; // cancelled
  events.push({ epoch: Date.now(), label: input.trim() || def });
  $("evtCount").textContent = events.length;
  dirty = true;
});
$("clearBtn").addEventListener("click", ()=>{
  recorded = [];
  events = [];
  captureId = recording ? newCaptureId() : null;
  uploadNotice = null;
  $("recCount").textContent = "0";
  $("evtCount").textContent = "0";
  refreshUploadUi();
});
$("csvBtn").addEventListener("click", downloadCsv);

function csvNum(x, decimals){
  return (x === null || x === undefined || Number.isNaN(x)) ? "" : x.toFixed(decimals);
}

function downloadCsv(){
  if(!recorded.length){ alert("Nothing recorded yet. Press “Start recording” first."); return; }
  const t0 = recorded[0].epoch;
  // attach each event marker to the nearest recorded sample (by time)
  const evtByIndex = {};
  for(const ev of events){
    let best = 0, bestD = Infinity;
    for(let i=0;i<recorded.length;i++){
      const d = Math.abs(recorded[i].epoch - ev.epoch);
      if(d < bestD){ bestD = d; best = i; }
    }
    evtByIndex[best] = evtByIndex[best] ? evtByIndex[best] + "|" + ev.label : ev.label;
  }
  const head = "timestamp_iso,epoch_ms,elapsed_ms,value_adc,resistance_ohm,conductance_us,in_calibrated_range,packet_id,channel,dongle_addr,ring_addr,event,raw";
  const rows = recorded.map((r, i) =>
    [ new Date(r.epoch).toISOString(), r.epoch, r.epoch - t0, r.value,
      csvNum(adcToOhms(r.value, r.ring), 1),
      csvNum(ohmsToMicroSiemens(adcToOhms(r.value, r.ring)), 4),
      adcInCalibratedRange(r.value, r.ring) ? 1 : 0,
      r.pktId, r.channel, r.dongle, r.ring,
      '"' + (evtByIndex[i] || "").replace(/"/g,'""') + '"',
      '"' + r.raw.replace(/"/g,'""') + '"' ].join(",")
  );
  const blob = new Blob([head + "\n" + rows.join("\n")], { type:"text/csv" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  const ts = new Date().toISOString().replace(/[:.]/g,"-").slice(0,19);
  a.href = url; a.download = `operatorsense_capture_${ts}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

```

---

## 13. Odosielanie na server

Uloženie nastavení, zostavenie dát a samotný `POST`. Chyby sa hlásia zrozumiteľne a nikdy nezahodia
nahraté dáta — neúspešné odoslanie sa dá zopakovať alebo nahradiť exportom do CSV.

```javascript
/* ---------- server upload ----------
   See the SERVER UPLOAD block at the top of this script for the payload schema
   and for how to hand this over pre-configured. */
let uploadCfg    = Object.assign({}, UPLOAD_DEFAULTS);
let uploadBusy   = false;
let uploadNotice = null;          // {text, kind} — result of the last send, until something changes
let lastPaintedState = null;

function newCaptureId(){
  const ts = new Date().toISOString().replace(/[-:]/g,"").replace(/\..+/,"");
  return "os-" + ts + "-" + Math.random().toString(16).slice(2, 6);
}

/* --- settings persistence (browser-local; the key never leaves this machine) --- */
function loadUploadCfg(){
  try{
    const raw = localStorage.getItem(UPLOAD_STORAGE_KEY);
    if(raw) uploadCfg = Object.assign({}, UPLOAD_DEFAULTS, JSON.parse(raw));
  }catch(_){ /* private mode / file:// without storage — defaults stay in memory */ }
  cfgToForm();
}
function persistUploadCfg(){
  try{ localStorage.setItem(UPLOAD_STORAGE_KEY, JSON.stringify(uploadCfg)); return true; }
  catch(_){ return false; }   // in-memory only for this page load
}
function cfgToForm(){
  $("cfgEndpoint").value = uploadCfg.endpoint;
  $("cfgScheme").value   = uploadCfg.scheme;
  $("cfgToken").value    = uploadCfg.token;
  $("cfgHeader").value   = uploadCfg.header;
  $("cfgLabel").value    = uploadCfg.label;
  syncSchemeFields();
}
function formToCfg(){
  uploadCfg = {
    endpoint: $("cfgEndpoint").value.trim(),
    scheme:   $("cfgScheme").value,
    token:    $("cfgToken").value.trim(),
    header:   ($("cfgHeader").value.trim() || "X-API-Key"),
    label:    $("cfgLabel").value.trim()
  };
}
function syncSchemeFields(){
  const scheme = $("cfgScheme").value;
  $("cfgToken").disabled  = scheme === "none";
  $("cfgHeader").disabled = scheme !== "apikey";
}

/* --- payload --- */
function knownRingTable(ringAddr){
  const key = String(ringAddr || "").trim().toUpperCase();
  return CALIBRATION_TABLES[key] ? key : CALIBRATION_FALLBACK_RING + " (fallback)";
}

function buildPayload(isTest){
  const t0   = recorded.length ? recorded[0].epoch : null;
  const tEnd = recorded.length ? recorded[recorded.length - 1].epoch : null;
  const ohmsOf = (v, ring) => CONVERSION_ENABLED ? adcToOhms(v, ring) : null;
  return {
    schemaVersion: "1.0",
    source: "OperatorSense Live (EDA ring, FTDI Web Serial)",
    sentAtIso: new Date().toISOString(),
    test: !!isTest,
    capture: {
      captureId: captureId || newCaptureId(),
      label: uploadCfg.label || null,
      startedAtIso: t0   === null ? null : new Date(t0).toISOString(),
      endedAtIso:   tEnd === null ? null : new Date(tEnd).toISOString(),
      durationMs:   t0 === null ? 0 : tEnd - t0,
      sampleCount:  isTest ? 0 : recorded.length,
      eventCount:   isTest ? 0 : events.length,
      baudRate: parseInt($("baud").value, 10)
    },
    device: {
      dongleAddr: lastPacket ? lastPacket.dongle : null,
      ringAddr:   lastPacket ? lastPacket.ring   : null,
      usbVendorId: "0x" + FTDI_VENDOR_ID.toString(16).toUpperCase().padStart(4, "0")
    },
    signal: {
      unit: "raw_adc",
      ohmsAvailable: CONVERSION_ENABLED,
      calibration: CONVERSION_ENABLED ? {
        source: "CALIBRATION.xlsx (device developer)",
        ringTable: knownRingTable(lastPacket && lastPacket.ring),
        rangeKOhm: [1, 100.2]
      } : null
    },
    samples: isTest ? [] : recorded.map(r => ({
      epochMs: r.epoch, elapsedMs: r.epoch - t0, valueAdc: r.value,
      ohms: ohmsOf(r.value, r.ring),
      microSiemens: ohmsToMicroSiemens(ohmsOf(r.value, r.ring)),
      inCalibratedRange: adcInCalibratedRange(r.value, r.ring),
      packetId: r.pktId, channel: r.channel, dongleAddr: r.dongle, ringAddr: r.ring, raw: r.raw
    })),
    events: isTest ? [] : events.map(ev => ({
      epochMs: ev.epoch, elapsedMs: t0 === null ? null : ev.epoch - t0, label: ev.label
    }))
  };
}

function uploadHeaders(){
  const h = { "Content-Type": "application/json" };
  if(uploadCfg.scheme === "bearer" && uploadCfg.token) h["Authorization"] = "Bearer " + uploadCfg.token;
  if(uploadCfg.scheme === "apikey" && uploadCfg.token) h[uploadCfg.header] = uploadCfg.token;
  return h;
}

async function postPayload(payload){
  const ctrl  = new AbortController();
  const timer = setTimeout(()=>ctrl.abort(), UPLOAD_TIMEOUT_MS);
  try{
    const res = await fetch(uploadCfg.endpoint, {
      method: "POST", headers: uploadHeaders(),
      body: JSON.stringify(payload), signal: ctrl.signal
    });
    if(!res.ok){
      let detail = "";
      try{ detail = (await res.text()).trim().slice(0, 160); }catch(_){}
      throw new Error("HTTP " + res.status + " " + res.statusText + (detail ? " — " + detail : ""));
    }
    return res.status;
  }catch(e){
    if(e.name === "AbortError") throw new Error("Timed out after " + (UPLOAD_TIMEOUT_MS/1000) + " s — the server did not answer");
    if(e.name === "TypeError")  throw new Error("Network/CORS error — check the URL, that the server is reachable, and that it allows this page's origin (file:// pages send Origin: null)");
    throw e;
  }finally{ clearTimeout(timer); }
}

async function sendCapture(isTest){
  if(uploadBusy) return;
  if(!uploadCfg.endpoint){
    openUploadCfg(true);
    setUploadState("No endpoint configured — fill in the URL and press \u201cSave settings\u201d", "err");
    return;
  }
  if(!isTest && !recorded.length){ alert("Nothing recorded yet. Press \u201cStart recording\u201d first."); return; }
  if(uploadCfg.scheme !== "none" && !uploadCfg.token &&
     !confirm("No API key is set. Send to the server without authentication?")) return;

  uploadBusy = true; uploadNotice = null;
  refreshUploadUi();
  const n = isTest ? 0 : recorded.length;
  setUploadState(isTest ? "Testing connection\u2026" : "Uploading " + n + " samples\u2026", "busy");
  try{
    const status = await postPayload(buildPayload(isTest));
    uploadNotice = { kind:"ok",
      text: (isTest ? "Test OK" : "Sent " + n + " samples") + " — HTTP " + status +
            " \u00b7 " + new Date().toLocaleTimeString() };
  }catch(e){
    uploadNotice = { kind:"err", text: (isTest ? "Test failed: " : "Upload failed: ") + e.message };
  }finally{
    uploadBusy = false;
    refreshUploadUi();
  }
}

/* --- ui state --- */
function setUploadState(text, kind){
  const key = kind + "|" + text;
  if(lastPaintedState === key) return;
  lastPaintedState = key;
  const el = $("uploadState");
  el.textContent = text;
  el.className = "status" + (kind && kind !== "muted" ? " " + kind : "");
}
function refreshUploadUi(){
  const configured = !!uploadCfg.endpoint;
  $("uploadBtn").disabled  = uploadBusy || !configured || recorded.length === 0;
  $("cfgTestBtn").disabled = uploadBusy;
  if(uploadBusy) return;                       // the sender owns the text while it runs
  if(uploadNotice){ setUploadState(uploadNotice.text, uploadNotice.kind); return; }
  if(!configured){ setUploadState("Not configured — open Server settings", "muted"); return; }
  setUploadState(recorded.length
    ? "Ready — " + recorded.length + " samples queued"
    : "Ready — nothing recorded yet", "muted");
}
function openUploadCfg(open){
  $("uploadCfg").classList.toggle("hidden", !open);
}

/* --- wiring --- */
$("uploadBtn").addEventListener("click", ()=> sendCapture(false));
$("uploadCfgBtn").addEventListener("click", ()=> openUploadCfg($("uploadCfg").classList.contains("hidden")));
$("cfgScheme").addEventListener("change", syncSchemeFields);
$("cfgSaveBtn").addEventListener("click", ()=>{
  formToCfg();
  const stored = persistUploadCfg();
  uploadNotice = { kind: uploadCfg.endpoint ? "ok" : "muted",
                   text: uploadCfg.endpoint
                     ? "Settings saved" + (stored ? "" : " (this page load only — browser storage unavailable)")
                     : "No endpoint set — uploads stay disabled" };
  refreshUploadUi();
});
$("cfgTestBtn").addEventListener("click", ()=>{ formToCfg(); persistUploadCfg(); sendCapture(true); });
$("cfgForgetBtn").addEventListener("click", ()=>{
  if(!confirm("Clear the stored endpoint and API key from this browser?")) return;
  uploadCfg = Object.assign({}, UPLOAD_DEFAULTS);
  try{ localStorage.removeItem(UPLOAD_STORAGE_KEY); }catch(_){}
  cfgToForm();
  uploadNotice = null;
  refreshUploadUi();
});

loadUploadCfg();
refreshUploadUi();
```

---

## 14. Stav používateľského rozhrania

Jedno miesto, kde sa zapínajú a vypínajú tlačidlá a píše stavový riadok, takže zvyšok kódu nemusí
riešiť, ktoré ovládacie prvky v ktorom stave dávajú zmysel.

```javascript
/* ---------- ui state ---------- */
function setConnectedUI(on){
  $("connectBtn").disabled = on;
  $("disconnectBtn").disabled = !on;
  $("baud").disabled = on;
  $("recBtn").disabled = !on;
  $("markBtn").disabled = !on;
  $("csvBtn").disabled = !on;
  $("clearBtn").disabled = !on;
  if(!on){
    recording = false;
    $("recBtn").textContent = "● Start recording";
    $("recBtn").className = "go";
  }
  refreshUploadUi();
}
function setStatus(text, kind){
  $("statusText").textContent = text;
  const dot = $("dot");
  dot.className = "dot" + (kind === "on" ? " on" : kind === "err" ? " err" : "");
}

// auto-reconnect cleanup if the device is unplugged
if("serial" in navigator){
  navigator.serial.addEventListener("disconnect", e => {
    if(port && e.target === port){ disconnect(); setStatus("Dongle unplugged", "err"); }
  });
}

sizeCanvas();
```

Súbor sa uzatvára takto:

```html
</script>
</body>
</html>
```

---

## 15. Prehľad funkcií

| Funkcia | Účel |
|---|---|
| `adcToOhms(adc, ringAddr)` | ADC kód -> odpor v ohmoch podľa kalibračnej tabuľky prsteňa. |
| `adcInCalibratedRange(adc, ringAddr)` | Či hodnota leží v nameranom rozsahu. |
| `ohmsToMicroSiemens(ohms)` | Odpor -> vodivosť. |
| `connect()` / `disconnect()` | Otvorenie/zatvorenie sériového portu a reset živého zobrazenia. |
| `readLoop()` | Nepretržité čítanie; skladá kompletné textové riadky. |
| `parseLine(line)` | Sériový riadok -> `{value, pktId, channel, dongle, ring, raw}`. |
| `handleLine(line)` | Pošle jednu vzorku do logu, grafu, merania frekvencie a záznamu. |
| `appendRaw(line)` | Kruhový buffer surového logu so zachovaním pozície posúvania. |
| `render()` | Slučka 20 Hz: štatistiky, prepočítaný údaj, graf. |
| `updateDuration()` / `updateRate()` | Dĺžka záznamu a nameraná vzorkovacia frekvencia. |
| `drawChart()` / `sizeCanvas()` | Vykreslenie grafu a prispôsobenie plátna vysokému rozlíšeniu. |
| `csvNum(x, decimals)` | Formátovanie čísel pre CSV (prázdna bunka namiesto `NaN`). |
| `downloadCsv()` | Zostaví a stiahne CSV; značky udalostí priradí k najbližším vzorkám. |
| `newCaptureId()` | Identifikátor jedného nahrávania pre rozpoznanie duplicít na serveri. |
| `loadUploadCfg()` / `persistUploadCfg()` | Načítanie/uloženie nastavení v `localStorage`. |
| `cfgToForm()` / `formToCfg()` / `syncSchemeFields()` | Prepojenie formulára nastavení. |
| `buildPayload(isTest)` | Zostaví JSON so záznamom (alebo prázdny testovací ping). |
| `uploadHeaders()` | Typ obsahu a zvolená autentifikačná hlavička. |
| `postPayload(payload)` | `fetch` s 30 s timeoutom a zrozumiteľnými chybovými hláškami. |
| `sendCapture(isTest)` | Celý priebeh odoslania vrátane stavu „prebieha“ a výsledku. |
| `setUploadState()` / `refreshUploadUi()` / `openUploadCfg()` | Stav rozhrania odosielania. |
| `setConnectedUI(on)` / `setStatus(text, kind)` | Globálny stav rozhrania a stav pripojenia. |

---

## 16. Bežné úpravy

| Zmena | Kde |
|---|---|
| Pridať prsteň alebo upraviť kalibráciu | `CALIBRATION_TABLES` (časť 4). |
| Odovzdať kópiu s predvyplneným serverom | `UPLOAD_DEFAULTS` (časť 5). |
| Iná predvolená prenosová rýchlosť | Možnosti v `<select id="baud">` (časť 2). |
| Dlhšie/kratšie okno grafu | `CHART_MAX` (časť 6). |
| Iný formát paketu | Iba `parseLine()` (časť 8). |
| Ďalšie stĺpce v CSV | Hlavička a mapovanie riadkov v `downloadCsv()` (časť 12); obdoba pre JSON je v časti 13. |
| Prefarbenie | CSS premenné v `:root` (časť 3). |
