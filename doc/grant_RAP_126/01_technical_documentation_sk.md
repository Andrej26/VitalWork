# Technická dokumentácia — senzorický riadiaci modul VitalWork

**Projekt:** RAP_126 — Vývoj prototypu senzorického riadiaceho modulu pre monitorovanie
fyziologického stavu operátora počas simulovaných pracovných scenárov
**Verzia dokumentu:** 1.0
**Dátum:** 2026-07-22
**Jazyk:** Slovenčina (formálny výstup); technická dokumentácia zdrojového kódu je v angličtine.

---

## Obsah

1. [Účel dokumentu](#1-účel-dokumentu)
2. [Prehľad systému](#2-prehľad-systému)
3. [Hardvérové komponenty](#3-hardvérové-komponenty)
4. [Softvérová architektúra](#4-softvérová-architektúra)
5. [Komunikačné protokoly](#5-komunikačné-protokoly)
6. [Dátový model](#6-dátový-model)
7. [Tok dát a spracovanie](#7-tok-dát-a-spracovanie)
8. [Bezpečnosť a oprávnenia](#8-bezpečnosť-a-oprávnenia)
9. [Závislosť od externých nástrojov](#9-závislosť-od-externých-nástrojov)
10. [Postup zostavenia a nasadenia](#10-postup-zostavenia-a-nasadenia)
11. [Schéma exportu dát (JSON/CSV)](#11-schéma-exportu-dát-jsoncsv)

---

## 1. Účel dokumentu

Tento dokument je formálnym technickým výstupom grantovej aktivity **Activity 2** (modelovanie /
simulácia) projektu RAP_126. Popisuje architektúru, komponenty, komunikačné protokoly a dátový model
systému **VitalWork** — senzorického riadiaceho modulu na monitorovanie fyziologického stavu operátora
počas piatich simulovaných biofeedback pracovných scenárov.

Systém je prototypom určeným na **technické overenie merania fyziologických parametrov** v
kontrolovaných podmienkach. Druhé zariadenie spárované cez lokálnu Wi-Fi umožňuje operátorovi sledovať
živú obrazovku monitorovaného zariadenia počas scenára; slúži výhradne ako pozorovacia pomôcka a na
samotnom meraní sa nijako nepodieľa.

---

## 2. Prehľad systému

### 2.1 Schéma architektúry

*Pozri diagram 01 — Architektúra systému:* `diagrams/png/01_system_architecture_en.png`

Systém VitalWork tvoria štyri fyzické komponenty prepojené cez lokálnu Wi-Fi sieť a Bluetooth:

| Komponent | Úloha |
|-----------|-------|
| **Monitorované zariadenie** (Android tablet/telefón, rola klienta) | Centrálny uzol: zber dát zo senzorov, správa sedenia, lokálny export a nahranie na server |
| **eSense Pulse** (Mindfield Biosignals) | Snímač srdcovej frekvencie a R-R intervalov cez BLE |
| **eSense Respiration** (Mindfield Biosignals) | Snímač dýchacej frekvencie cez audio konektor monitorovaného zariadenia |
| **Galaxy Watch 8** (Samsung) | Snímač EDA, srdcovej frekvencie a IBI; posiela dáta cez Wearable Data Layer na monitorované zariadenie |
| **Zobrazovacie zariadenie** (Android tablet/telefón, rola servera) | Druhé zariadenie operátora; sleduje živú obrazovku monitorovaného zariadenia cez priame Wi-Fi spojenie |

Centrálny server VitalWork (mimo zariadenia) prijíma nahraté sedenia cez HTTP.

### 2.2 Cieľ merania

Systém zaznamenáva **fyziologický stav operátora** počas práce na piatich biofeedback scenároch (A–E):
Referenčný stav, Kognitívna záťaž, Rušivé prostredie, Dlhodobá únava a Reakčné úlohy. Všetky časové
pečiatky pochádzajú z jedných NTP-korigovaných hodín na monitorovanom zariadení (`TimeProvider`), takže
vzorky zo všetkých senzorov zdieľajú spoločnú UTC časovú os bez ďalšej synchronizácie hodín medzi
zariadeniami.

Paralelne sú zaznamenávané fyziologické signály:

- Srdcová frekvencia (BPM) a R-R intervaly (ms) — eSense Pulse
- Amplitúda dýchania — eSense Respiration
- Srdcová frekvencia (BPM), IBI (ms) a elektrodermálna aktivita EDA (µS) — Galaxy Watch 8

Tieto signály umožňujú korelovať fyziologický stav operátora s nárokmi jednotlivých scenárov —
primárny analytický výstup modulu.

---

## 3. Hardvérové komponenty

### 3.1 eSense Pulse (Mindfield Biosignals)

| Vlastnosť | Hodnota |
|-----------|---------|
| Typ | Hrudný remienok — PPG senzor |
| Rozhranie | Bluetooth Low Energy (BLE), Heart Rate Profile (Bluetooth SIG 0x180D) |
| Signály | Srdcová frekvencia (BPM), R-R intervaly (ms) |
| Vzorkovacia frekvencia | ≈ 4,5 Hz (225 ms perióda; nominálne 5 Hz) |
| Presnosť | ±2 BPM (rozsah 30–240 BPM) |
| Interný PPG | 500 Hz, spracovanie na zariadení pred BLE prenosom |

**Identifikácia zariadenia:** prefix názvu `eSense` alebo Manufacturer ID `0xFF0C` v BLE advertising
dátach. Zariadenie používa náhodný typ BLE adresy.

**Protokol:** GATT charakteristika `0x2A37` (Heart Rate Measurement), notifikácie povolené zápisom
na CCCD `0x2902`. Každá notifikácia obsahuje BPM hodnotu (UINT8 alebo UINT16) a voliteľne R-R
intervaly (UINT16 LE, rozlíšenie 1/1024 s, konvertované na ms). Nulové R-R hodnoty sú zahadzované.

### 3.2 eSense Respiration (Mindfield Biosignals)

| Vlastnosť | Hodnota |
|-----------|---------|
| Typ | Hrudný remienok — senzor rozťahovania hrudníka |
| Rozhranie | Audio konektor (3,5 mm jack) |
| Signál | Amplitúda dýchania (RA) — surový priebeh rozťahovania a sťahovania hrudníka |
| Vzorkovacia frekvencia | 5 Hz (konfigurovateľné cez SDK) |
| Odvodzovaný parameter (iba UI) | Dychová frekvencia (breaths/min) z detekcie nádychov (vyhladenie, odstránenie driftu základnej línie, hysterézia škálovaná amplitúdou) na 60-sekundovom okne — počíta sa pre živé zobrazenie, neukladá sa ako vzorka |
| Živé varovania kvality signálu | **Strata signálu** (RA pod 0,8 — remienok skĺzol z hrudníka) a **Nezistené dýchanie** (normálna úroveň RA, ale žiadny dychový priebeh) — zobrazované ako bannery počas nastavenia aj nahrávania |

**Nyquistovo kritérium:** normálna dychová frekvencia je 0,2–0,33 Hz; pri 5 Hz je prevzorkovaná viac
ako 15-násobne — dostatočná rezerva.

### 3.3 Samsung Galaxy Watch 8

| Vlastnosť | Hodnota |
|-----------|---------|
| Typ | Inteligentné hodinky (Wear OS 6 / One UI Watch) |
| SDK | Samsung Health Sensor SDK (lokálny AAR, modul `:wear`) |
| Rozhranie | Wearable Data Layer (Bluetooth; bez internetu) |
| Signály | HR (BPM), IBI (ms), EDA (µS) |
| Vzorkovacia frekvencia | 1 Hz (natívna) |

> **Kľúčový dizajnový zámer:** Galaxy Watch 8 nemá štandardný BLE Heart Rate Profile ani prístupný
> BLE GATT pre EDA. Samsung Health Sensor SDK beží výhradne **na hodinkách**. Preto je nutná
> dvojaplikačná architektúra: companion app (modul `:wear`) číta senzory na hodinkách a posiela
> merania na monitorované zariadenie cez Wearable Data Layer.

**Store-and-forward:** hodinky ukladajú každé meranie HR/IBI/EDA do lokálneho trvalého úložiska
(súbor `WatchSampleStore`), takže sedenie so zhasnutou obrazovkou (Doze) nestráca žiadne dáta. Na
konci sedenia monitorované zariadenie posiela príkaz `FLUSH`; hodinky vrátia uložené riadky ako
`DataClient` DataItems (spoľahlivý prenos, vyrovnávaný počas krátkych výpadkov). Po prijatí
monitorované zariadenie odosiela `FLUSH_ACK` a hodinky skrátia úložisko po potvrdenú časovú pečiatku.

**Nepretržité doručovanie na pozadí** vyžaduje foreground `health` službu na hodinkách,
`BODY_SENSORS_BACKGROUND` a slučku `HealthTracker.flush()` s frekvenciou 1 Hz. **Spojenie musí bežať
cez priamy Bluetooth** — ak je Bluetooth na telefóne vypnutý, Data Layer sa bez upozornenia prepne na
cloudový relay, ktorý nedokáže doručiť dáta na telefón v režime Doze; UI hodiniek zobrazuje varovanie
o vypnutom Bluetooth, aby sa tomuto zlyhaniu predišlo.

---

## 4. Softvérová architektúra

### 4.1 Modulová štruktúra

Projekt pozostáva z dvoch Gradle modulov:

| Modul | Popis | Min. SDK |
|-------|-------|----------|
| `:app` | Monitorované / zobrazovacie zariadenie — Android tablet alebo telefón | API 24 (Android 7.0) |
| `:wear` | Companion app — Galaxy Watch (Wear OS) | API 28 (Android 9.0) |

**Technologický zásobník (`:app`):**

| Vrstva | Technológia |
|--------|-------------|
| Jazyk | Kotlin 2.3.0 |
| UI | Jetpack Compose (BOM 2026.01.00), Material Design 3 |
| Vkladanie závislostí | Hilt / Dagger |
| Lokálna databáza | Room 2.7.1 |
| HTTP klient (upload) | Ktor 3.3.0 (CIO) |
| NTP synchronizácia | Kronos (Lyft) |
| Peer link | Java-WebSocket + mDNS (Wi-Fi, port 9090) |
| Zrkadlenie obrazovky | stream-webrtc-android (WebRTC, peer-to-peer) |
| Watch link | Play Services Wearable (Data Layer) |

### 4.2 Vrstvená architektúra

*Pozri diagram 01 — Architektúra systému*

```
MainActivity
└── VitalWorkApplication  (Hilt app trieda)
    └── VitalWorkTheme    (Material 3 téma, natrvalo svetlá)
        └── AppNavigation (NavHost — Compose navigácia)
            └── Composable obrazovky
```

Prezentačná vrstva (ViewModely + Compose) komunikuje výhradne cez vrstvu dát (repozitáre a
singleton receivery). Žiadna UI trieda nepristupuje priamo k databáze ani k sieťovým klientom.

**Kľúčové singleton komponenty (Hilt):**

| Komponent | Zodpovednosť |
|-----------|--------------|
| `PeerLinkManager` | Priame spojenie zariadenie-zariadenie cez WebSocket (rola server/klient, mDNS vyhľadávanie) |
| `WatchSensorReceiver` | Príjemca živých meraní z hodiniek + flush DataItems; sleduje stav spojenia (LIVE/DOZING/DISCONNECTED) |
| `ScenarioRecordingRepositoryImpl` | Riadenie nahrávania (start/stop) + ukladanie vzoriek do DB |
| `TimeProvider` | NTP-korigované wall-clock hodiny (Kronos) |
| `KeepAliveCoordinator` | Množina dôvodov (SESSION / LINK / SCREEN_SHARE) riadiaca životný cyklus foreground služby |

### 4.3 Foreground service

`BackgroundConnectionService` je jediná celoaplikačná foreground služba, ktorá udržuje proces, peer
link a zrkadlenie obrazovky aktívne pri zhasnutej obrazovke, pokiaľ je aktívny aspoň jeden dôvod
(`SESSION`, `LINK`, `SCREEN_SHARE`). Spustí sa hneď, ako sa množina dôvodov stane neprázdnou, a
zastaví sa, keď zaniknú všetky dôvody.

### 4.4 Navigačné trasy

| Trasa | Obrazovka | Popis |
|-------|-----------|-------|
| `mode` | ModeSelectionScreen | Výber role Server/Klient pri prvom spustení |
| `tutorial` | TutorialScreen | Úvodný onboarding (prvé spustenie) |
| `home` | HomeScreen | Prehľad prispôsobený role (rozloženie pre Server vs. Klient) |
| `link/{role}` | PeerLinkScreen | Spojenie zariadenie-zariadenie: párovanie, diagnostika, ovládanie zrkadlenia obrazovky |
| `settings` | SettingsScreen | Prefix zariadenia (A/B/C/D) a prepínač role Server/Klient |
| `sensors` | SensorsScreen | Zoznam dostupných senzorov |
| `sensors/{sensorId}` | SensorDetailScreen | Smerovanie na obrazovku špecifickú pre daný senzor |
| `participants/new` | ParticipantEntryScreen | Zadanie anonymizovaného účastníka (vytvára účastníka + sedenie) |
| `sessions` | SessionsScreen | Zoznam ukončených sedení |
| `sessions/setup/{sessionId}` | SessionControlScreen (setup mód) | Jednorazová kontrola pripojenia senzorov po zadaní účastníka |
| `sessions/scenario-select/{sessionId}` | ScenarioSelectionScreen | Rozcestník scenárov: výber A–E alebo ukončenie sedenia |
| `sessions/active/{sessionId}` | SessionControlScreen | Nahrávanie zvoleného scenára (auto-štart + odpočet) |
| `sessions/review/{sessionId}` | SessionDetailScreen | Prehľad sedenia, lokálny export, nahranie |

---

## 5. Komunikačné protokoly

### 5.1 Spojenie zariadenie-zariadenie (zobrazovacie ↔ monitorované zariadenie)

*Pozri diagram 01 — Architektúra systému:* `diagrams/png/01_system_architecture_en.png`

**Úlohy:**

| Zariadenie | Rola |
|------------|------|
| Zobrazovacie zariadenie | WebSocket server (Java-WebSocket, port 9090), inzerovaný cez mDNS |
| Monitorované zariadenie | WebSocket klient — vyhľadá zobrazovacie zariadenie a pripojí sa |

**Vyhľadanie a párovanie (iniciované klientom):**

1. Zobrazovacie zariadenie (rola servera) sa inzeruje cez mDNS.
2. Monitorované zariadenie (rola klienta) nájde službu a otvorí WebSocket spojenie na
   `ws://{ipZobrazovacieho}:9090`.
3. Ten istý WebSocket nesie párovanie, voľný textový diagnostický log a **WebRTC signalizáciu**
   (offer/answer/ICE kandidáti) pre zrkadlenie obrazovky.
4. Po dokončení signalizácie monitorované zariadenie streamuje svoju živú obrazovku zobrazovaciemu
   zariadeniu **priamo peer-to-peer cez WebRTC/UDP** — samotné video nikdy neprechádza cez WebSocket.

Spojenie beží **v jednej role naraz** na zariadenie (`PeerLinkManager.activeRole`) a je udržiavané pri
spánku cez `BackgroundConnectionService`. Nešifrovaná prevádzka je povolená iba pre lokálnu sieť
(`network_security_config.xml`) a zachytávanie obrazovky vyžaduje oprávnenia
`FOREGROUND_SERVICE_MEDIA_PROJECTION` a `POST_NOTIFICATIONS`.

**Nákladový model:** zrkadlenie obrazovky je bezplatné na prevádzku — bez media servera, bez cloud
relay, jediný externý prvok je bezplatný verejný STUN server Google na zisťovanie lokálnych adries.
Jediné reálne náklady sú batéria/teplo na zdieľajúcom zariadení a lokálna Wi-Fi šírka pásma. Použitie
mimo lokálnej siete by vyžadovalo TURN relay, ktorý tento systém nepoužíva.

### 5.2 BLE (eSense Pulse)

Monitorované zariadenie skenuje BLE zariadenia (nefiltrovane, nízka latencia), identifikuje eSense
Pulse podľa názvu alebo Manufacturer ID, pripája sa cez GATT a povolí notifikácie na charakteristike
`0x2A37`. Parser (`BleParsers.kt`) spracováva UINT8 aj UINT16 formát a extrahuje R-R intervaly.

### 5.3 Audio (eSense Respiration)

eSense SDK číta analógový signál z audio konektora (3,5 mm jack). Aplikácia konfiguruje vzorkovaciu
frekvenciu 5 Hz. Dychová frekvencia zobrazovaná v UI sa odvodzuje estimátorom nádychov na
60-sekundovom posuvnom okne: RA priebeh sa vyhladí, zbaví driftu voči pohyblivej základnej línii a
nádychy sa detegujú hysteréziou škálovanou amplitúdou; frekvencia je orezaný medián intervalov medzi
nádychmi. Estimátor bol doladený na reálnych záznamoch z hrudného remienka a vydáva aj dva verdikty
kvality použité pre živé varovania: *strata signálu* (RA < 0,8 — remienok mimo hrudníka) a
*nezistené dýchanie* (normálna úroveň RA bez dychovej modulácie).

### 5.4 Wearable Data Layer (Galaxy Watch 8)

*Pozri diagram 05 — Store-and-forward Galaxy Watch:* `diagrams/png/05_watch_store_forward_en.png`

**Živé dáta:** `WatchSensorService` (hodinky) → `MessageClient` → `WatchListenerService`
(monitorované zariadenie) → `WatchSensorReceiver`.

**Flush (koniec sedenia):**
- `WatchCommandSender` posiela `FLUSH`
- `WatchCommandListenerService` (hodinky) vyprázdni vyrovnávaciu pamäť SDK do úložiska, potom
  `WatchFlushWriter` zabalí uložené riadky do dávkovaných DataItems
- `WatchListenerService.onDataChanged` (monitorované zariadenie) ich prijme a spracuje, a odošle
  `FLUSH_COMPLETE`
- `WatchSessionDrainer` priradí každý riadok k scenáru podľa časového okna a odstráni duplicity voči
  vzorkám už zapísaným naživo
- Monitorované zariadenie odošle `FLUSH_ACK:<maxTimestamp>` a hodinky skrátia úložisko po túto
  časovú pečiatku, pričom si ponechajú prípadný nepotvrdený zvyšok pre ďalší flush

**Stav spojenia:** nízkofrekvenčný `HEARTBEAT` každých 30 s udržuje indikátor spojenia s hodinkami na
monitorovanom zariadení v stave DOZING (nie false "Disconnected"), keď sa živé streamovanie počas
Doze pozastaví — dáta sú bezpečne v úložisku a flush ich získa. Automatické zobudenie hodiniek pri
flush je best-effort; záložným riešením je manuálne ťuknutie na hodinky, no žiadne dáta sa nestratia,
keďže úložisko je trvalé až do potvrdenia.

### 5.5 NTP synchronizácia

Monitorované zariadenie používa Kronos (`TimeProvider.nowMs()`) pre všetky ukladané a exportované
časové pečiatky. Vzorky každého senzora — eSense, Galaxy Watch aj akákoľvek časová pečiatka
zaznamenaná počas scenára — zdieľajú tie isté hodiny, takže zarovnanie naprieč tokmi dát nevyžaduje
žiadnu ďalšiu synchronizáciu hodín medzi zariadeniami.

---

## 6. Dátový model

*Pozri diagram 03 — Dátový model (ER schéma):* `diagrams/png/03_data_model_en.png`

Room databáza (schéma v6) so 4 entitami; kaskádové mazanie na všetkých cudzích kľúčoch. Schéma
používa `fallbackToDestructiveMigration`, takže zvýšenie verzie vymaže staré lokálne riadky bez
ručne písanej migrácie — prijateľné, keďže sedenia sú v čase zmeny schémy už exportované/nahraté.

| Entita | Tabuľka | Účel |
|--------|---------|------|
| `ParticipantEntity` | `participants` | Anonymizovaný účastník (`participantCode`) |
| `SessionEntity` | `sessions` | Sedenie (FK → participants; status: ACTIVE, COMPLETED, UPLOADED) |
| `ScenarioEntity` | `scenarios` | Jeden priebeh biofeedback scenára v rámci sedenia (FK → sessions; kód, `startedAt`/`endedAt`) |
| `SensorSampleEntity` | `sensor_samples` | Časový rad vzoriek (FK → scenarios; `timestampMs`, `elapsedMs`, `sensorType`, `value`) |

### 6.1 Enumerácie

| Enum | Hodnoty |
|------|---------|
| `SessionStatus` | `ACTIVE`, `COMPLETED`, `UPLOADED` |
| `ScenarioCode` | `REFERENCE_STATE` (A), `COGNITIVE_LOAD` (B), `DISTRACTING_ENVIRONMENT` (C), `LONG_TERM_FATIGUE` (D), `REACTION_TASKS` (E) |
| `SensorType` | `ESENSE_HEART_RATE`, `ESENSE_RR_INTERVAL`, `RESPIRATION`, `WATCH_HR`, `WATCH_IBI`, `WATCH_EDA` |

`ScenarioCode` nesie ako vlastnosti enumu krátky oficiálny kód (A–E) a zobrazovaný názov — v databáze
a na serveri sa ukladá samotný *názov* konštanty (napr. `REFERENCE_STATE`), takže popisné názvy sa
môžu meniť bez porušenia existujúcich záznamov.

### 6.2 Kódovanie identifikátorov

Každé zariadenie má pridelený prefix (A/B/C/D) konfigurovaný v nastaveniach (`SettingsRepository`,
SharedPreferences, predvolene `A`). Prefix označuje kódy účastníkov (`A-001-260722-143022`) aj
sedení (`VW-A-yyMMdd-HHmmss`), takže viac zariadení testujúcich paralelne nikdy nevytvorí kolidujúce
kódy. Operátori sa musia vopred dohodnúť, ktoré písmeno patrí ktorému zariadeniu; počítadlo je vedené
samostatne pre každý prefix (`ParticipantDao.getParticipantCountByPrefix`), takže kolíziám sa
predchádza iba medzi zariadeniami s odlišnými literami.

### 6.3 Uchovávanie predpočítaných počtov vzoriek

`SessionEntity` uchováva predpočítané počty vzoriek podľa typu (`hrSampleCount`,
`respirationSampleCount`, `rrIntervalSampleCount`, `edaSampleCount`, `watchHrSampleCount`,
`watchIbiSampleCount`) spolu s počtom scenárov, aby sa prehľadová obrazovka vykresľovala bez
opakovaného dotazovania na `sensor_samples`. Tieto počty sa vypočítajú raz pri ukončení sedenia zo
vzoriek zaznamenaných v databáze.

### 6.4 História schémy

Schéma sa vyvíjala počas biofeedback pivotu: v2 pridala `WATCH_IBI`; v3 rozdelila typy senzorov podľa
zariadenia; v4 pridala počítadlá vzoriek z hodiniek; v5 odstránila polia pre reakčný čas/VR
(`scenarioCategory`, `eventTimestampMs`, `reactionTimestampMs`) a `sessions.notes`; v6 premenovala
deväť priemyselných kódov scenárov na päť biofeedback scenárov popísaných v §6.1. Bývalá VR fáza —
spojenie s Meta Quest, HTTP server na zariadení a UDP discovery beacon — bola v tomto pivote úplne
odstránená a nie je súčasťou aktuálneho systému.

---

## 7. Tok dát a spracovanie

*Pozri diagram 04 — Tok dát:* `diagrams/png/04_data_flow_pipeline_en.png`

### 7.1 Nahrávanie scenára

```
Operátor zvolí scenár A–E → ScenarioRecordingRepositoryImpl.startRecording()
    ├─ eSense Pulse (~4,5 Hz)    → ESENSE_HEART_RATE + ESENSE_RR_INTERVAL
    ├─ eSense Respiration (5 Hz) → RESPIRATION
    └─ Galaxy Watch (1 Hz)       → WATCH_HR + WATCH_IBI + WATCH_EDA (živý stream)

Vzorky sa dávkujú (50 vzoriek alebo 1 s flush interval, podľa toho, čo nastane skôr) pred zápisom do
Room DB. Dáta z hodiniek túto vyrovnávaciu pamäť preskakujú — prídu v jednej dávke na konci sedenia
(§7.2).

Operátor sa vráti do rozcestníka scenárov → ScenarioRecordingRepositoryImpl.stopRecording()
    → zapíše endedAt do ScenarioEntity
    → ukončí zber vzoriek pre tento scenár
```

### 7.2 Koniec sedenia a flush hodiniek

*Pozri diagram 05 — Store-and-forward Galaxy Watch:* `diagrams/png/05_watch_store_forward_en.png`

```
Operátor ukončí sedenie
    → WatchCommandSender.sendFlush()
    → Hodinky vyprázdnia svoju vyrovnávaciu pamäť SDK, potom odošlú uložené riadky (dávkované
      DataClient DataItems)
    → WatchListenerService.onDataChanged() prijme a spracuje riadky; hodinky odošlú FLUSH_COMPLETE
    → WatchSessionDrainer priradí vzorky k scenárom podľa časového okna, s odstránením duplicít
      voči naživo streamovaným vzorkám
    → ScenarioRecordingRepositoryImpl zapíše priradené riadky do DB
    → FLUSH_ACK:<maxTimestamp> → hodinky skrátia úložisko po túto časovú pečiatku
    → SessionEntity.status = COMPLETED
```

### 7.3 Export a nahranie

```
Koniec sedenia (automaticky)
    → SessionHttpUploader (Ktor klient) → POST /api/sessions/upload na server VitalWork
        (jeden idempotentný POST, identifikovaný kódom sedenia sessionCode; bezpečný na opakovanie)
    → Pri úspechu: SessionEntity.status = UPLOADED

Voliteľne, na podnet operátora (obrazovka prehľadu sedenia)
    → SessionExportService → JSON + CSV zapísané do priečinka Documents
```

Timeout nahrania: `REQUEST_TIMEOUT_MS = 600 000 ms` — celkový čas prenosu tela; dlhé sedenia
s tisíckami vzoriek môžu byť pomalšie na slabšom spojení. Neúspešné nahranie možno manuálne
zopakovať z obrazovky prehľadu sedenia.

### 7.4 Analytický modul

**Implementované v prototype:**
- Detekcia medzier v senzorových dátach (`GapDetector`) — pre každý typ senzora a každý scenár
- Správa o kompletnosti flush hodiniek (`WatchReconciliationReport` — claimed/received/in-window/db
  rows)

**Navrhnuté (nie implementované v aktuálnom prototype):**
- Automatické porovnanie nameraných hodnôt s referenčnými fyziologickými prahmi
- Agregácia a štatistická analýza naprieč viacerými sedeniami

---

## 8. Bezpečnosť a oprávnenia

### 8.1 Android oprávnenia (`:app`)

| Oprávnenie | Účel |
|------------|------|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Nahranie na server VitalWork, konektivita peer linku |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS vyhľadávanie druhého zariadenia |
| `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` | eSense Respiration (audio konektor) |
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` | BLE pre eSense Pulse (Android 12+) |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | BLE pre staršie verzie Androidu (≤ API 30) |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Vyžadované Androidom pre BLE skenovanie |
| `WRITE_EXTERNAL_STORAGE` (≤ API 28) | Legacy lokálny export |
| `FOREGROUND_SERVICE` + varianty (mikrofón, pripojené zariadenie, synchronizácia dát, media projection) | Udržanie sedenia, peer linku a zrkadlenia obrazovky aktívnych pri zhasnutej obrazovke |
| `WAKE_LOCK` | Wi-Fi lock udržujúci sieťové pripojenie počas Doze |
| `POST_NOTIFICATIONS` | Notifikácia foreground service (API 33+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Voliteľná výnimka, aby OEM správcovia batérie neukončili dlhé sedenia |
| `WRITE_SETTINGS` | Voliteľné: stmieva podsvietenie zdieľajúceho zariadenia počas zrkadlenia obrazovky, kým zachytávanie pokračuje |

### 8.2 Anonymizácia

Systém neuchováva žiadne osobné identifikátory. Účastníci sú evidovaní výhradne cez automaticky
generovaný kód (`A-001-…`) bez mena, dátumu narodenia ani iných osobných údajov. Pole s kódom
účastníka je v UI len na čítanie, aby bola schéma odolná voči preklepom.

---

## 9. Závislosť od externých nástrojov

| Nástroj / Knižnica | Verzia | Zdroj |
|--------------------|--------|-------|
| Kotlin | 2.3.0 | Maven Central |
| Jetpack Compose BOM | 2026.01.00 | Google Maven |
| Hilt / Dagger | (cez BOM) | Google Maven |
| Room | 2.7.1 | Google Maven |
| Ktor (CIO klient) | 3.3.0 | Maven Central |
| Kronos (Lyft NTP) | 0.0.1-alpha11 | Maven Central |
| Java-WebSocket | aktuálna | Maven Central |
| stream-webrtc-android | aktuálna | Maven Central |
| Play Services Wearable | aktuálna | Google Maven |
| Samsung Health Sensor SDK | aktuálna | Lokálny AAR (`wear/libs/`) |
| eSense SDK | 2.x | Lokálny JAR (`app/libs/eSense_sdk_2_lib.jar`) |

Všetky verzie sú centrálne spravované v `gradle/libs.versions.toml`.

---

## 10. Postup zostavenia a nasadenia

### 10.1 Požiadavky

- **JDK 17+** — odporúčame bundlovaný JBR z Android Studio
- Android Studio (aktuálna verzia) alebo príkazový riadok s Gradle 9.3.0
- Pripojené Android zariadenie (API 24+ pre `:app`, API 28+ pre `:wear`)

### 10.2 Príkazy

```bash
# Debug build
./gradlew assembleDebug

# Inštalácia na pripojené zariadenie
./gradlew :app:installDebug        # monitorované / zobrazovacie zariadenie (tablet alebo telefón)
./gradlew :wear:installDebug       # companion app Galaxy Watch

# Release AAB (vyžaduje keystore v local.properties)
./gradlew bundleRelease

# Testy
./gradlew test                     # unit testy (bez zariadenia)
./gradlew connectedAndroidTest      # inštrumentované testy (zariadenie / emulátor)

# Čistenie
./gradlew clean
```

### 10.3 Konfigurácia local.properties

Pre podpisovanie release buildu:

```properties
KEYSTORE_PATH=...
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

Základná URL adresa servera VitalWork, ktorú používa `SessionHttpUploader`, je taktiež
konfigurovaná v `local.properties`.

### 10.4 Inštalácia companion app (Galaxy Watch)

Postup inštalácie modulu `:wear` na Galaxy Watch 8 (cez ADB cez Wi-Fi alebo priamo) je detailne
popísaný v [`doc/install_watch_app.md`](../install_watch_app.md).

---

## 11. Schéma exportu dát (JSON/CSV)

*Pozri diagram 06 — Schéma exportu:* `diagrams/png/06_export_schema_en.png`

Na konci sedenia sa sedenie automaticky nahrá na server VitalWork jedným idempotentným JSON POST-om
(`/api/sessions/upload`, identifikovaným kódom sedenia `sessionCode`). Uloženie JSON/CSV súborov
lokálne na zariadení je samostatný, voliteľný krok, ktorý môže operátor spustiť — exportovaný JSON je
autoritatívny, plne vnorený balík; CSV súbory (jeden na scenár) sú plochý pohľad na tie isté vzorky,
vhodný pre tabuľkové procesory.

### 11.1 Rozloženie súborov

| Súbor | Rozsah | Príklad názvu |
|-------|--------|---------------|
| `{sessionCode}_export.json` | Jeden súbor na **sedenie** (účastník + sedenie + všetky scenáre + všetky vzorky) | `VW-A-260722-171532_export.json` |
| `{sessionCode}_NN_{SCENARIO}.csv` | Jeden súbor na **scenár** (`NN` = 01, 02, …, poradie) | `VW-A-260722-171532_02_COGNITIVE_LOAD.csv` |

### 11.2 Štruktúra JSON (verzia schémy 2.2.0)

Koreňový objekt (`SessionExport`) nesie `version`, `exportedAt` a tri vnorené bloky — `participant`,
`session` a `scenarios[]`.

| Cesta | Typ | Poznámka |
|-------|-----|----------|
| `version` | string | Verzia schémy exportu, aktuálne `"2.2.0"` (2.2.0 pridala `scenarios[].respirationIssues`; inak identická s 2.1.0) |
| `exportedAt` | string | ISO-8601 UTC časová pečiatka exportu |
| `participant.participantCode` | string | Anonymizovaný kód (napr. `A-007-260722-063337`) |
| `participant.age` | int? | Nullable |
| `participant.gender` | string? | Nullable |
| `session.sessionCode` | string | `VW-{prefix}-yyMMdd-HHmmss` |
| `session.startedAt` | string | ISO-8601 UTC |
| `session.endedAt` | string? | ISO-8601 UTC, nullable |
| `session.status` | string | `COMPLETED` alebo `UPLOADED` |
| `session.statistics.*` | int | Sedem počtov podľa typu: `scenarioCount`, `hrSampleCount`, `respirationSampleCount`, `rrIntervalSampleCount`, `edaSampleCount`, `watchHrSampleCount`, `watchIbiSampleCount` |
| `scenarios[]` | array | Jedna položka na priebeh biofeedback scenára (pozri nižšie) |

**Každá položka `scenarios[]`:**

| Cesta | Typ | Poznámka |
|-------|-----|----------|
| `scenarioCode` | string | Jeden z `REFERENCE_STATE`, `COGNITIVE_LOAD`, `DISTRACTING_ENVIRONMENT`, `LONG_TERM_FATIGUE`, `REACTION_TASKS` |
| `startedAt` | string | ISO-8601 UTC |
| `endedAt` | string? | ISO-8601 UTC, nullable |
| `gaps` | object? | Správa o medzerách pre každý senzor (`heartRate`, `rrInterval`, `respiration`); každá = `gapCount`, `gapTotalMs`, `gaps[]{startElapsedMs, endElapsedMs, gapMs}` |
| `respirationIssues` | object? | Úseky, kde vzorky dýchania prichádzali, ale sú nepoužiteľné (skĺznutý remienok nevytvorí žiadnu medzeru): `signalLostCount`, `signalLostTotalMs`, `noBreathingCount`, `noBreathingTotalMs`, `events[]{reason, startElapsedMs, endElapsedMs, durationMs}`; `reason` = `SIGNAL_LOST` alebo `NO_BREATHING`. `null`, keď nie je čo hlásiť — vrátane prípadu, keď senzor jednoducho nebol pripojený. Odvodzuje sa pri exporte zo zaznamenaného RA priebehu. |
| `samples[]` | array | Časový rad (pozri nižšie) |

**Každá položka `samples[]`:**

| Pole | Typ | Poznámka |
|------|-----|----------|
| `timestampMs` | long | NTP-korigované UTC epoch ms |
| `elapsedMs` | long | ms od začiatku scenára |
| `sensorType` | string | Pozri §11.4 |
| `value` | float | Nameraná hodnota v jednotke pre daný `sensorType` |

**Príklad (skrátený):**

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

### 11.3 Štruktúra CSV (jeden súbor na scenár)

Každý CSV začína **komentárovou hlavičkou s metadátami** (riadky s prefixom `#`) popisujúcou scenár,
za ktorou nasleduje jeden riadok dátovej hlavičky a riadky vzoriek. Komentárový riadok sa zapíše len
vtedy, keď príslušná hodnota existuje (napr. riadok `*_gaps` sa vynechá pri scenári bez zistených
medzier).

**Hlavička metadát (`# kľúč,hodnota`):** `session_code`, `scenario_code`, `start_time`, `end_time`,
`esense_hr_samples`, `respiration_samples` (zapisujú sa vždy), plus `rr_interval_samples`,
`watch_hr_samples`, `watch_ibi_samples`, `watch_eda_samples` (iba ak > 0), a počítadlá medzier pre
každý senzor (`*_gaps`, `*_gap_total_ms`, iba ak existujú). Ak boli zistené problémy kvality
dychového signálu, pridajú sa riadky s počtami `respiration_signal_lost` /
`respiration_no_breathing`, riadok `*_total_ms` a jeden pozičný riadok na udalosť
(`# respiration_signal_lost_1,<startElapsedMs>,<endElapsedMs>`).

**Dátové stĺpce:**

| Stĺpec | Typ | Poznámka |
|--------|-----|----------|
| `timestamp_ms` | long | NTP-UTC epoch ms |
| `elapsed_ms` | long | ms od začiatku scenára |
| `sensor_type` | string | Pozri §11.4 |
| `value` | float | Nameraná hodnota |

```
# session_code,VW-A-260722-063346
# scenario_code,COGNITIVE_LOAD
timestamp_ms,elapsed_ms,sensor_type,value
1782283839046,279,watch_hr,82.0
1782283839237,470,watch_eda,21.434
1782283840047,1280,esense_heart_rate,78.0
```

### 11.4 Hodnoty sensorType a jednotky

Pole `sensorType` v JSON aj stĺpec `sensor_type` v CSV používajú rovnaký slovník malými písmenami
(sú to prenosové hodnoty, odlišné od interných názvov enumu `SensorType`):

| Prenosová hodnota | Jednotka | Zdroj |
|-------------------|----------|-------|
| `esense_heart_rate` | BPM | eSense Pulse |
| `rr_interval` | ms | eSense Pulse |
| `respiration` | amplitúda (surový priebeh) | eSense Respiration |
| `watch_hr` | BPM | Galaxy Watch 8 |
| `watch_ibi` | ms | Galaxy Watch 8 |
| `watch_eda` | µS | Galaxy Watch 8 |

> **Poznámka:** štatistiky medzier sú **odvodené pri exporte** — nie sú uložené ako surové stĺpce v
> databáze. Nahranie na server používa ekvivalentné JSON telo s rovnakými hodnotami; endpoint je
> identifikovaný kódom sedenia `sessionCode`, takže opätovné nahranie nahradí scenáre a vzorky daného
> sedenia.

---

*Dokument je súčasťou formálneho výstupu projektu RAP_126, Activity 2 (modelovanie / simulácia).*
*Všetky diagramy sú dostupné v adresári `doc/grant_RAP_126/diagrams/` ako `.png` render aj interaktívny `.html`.*
