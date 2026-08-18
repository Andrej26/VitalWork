# OperatorSense Live — EDA prsteň: technická dokumentácia

Technický popis meracej súpravy EDA (senzorový prsteň + USB dongle) a aplikácie
`operatorsense_live.html`, ktorá slúži na čítanie, záznam a export jej dát v prehliadači.

> Slovenská verzia dokumentu `OperatorSense_Live_Documentation.md`. Názvy súborov, identifikátory,
> polia CSV/JSON a popisy tlačidiel v aplikácii zostávajú v angličtine — tak, ako sa reálne
> zobrazujú na obrazovke a v exportoch.

---

## 1. Čo je tento systém

| Časť | Funkcia |
|---|---|
| **EDA prsteň** | Senzor nosený na prste. Meria odpor kože a vysiela ho rádiom v pásme 2,4 GHz. |
| **USB dongle** | Prijíma rádiové pakety a vypisuje ich ako ASCII riadky na virtuálnom COM porte USB. |
| **`operatorsense_live.html`** | Číta tento COM port v prehliadači Chrome/Edge, zobrazuje signál naživo, zaznamenáva ho, exportuje CSV a vie záznam odoslať na server metódou POST. |
| **`CALIBRATION.xlsx`** | Nameraná tabuľka ADC kód → odpor pre oba prstene (implementovaná v aplikácii, pozri [§5](#5-kalibrácia-adc-kód--odpor--vodivosť)). |
| **`foto/`** | Fotografie prsteňa a dongla. |

### Reťazec signálu

```
  ┌──────────────┐   2.4 GHz rádio    ┌────────────────────────┐   USB (virtuálny COM) ┌───────────────────┐
  │  EDA prsteň  │ ─────────────────► │       USB dongle       │ ────────────────────► │   Chrome / Edge   │
  │              │  IEEE 802.15.4     │                        │  38400 baud, 8/N/1    │                   │
  │ ATMEGA128RFA1│  proprietárny      │ ATMEGA128RFA1 prijímač │  ASCII textové riadky │ operatorsense_live│
  │ meranie      │  formát paketov    │  + FTDI FT232RQ        │                       │ .html (Web Serial)│
  │ odporu kože  │  (nezdokumentovaný)│  USB-sériový prevodník │                       │                   │
  └──────────────┘                    └────────────────────────┘                       └───────────────────┘
                                                                                                 │
                                                                              CSV súbor ◄────────┼────────►  HTTP POST
                                                                              (lokálne)          │           (JSON, voliteľné)
```

**Dongle je nevyhnutný.** Android nedokáže čítať prsteň priamo — pozri [§3](#3-rádiové-spojenie-medzi-prsteňom-a-donglom).

---

## 2. Hardvér

### 2.1 Čipy

| Zariadenie | Čipy |
|---|---|
| Prsteň | **ATMEGA128RFA1** — 8-bitový mikrokontrolér AVR s integrovaným vysielačom/prijímačom 2,4 GHz IEEE 802.15.4. |
| Dongle | **ATMEGA128RFA1** (rádiový prijímač) + **FTDI FT232RQ** (prevodník USB na sériový port). |

Čip FTDI sa hlási ako štandardný virtuálny COM port, takže na súčasných systémoch Windows/macOS/Linux
netreba žiadny ovládač od výrobcu a prehliadač ho vie otvoriť cez rozhranie Web Serial API.

- **USB vendor ID:** `0x0403` (FTDI) — aplikácia ním filtruje dialóg výberu portu.
- **Nastavenia sériového portu:** **38400 baud, 8 dátových bitov, bez parity, 1 stop bit, bez riadenia toku.**

Súprava je necertifikovaný prototyp: neexistuje k nej FCC ID ani obdobné schválenie.

### 2.2 Prsteň

Malá zaliata doska plošných spojov na čiernom elastickom pásiku so suchým zipsom; pásik ju drží na
prste a umožňuje jednému kusu sadnúť na rôzne hrúbky prstov.

**Nasadený**

![Prsteň na prste, gombíková batéria a pridržiavacia svorka smerujú von](foto/1787042485281.jpg)

**Rozopnutý, s vybratou batériou**

![Prsteň s rozopnutým pásikom a vybratou batériou CR1620; vidno zaliatu dosku a svorku batérie](foto/1787042485273.jpg)

- Elektronika je zaliata v **priehľadnej zalievacej hmote**, takže doska je zapuzdrená, ale jej
  súčiastky zostávajú viditeľné. Pásik je prevlečený cez otvory na oboch koncoch modulu.
- **Napájanie: jedna lítiová gombíková batéria `CR1620` 3 V**, držaná spájkovanou kovovou pružinovou
  svorkou. Batéria je vymeniteľná používateľom a sedí na vonkajšej strane modulu.
- Zariadenie **nemá nabíjací konektor ani vypínač** — prsteň beží vždy, keď je batéria vložená, a
  jediný spôsob, ako ho vypnúť, je vybrať ju zo svorky. Výdrž batérie nie je zdokumentovaná
  (pozri [§9](#9-otvorené-technické-otázky)).
- Elektródy dotýkajúce sa kože sú na spodnej strane, proti prstu; na týchto fotografiách ich vidieť
  nie je. *(Predpoklad odvodený z toho, že batéria je umiestnená na opačnej strane.)*

**Zaobchádzanie**

- Pri odkladaní súpravy vyberte batériu zo svorky, inak prsteň ďalej vysiela a vybíja ju.
- Modul pritlačte ku koži pevne, ale pásik neuťahujte nadmerne — prsteň, ktorý stratí kontakt s kožou,
  sa prejaví ako zamrznutá hodnota (pozri [§6.6](#66-riešenie-problémov)).

### 2.3 Doska dongla

Nezapuzdrená doska plošných spojov s priamo naspájkovaným konektorom USB-A.

**Horná strana — rádio**

![Dongle, horná strana: ATMEGA128RFA1 a anténa v plošnom spoji](foto/1787042566892.jpg)

- Čip v puzdre QFN s označením **`ATMEL MEGA128RFA1-ZU`**, ďalšie značenie `1114D TW / 0T8301`
  (`1114` čítané ako dátumový kód = približne 11. týždeň roku 2011).
- Koniec oproti konektoru USB nesie **anténu vyleptanú priamo v medi dosky**; externá anténa
  neexistuje.

**Spodná strana — rozhranie USB**

![Dongle, spodná strana: prevodník FTDI, tlačidlo, neosadená lišta](foto/1787042566886.jpg)

- Prevodník **FTDI** z USB na sériový port, kryštál a pasívne súčiastky.
- **Mikrospínač (tlačidlo)** — **mikrokontrolér nereštartuje**. Overené trojnásobným podržaním na
  8 sekúnd pri otvorenom sériovom porte: tok paketov pokračoval nerušene v rytme 3,1 s, bez jedinej
  medzery a bez zmeny obsahu riadkov. Buď nie je zapojený, alebo ho firmvér ignoruje. Pri prevádzke
  nie je potrebný a je neškodný.
- Rad **neosadených prekovených otvorov**, najpravdepodobnejšie programovacia/ladiaca lišta (ISP
  alebo JTAG) — jediná cesta k pamäti čipu, pozri [§8](#8-firmvér).
- **Linky DTR a RTS z prevodníka FTDI nie sú pripojené na reset.** Overené impulzmi na oboch: dátový
  tok sa neprerušil. Znamená to, že **počítač nemá ako dongle reštartovať ani rozhodiť** — žiadny
  softvér sa k mikrokontroléru cez USB nedostane.

**Zaobchádzanie**

- Doska je odhalená: držte ju za konektor USB a zaobchádzajte s ňou ako s citlivou na
  elektrostatický výboj.
- Koniec s anténou udržiavajte mimo prstov, šasi notebooku a kovových plôch. Krátky predlžovací USB
  kábel, ktorý dongle odsunie od počítača, je najjednoduchší spôsob, ako zlepšiť dosah a stabilitu
  spojenia.

---

## 3. Rádiové spojenie medzi prsteňom a donglom

| Vlastnosť | Stav |
|---|---|
| Protokol | Proprietárny protokol 2,4 GHz nad fyzickou vrstvou IEEE 802.15.4, možno odvodený od Thread/ZigBee. **Nie** je to Bluetooth, BLE ani ANT. *(Vyjadrenie vývojára, nepotvrdené.)* |
| Frekvenčné pásmo | 2,4 GHz. |
| Párovanie | Žiadne — **odmerané**. Dongle bol počas testu päťkrát odpojený od napájania a spojenie sa zakaždým obnovilo samo, okamžite a bez zásahu. Prsteň a dongle majú pevné adresy (pozri [§4.2](#42-polia)). |
| Služby / UUID GATT | Neaplikovateľné — vrstva BLE neexistuje. |
| SDK / API | Žiadne. Jediným rozhraním je sériový výstup dongla. |
| Šifrovanie | **Neznáme.** Zistiteľné len odchytením rádiového rámca prijímačom 802.15.4, ktorý nemáme. Pre popis súčasnej funkcie zariadenia to nie je podstatné. |

**Čítanie z Androidu nie je možné.** Telefón ani tablet nemajú rádio 802.15.4 a prsteň neposkytuje
žiadny profil BLE, takže jediná cesta k dátam vedie cez dongle pripojený k počítaču.

---

## 4. Formát sériového výstupu

Dongle vysiela čistý text ASCII, jeden riadok na každé prijaté meranie.

### 4.1 Ukážkový riadok

```
D8730:01[CEE3];CAFE;1068
```

### 4.2 Polia

| Pole | Príklad | Význam | Istota |
|---|---|---|---|
| Identifikátor paketu | `D8730` | **Konštantný**, nie počítadlo | Odmerané |
| Kanál | `01` | Rádiový kanál, konštantný | Odmerané (význam je predpoklad) |
| Adresa dongla | `CEE3` | Adresa prijímača, konštantná | Odmerané (význam je predpoklad) |
| Adresa prsteňa | `CAFE` | Adresa odosielateľa, konštantná | Odmerané (význam je predpoklad) |
| Hodnota | `1068` | **ADC kód nameraného odporu** | Potvrdené |

**Prvé štyri polia sú statické identifikátory.** V zázname s 1 054 vzorkami za 67 minút ani v žiadnom
z neskorších meraní sa nezmenilo ani jedno z nich. Pôvodný predpoklad vývojára, že sa `D8730` mení od
paketu k paketu, sa meraním nepotvrdil — **stratu paketov teda z týchto polí nemožno rozpoznať**.

Riadok má vždy **pevnú dĺžku 24 bajtov** (23 znakov + ukončovací znak).

Aplikácia číta ako hodnotu **celé číslo za posledným `;`**, takže funguje ďalej aj v prípade, že sa
predchádzajúce polia neskôr vyložia inak.

### 4.3 Ukončenie riadka a vzorkovacia frekvencia

- **Ukončovací znak: samotné `LF` (0x0A), bez `CR`.** Odmerané na surových bajtoch zo sériového portu;
  v celom zázname sa nevyskytol ani jeden neplatný bajt. Aplikácia napriek tomu akceptuje `\r\n`, `\r`
  aj `\n` a neúplný zvyšok riadka si odkladá do bufferu, kým nedorazí jeho zvyšok.
- **Vzorkovacia perióda: 3,101 s (≈ 0,32 Hz).** Odmerané na 965 intervaloch; smerodajná odchýlka
  20 ms. Perióda je nemenná — drží aj pri skrate, aj pri rozpojenom obvode. Aplikácia frekvenciu meria
  aj naživo z posledných 50 paketov a zobrazuje ju v Hz a s/vzorku.

**Fáza sa po každom výpadku preukotví.** Ak vysielanie na chvíľu vypadne, nasledujúce pakety
nepokračujú v pôvodnej mriežke, ale v novej — posunutej najčastejšie o **±1,03 s, čo je presne tretina
periódy**. V 67-minútovom zázname sa fáza posunula pri 24 z 34 výpadkov.

> **Dôsledok pre spracovanie dát:** vzorky sa nedajú klásť do pravidelného časového rastra. Vždy
> pracujte so skutočnými časovými značkami z exportu.

### 4.4 Rozsah hodnôt a význam 65535

Prevodník je **16-bitový**, overený rozsah nameraných hodnôt je **2 až 65535**.

**Hodnota 65535 (0xFFFF) znamená rozpojený obvod — teda žiadne platné meranie.** Nie je to špeciálny
kód od firmvéru, ale **saturácia prevodníka**: odpor prekročil merateľný rozsah. Potvrdené priebehom
po zložení prsteňa z prsta, kde hodnota do stropu plynule vystúpila, ako na elektródach vysychala
vlhkosť:

```
615 → 1945 → 5144 → 9121 → 13994 → 20321 → 28050 → 36903 → 46515 → 55097 → 65535
```

> **Dôsledok pre spracovanie dát:** vzorky s hodnotou 65535 treba pred analýzou odfiltrovať. V
> 67-minútovom zázname ich bolo **312 z 1 054, teda 29,6 %**, a to v dlhých súvislých blokoch (98, 61,
> 52, 42, 25 a 24 vzoriek za sebou). Medián so sentinelmi vyjde 3 140, bez nich 2 146. Odrezať treba
> aj úsek tesne pred saturáciou — od straty kontaktu po dosiahnutie stropu ubehne asi 100 sekúnd, počas
> ktorých hodnoty vyzerajú ako platné meranie.

---

## 5. Kalibrácia: ADC kód → odpor → vodivosť

Vysielaná hodnota je surový ADC kód, nie ohmy. Ku každému prsteňu boli pripojené referenčné odpory a
zaznamenaná nahlásená hodnota; výsledkom je `CALIBRATION.xlsx`:

| R [kΩ] | ADC kód — prsteň `CAFE` (s pásikom) | ADC kód — prsteň `CAD1` (bez pásika) |
|---:|---:|---:|
| 0.55 | – | – |
| 1 | 3 | 3 |
| 2.2 | 9 | 10 |
| 5.55 | 27 | 28 |
| 8.19 | 40 | 42 |
| 17.9 | 93 | 96 |
| 38.9 | 212 | 212 |
| 68.3 | 361 | 377 |
| 100.2 | 554 | 550 |

**Dva prstene, dva stĺpce** — kalibrujú sa mierne odlišne. Adresa prsteňa je prítomná v každom pakete,
takže aplikácia vyberá zodpovedajúci stĺpec automaticky; pri neznámej adrese sa vráti k stĺpcu `CAFE`
a v odosielanom payloade to označí ako núdzové riešenie (fallback).

Ako aplikácia prevádza (kód na začiatku súboru `operatorsense_live.html`):

- **Medzi bodmi tabuľky:** lineárna interpolácia. Odozva je takmer, ale nie presne lineárna (pomer
  kód na kΩ sa posúva z 3,0 na 5,5), takže po častiach zostavená tabuľka je presnejšia než jediná
  preložená priamka.
- **Mimo rozsahu 1–100,2 kΩ:** lineárna extrapolácia z najbližšieho úseku. Takéto hodnoty sú odhady a
  sú všade označené: `(extrapolated)` v živom údaji, `in_calibrated_range` v CSV a `inCalibratedRange`
  v JSON. **Namerané hodnoty na koži zvyčajne ležia nad kalibrovaným rozsahom** (kód 1068 ≈ 185 kΩ),
  takže tento príznak sa objavuje často — označuje pokrytie kalibráciou, nie chybu.
- **Pod kalibrovaným rozsahom:** riadok 0,55 kΩ obsahuje v tabuľke `–`. Neznamená to, že zariadenie
  nehlási nič — **v prevádzke boli namerané hodnoty až po 2**, teda hlboko pod prvým kalibračným bodom.
  Pomlčka zjavne znamená, že vývojár tam nedostal stabilné meranie, nie že výstup chýba.
- **Vodivosť:** `µS = 1 000 000 / Ω`. Mikrosiemens je štandardná jednotka pre analýzu EDA a zároveň
  jednotka, ktorú používa aplikácia VitalWork pre Android na kanáli EDA z hodiniek Galaxy Watch, takže
  obe sú priamo porovnateľné.

Surový ADC kód zostáva v každom exporte vedľa prevedených hodnôt, takže dáta možno prepočítať znova,
ak sa kalibrácia zreviduje.

**Pridanie ďalšieho prsteňa:** doplňte jeho adresu a namerané dvojice do `CALIBRATION_TABLES` v súbore
`operatorsense_live.html`. Nič iné sa nemení.

---

## 6. Aplikácia `operatorsense_live.html`

Jediný sebestačný súbor HTML — bez inštalácie, bez servera, bez závislostí. Dongle číta cez rozhranie
prehliadača **Web Serial API**.

### 6.1 Požiadavky

- **Desktopový Chrome alebo Edge.** Firefox, Safari ani mobilné prehliadače Web Serial
  neimplementujú; ak nie je podporované, stránka zobrazí varovný pruh.
- Pripojený dongle.

### 6.2 Rozloženie obrazovky

| Sekcia | Obsah |
|---|---|
| Horná lišta | Connect / Disconnect, výber prenosovej rýchlosti (predvolene 38400), poznámka 8/N/1, stav pripojenia. |
| Štatistické karty | Aktuálna surová hodnota ADC, prevedené kΩ a µS, min / max / priemer, vzorkovacia frekvencia (Hz, s/vzorku), trvanie, počet zaznamenaných vzoriek, počet udalostí. |
| Živý signál | Posuvný graf posledných 600 vzoriek so značkami udalostí a dekódovaný pohľad na posledný paket. |
| Recording & export | Start/Stop recording, Mark event, Download CSV, Send to server, Clear recording a nastavenia servera. |
| Surový sériový výpis | Posledných 200 riadkov presne tak, ako prišli — prvé miesto, kam sa pozrieť, keď sa niečo správa čudne. |

### 6.3 Pracovný postup

1. **Pripojte dongle** (*Connect*) — prehliadač zobrazí dialóg výberu portu filtrovaný na zariadenia
   FTDI (po jeho zrušení sa otvorí nefiltrovaný zoznam). Živý graf a štatistiky sa spustia okamžite.
2. **Spustite záznam** (*Start recording*) — od tejto chvíle sa každá vzorka ukladá s časovou značkou.
   Na sledovanie živého grafu záznam potrebný nie je.
3. **Označte udalosť** (*Mark event*, voliteľné, počas záznamu) — aplikácia si vyžiada popis (napr.
   `touch`, `stimulus`) a umiestni značku do grafu aj do exportu, pripojenú k časovo najbližšej vzorke.
4. **Zastavte záznam** (*Stop recording*) — zachytené dáta zostávajú v pamäti.
5. **Stiahnite CSV** (*Download CSV*) a/alebo **odošlite na server** (*Send to server*).
6. **Vyčistite záznam** (*Clear recording*) pred ďalším behom.

**Správanie bufferu:** stiahnutie CSV buffer **nevyprázdni**. Ak spustíte druhý záznam bez stlačenia
*Clear recording*, oba behy skončia v jednom súbore. Odpojenie dongla záznam zastaví, ale dáta
zachová, takže zachytený úsek sa dá aj potom exportovať alebo odoslať.

### 6.4 Export do CSV

Názov súboru: `operatorsense_capture_<timestamp>.csv`.

```
timestamp_iso, epoch_ms, elapsed_ms, value_adc, resistance_ohm, conductance_us,
in_calibrated_range, packet_id, channel, dongle_addr, ring_addr, event, raw
```

| Stĺpec | Význam |
|---|---|
| `elapsed_ms` | Milisekundy od prvej zaznamenanej vzorky. |
| `value_adc` | Surový kód tak, ako bol vyslaný, nikdy neupravený. |
| `resistance_ohm`, `conductance_us` | Prevedené cez kalibračnú tabuľku daného prsteňa ([§5](#5-kalibrácia-adc-kód--odpor--vodivosť)). |
| `in_calibrated_range` | `1` v rozsahu 1–100,2 kΩ, `0` pri extrapolácii. |
| `event` | Popis značky, ktorá pripadla na túto vzorku. |
| `raw` | Pôvodný sériový riadok, ponechaný na opätovné spracovanie. |

### 6.5 Odosielanie na server

Cesta na odosielanie je implementovaná, ale predvolene nesmeruje na žiadny server — kým nezadáte
koncový bod, neodošle sa nič.

**Konfigurácia** — otvorte **⚙ Server settings** v paneli *Recording & export*:

| Pole | Význam |
|---|---|
| Endpoint URL | Cieľ požiadavky `POST`, napr. `https://api.example.org/v1/eda/captures`. |
| Authentication | `Bearer token` (`Authorization: Bearer …`), `API key header` alebo `None`. |
| API key / token | Prihlasovací údaj vydaný serverom. |
| API key header name | Hlavička použitá pri schéme s API kľúčom; predvolene `X-API-Key`. |
| Session label | Voliteľný text odosielaný s každým záznamom. |

**Save settings** nastavenia uloží; **Test connection** odošle prázdny payload s `"test": true` a overí
tak URL aj prihlasovacie údaje bez dát; **Forget credentials** ich vymaže. Nastavenia sú uložené v
`localStorage` daného prehliadača (kľúč `operatorsense.upload.v1`). Ak chcete distribuovať vopred
nakonfigurovanú kópiu, vyplňte `UPLOAD_DEFAULTS` na začiatku skriptu — uložené nastavenia však majú
aj tak prednosť.

**Payload** — jeden JSON `POST` na jeden záznam:

```json
{
  "schemaVersion": "1.0",
  "source": "OperatorSense Live (EDA ring, FTDI Web Serial)",
  "sentAtIso": "2026-08-18T09:12:33.120Z",
  "test": false,
  "capture": {
    "captureId": "os-20260818T091120-4f3a",
    "label": "operator-07",
    "startedAtIso": "2026-08-18T09:11:20.000Z",
    "endedAtIso": "2026-08-18T09:12:21.234Z",
    "durationMs": 61234,
    "sampleCount": 612,
    "eventCount": 3,
    "baudRate": 38400
  },
  "device": { "dongleAddr": "CEE3", "ringAddr": "CAFE", "usbVendorId": "0x0403" },
  "signal": {
    "unit": "raw_adc",
    "ohmsAvailable": true,
    "calibration": {
      "source": "CALIBRATION.xlsx (device developer)",
      "ringTable": "CAFE",
      "rangeKOhm": [1, 100.2]
    }
  },
  "samples": [
    {
      "epochMs": 1755506380000, "elapsedMs": 0, "valueAdc": 1068,
      "ohms": 185156.48, "microSiemens": 5.4008, "inCalibratedRange": false,
      "packetId": "D8730", "channel": "01", "dongleAddr": "CEE3", "ringAddr": "CAFE",
      "raw": "D8730:01[CEE3];CAFE;1068"
    }
  ],
  "events": [ { "epochMs": 1755506390000, "elapsedMs": 10000, "label": "touch" } ]
}
```

Pre prijímajúci koncový bod platí:

- Za úspech sa považuje ľubovoľná odpoveď **2xx**. Požiadavky vypršia po **30 s**.
- **`captureId` je stabilné pre jeden záznam** — použite ho na deduplikáciu opakovaných odoslaní.
- Zaznamenané dáta zostávajú zachované aj po neúspešnom odoslaní, takže sa dá zopakovať alebo
  exportovať do CSV.
- `signal.calibration.ringTable` uvádza použitý kalibračný stĺpec; hodnota končiaca na `(fallback)`
  znamená, že adresa prsteňa nebola rozpoznaná.
- **CORS:** server musí povoliť origin tejto stránky. Stránka otvorená ako `file://` posiela
  `Origin: null`; servírovanie HTML cez `http://` sa tomu vyhne.

### 6.6 Riešenie problémov

| Prejav | Príčina / riešenie |
|---|---|
| „Browser doesn't support Web Serial" | Použite desktopový Chrome alebo Edge. |
| Dialóg výberu portu je prázdny | Dongle nie je pripojený alebo COM port drží iný program (PuTTY, terminál). |
| Pripojené, ale bez dát | Skontrolujte surový sériový výpis. Riadky prichádzajú, ale nedajú sa spracovať → zmenil sa formát. Neprichádza vôbec nič → nesprávna prenosová rýchlosť alebo je prsteň vypnutý/mimo dosahu. |
| Hodnota je stále 65535 | Rozpojený obvod — prsteň nemá kontakt s kožou alebo je zložený ([§4.4](#44-rozsah-hodnôt-a-význam-65535)). |
| Hodnoty zamrzli | Prsteň pravdepodobne nemá kontakt s kožou. |
| Hodnoty tesne po nasadení klesajú | Normálne — ustaľovanie trvá asi minútu ([§7.1](#71-ustálenie-po-nasadení)). |
| Údaj zobrazuje „(extrapolated)" | Normálne — hodnota je mimo kalibrovaného rozsahu 1–100,2 kΩ. Ovplyvňuje len presnosť prevodu, nie samotný záznam. |
| Údaj zobrazuje „—" | Zatiaľ nebol spracovaný žiadny paket alebo sa hodnota nedala previesť. |
| *Send to server* je zošednuté | Nie je nakonfigurovaný koncový bod alebo nie je nič zaznamenané. |
| `Network/CORS error` | Nesprávna alebo nedostupná URL, prípadne server odmieta origin tejto stránky. |

---

## 7. Namerané prevádzkové správanie

Merania z 18. 8. 2026, získané pasívnym odpočúvaním portu COM4 a rozborom 67-minútového záznamu
z 15. 6. 2026. Do zariadenia sa pri nich nezasahovalo — nič sa neposielalo ani nemenilo.

### 7.1 Ustálenie po nasadení

Po nasadení prsteňa hodnota od saturácie plynule klesá a ustáli sa asi po **55 sekundách**:

| Čas od nasadenia | Hodnota |
|---:|---:|
| 0 s | 65535 *(bez kontaktu)* |
| 6 s | 23 460 |
| 18 s | 10 987 |
| 30 s | 8 218 |
| 43 s | 5 913 |
| 55 s | 3 332 *(plató)* |

Potom kolíše okolo priemeru približne ±29 %.

> **Do protokolu merania:** po nasadení prsteňa počkajte **aspoň minútu, radšej dve**, až potom
> spúšťajte záznam. Prvá minúta je nepoužiteľná.

### 7.2 Výpadky vysielania

| Podmienky | Výsledok |
|---|---|
| 67 minút merania s manipuláciou | **34 výpadkov** dlhších než 4,5 s; najdlhší 7 minút |
| 200 sekúnd s prsteňom odloženým bez dotyku | **ani jeden výpadok**, 64 paketov v pravidelnom rytme |

Výpadky teda **nie sú vlastnosťou rádiového spojenia** — vznikajú pri manipulácii s prsteňom.
Podozrenie padá na uvoľnenú batériu v pružinovej svorke, prípadne na zmenu polohy antény.

> **Do protokolu merania:** počas záznamu sa prsteňa nedotýkajte a nehýbte ním. Pri kontrole dát
> počítajte s tým, že medzery v časovej rade sú bežné.

### 7.3 Čas nábehu po zapojení dongla

Od zasunutia dongla do USB po prvé dáta uplynie **5 až 7 sekúnd** — z toho 1 až 3 sekundy trvá
rozpoznanie zariadenia vo Windows a zvyšok je čakanie na najbližší paket.

### 7.4 Poškodené riadky

Sériová linka doručí zriedka poškodený riadok — v 67-minútovom zázname **2 z 1 054, teda 0,2 %**.
Aplikácia ich v súčasnosti prijme ako platné vzorky, pretože kontroluje len to, či je za posledným `;`
číslo. Pri spracovaní dát sa preto oplatí overiť, že riadok v stĺpci `raw` presne zodpovedá
očakávanému tvaru.

---

## 8. Firmvér

**K zariadeniu nebol dodaný žiadny firmvér ani zdrojový kód a autor ich už nemá.** Nasledujúce
vychádza z identifikácie čipov a z merania správania.

### 8.1 Na čom zariadenie beží

| Vlastnosť | Zistenie |
|---|---|
| Platforma | **ATMEGA128RFA1** — 8-bitový AVR, 128 kB Flash, 16 kB SRAM, integrovaný vysielač 2,4 GHz IEEE 802.15.4 a **hardvérové AES-128** |
| Rovnaký čip | v prsteni aj v dongli |
| Vek | dátumový kód čipu ukazuje na rok 2011 |
| Časovanie | pevná perióda 3,101 s → riadené časovačom vo firmvéri |
| Výstup | UART 38400 8N1, pevný 23-znakový textový formát, ukončenie `LF` |
| USB | firmvér neupravuje deskriptory FTDI — hlásia sa generické (`VID_0403/PID_6001`), bez názvu projektu či verzie |
| Štart | firmvér pri štarte **nevypisuje žiadnu hlášku** |

Rádiový stack nie je známy. Šestnásťbitové adresy a pole kanála by zodpovedali knižnici **Atmel
Lightweight Mesh**, ktorá bola v roku 2011 pre tento čip obvyklou voľbou — je to však odhad
z nepriamych indícií, nie overený fakt.

### 8.2 Ako je zariadenie programované

ATMEGA128RFA1 sa programuje cez **ISP** (rozhranie SPI), **JTAG** alebo bootloaderom cez sériovú
linku. Neosadená lišta na spodnej strane dosky ([§2.3](#23-doska-dongla)) je takmer isto jedno
z prvých dvoch rozhraní.

### 8.3 Čo bolo vyskúšané na získanie firmvéru

Systematicky boli preverené všetky cesty, ktoré nevyžadujú nákup vybavenia:

| Cesta | Výsledok |
|---|---|
| Vyčítanie cez USB / sériovú linku | **Nemožné.** FTDI je iba prevodník, k pamäti mikrokontroléra nemá prístup. |
| Reset cez linky DTR / RTS | **Nie sú pripojené.** Impulzy na oboch linkách dátový tok neprerušili. |
| Sériový bootloader | **Nedosiahnuteľný**, keďže z počítača sa nedá vyvolať reset. |
| Štartovacia hláška po odpojení a zapojení | **Nezachytená.** Windows potrebuje 1–3 s na rozpoznanie portu, hláška by odišla skôr. |
| Reset tlačidlom na doske | **Tlačidlo nereštartuje** — tri podržania po 8 sekúnd bez akéhokoľvek účinku. |

### 8.4 Čo by získanie firmvéru vyžadovalo

Jediná zostávajúca cesta je **programátor (napr. USBasp alebo USBtinyISP, ~15 €)** pripojený na
neosadenú lištu, s prezvonením pinoutu multimetrom. Postup by bol výhradne čítací — najprv `lock`
a `fuse` bity, a až ak sú odomknuté, obraz pamäte Flash.

Obmedzenia, s ktorými treba počítať:

- Ak sú **lock bity nastavené na ochranu**, vyčítanie vráti balast a ďalej sa nedá nič robiť.
- Výsledkom je **binárka bez zdrojového kódu**; vytiahnuť z nej formát paketov znamená reverzné
  inžinierstvo kódu AVR. Rýchly úžitok by dalo len prehľadanie reťazcov, ktoré môže odhaliť názov
  a verziu použitého stacku.
- Získať by sa dal **len firmvér dongla**. Prsteň je zaliaty v živici, k jeho programovacej lište sa
  nedá dostať bez zničenia — a pritom práve v ňom je logika merania.

---

## 9. Otvorené technické otázky

1. **Kalibrácia pokrýva len 1–100,2 kΩ**, kým hodnoty namerané na koži ležia takmer vždy nad týmto
   rozsahom — v 67-minútovom zázname bolo v kalibrovanom rozsahu len 137 z 1 054 vzoriek (13 %).
   Rozšírenie tabuľky o vyššie referenčné odpory (200 kΩ, 500 kΩ, 1 MΩ) by extrapoláciu odstránilo.
   **Toto je najužitočnejšia vec, ktorú by mohol vývojár doplniť.**
2. **Čo spôsobuje výpadky pri manipulácii** ([§7.2](#72-výpadky-vysielania)) — podozrenie na uvoľnenú
   batériu vo svorke sa dá overiť tak, že sa prsteňom počas merania zámerne pohýbe.
3. **Drift počas dlhého nosenia** — ustálenie po minúte je odmerané, ale správanie po desiatkach minút
   nosenia (možný vlhkostný most medzi elektródami) overené nebolo.
4. **Výdrž batérie prsteňa** — ako dlho vydrží jedna batéria `CR1620` pri nepretržitom vysielaní, nie
   je zdokumentované; pred dlhšími meraniami sa to oplatí odmerať.
5. **„BAND" / „NO BAND"** — dva kalibračné stĺpce zodpovedajú kusom prsteňa `CAFE` a `CAD1`; čo pásik
   mení, nie je potvrdené. Fotografovaný kus ([§2.2](#22-prsteň)) elastický pásik má, takže označenie
   pravdepodobne odlišuje kus s pásikom od kusu bez neho — ale ktorú adresu fotografovaný prsteň nesie,
   sa neodčítalo, takže to zostáva nepotvrdené.
6. **Význam polí** — že sú konštantné, je odmerané; že `01` je kanál a `CEE3`/`CAFE` sú adresy, zostáva
   predpokladom vývojára, neovereným voči firmvéru.
7. **Šifrovanie rádiových paketov** — zistiteľné len prijímačom 802.15.4. Pre popis súčasnej funkcie
   zariadenia nepodstatné.

---

## 10. Stav dokumentácie

K tomuto zariadeniu neexistujú žiadne obrazy firmvéru, zdrojový kód ani dokumentácia protokolu —
pôvodný firmvér sa nezachoval ([§8](#8-firmvér)). Všetko vyššie uvedené pochádza zo štyroch zdrojov:

1. Písomné informácie od vývojára zariadenia (hardvér, rádio, výklad polí, stav firmvéru).
2. Kalibračné merania v `CALIBRATION.xlsx`.
3. Fotografie prsteňa a dosky dongla, z ktorých boli odčítané označenia čipov.
4. **Vlastné merania z 18. 8. 2026** — pasívne odpočúvanie sériového portu a rozbor 67-minútového
   záznamu z 15. 6. 2026. Tie potvrdili alebo opravili údaje o perióde, ukončovacom znaku, rozsahu
   hodnôt, význame 65535, statických identifikátoroch, absencii párovania, správaní tlačidla
   a prevádzkovom správaní podľa [§7](#7-namerané-prevádzkové-správanie).

Údaje označené v texte ako **odmerané** pochádzajú zo zdroja 4 a sú overiteľné zopakovaním merania.
Údaje označené ako **predpoklad** pochádzajú od vývojára a overené neboli.
