# Prevádzková príručka — senzorický riadiaci modul VitalWork

**Projekt:** RAP_126 — Vývoj prototypu senzorického riadiaceho modulu na monitorovanie fyziologického
stavu operátora počas simulovaných pracovných scenárov
**Verzia dokumentu:** 1.0
**Dátum:** 2026-07-22
**Určené pre:** operátora merania (obsluhu zariadenia počas testovacieho sedenia)

---

## Obsah

1. [Prehľad systému a zariadení](#1-prehľad-systému-a-zariadení)
2. [Príprava pred meraním](#2-príprava-pred-meraním)
3. [Spustenie aplikácie a počiatočné nastavenie](#3-spustenie-aplikácie-a-počiatočné-nastavenie)
4. [Pripojenie senzorov](#4-pripojenie-senzorov)
5. [Zadanie účastníka a spustenie sedenia](#5-zadanie-účastníka-a-spustenie-sedenia)
6. [Spárovanie druhého zariadenia (zrkadlenie obrazovky)](#6-spárovanie-druhého-zariadenia-zrkadlenie-obrazovky)
7. [Priebeh merania — scenáre A–E](#7-priebeh-merania--scenáre-ae)
8. [Ukončenie sedenia a export dát](#8-ukončenie-sedenia-a-export-dát)
9. [Riešenie bežných problémov](#9-riešenie-bežných-problémov)

---

## 1. Prehľad systému a zariadení

*Pozri diagram 01 — Architektúra systému:* `diagrams/png/01_system_architecture_en.png`

Systém VitalWork tvoria nasledujúce zariadenia, ktoré operátor pripravuje a ovláda:

| Zariadenie | Úloha počas merania |
|------------|----------------------|
| **Android tablet/telefón** (Client) | Beží na ňom aplikácia VitalWork; pripája senzory; nahráva a odosiela sedenie |
| **eSense Pulse** (hrudný pás) | Meria srdcovú frekvenciu a R-R intervaly cez BLE |
| **eSense Respiration** (hrudný pás) | Meria amplitúdu dýchania cez jack konektor tabletu |
| **Galaxy Watch 8** | Meria EDA, srdcovú frekvenciu a IBI; nosí ho účastník |
| **Druhé Android zariadenie** (Server, voliteľné) | Sleduje živú obrazovku zariadenia Client cez lokálnu Wi-Fi — užitočné, keď operátor nie je priamo pri účastníkovi |

> **Dôležité:** každý tablet má pre spojenie medzi zariadeniami nastavenú presne jednu rolu —
> **Client** (beží celá aplikácia: senzory, sedenia, scenáre) alebo **Server** (hostí spojenie a iba
> sleduje obrazovku zariadenia Client). Väčšina sedení potrebuje iba Client; zariadenie Server je
> voliteľné a je relevantné iba vtedy, keď chce druhý operátor sledovať priebeh na diaľku.

---

## 2. Príprava pred meraním

### 2.1 Kontrolný zoznam pred každým sedením

- [ ] Tablet/telefón nabitý (odporúča sa ≥ 80 %)
- [ ] Galaxy Watch nabitý (odporúča sa ≥ 50 %; aplikácia zobrazí upozornenie na nízku batériu)
- [ ] eSense Pulse nabitý a funkčný (indikátor svieti)
- [ ] eSense Respiration zapojený do jack konektora tabletu
- [ ] **Bluetooth na tablete je zapnutý** (potrebný pre BLE spojenie s eSense Pulse aj pre Data Layer
      hodiniek Galaxy Watch)
- [ ] Lokalizačné služby na tablete sú zapnuté (Android ich vyžaduje pre BLE skenovanie)
- [ ] Povolené notifikácie (potrebné pre foreground službu, ktorá udržiava sedenie aktívne)
- [ ] Pri použití druhého zariadenia na zrkadlenie obrazovky: obe zariadenia na **tej istej Wi-Fi sieti**

### 2.2 Nasadenie senzorov na účastníka

1. **eSense Pulse** — nasaďte hrudný pás so správne umiestnenými elektródami; po nadviazaní BLE
   spojenia počkajte niekoľko sekúnd, kým sa signál ustáli.
2. **eSense Respiration** — nasaďte hrudný pás; zapojte kábel do jack konektora tabletu.
3. **Galaxy Watch 8** — nasaďte na zápästie účastníka; skontrolujte, že beží sprievodná aplikácia na
   hodinkách (zobrazuje sa ako služba na popredí (foreground) typu „health").

---

## 3. Spustenie aplikácie a počiatočné nastavenie

### 3.1 Prvé spustenie — výber režimu zariadenia

Pri prvom spustení sa aplikácia opýta, či je toto zariadenie **Server** alebo **Client**. Pre
zariadenie, ktoré vykonáva samotné meranie (senzory, sedenia, scenáre), zvoľte **Client**; **Server**
zvoľte iba pre druhé zariadenie, ktoré bude na diaľku sledovať obrazovku zariadenia Client. Voľba sa
uchová — pri ďalšom spustení aplikácia prejde priamo na domovskú obrazovku. Dá sa kedykoľvek zmeniť v
**Nastaveniach**.

### 3.2 Domovská obrazovka (režim Client)

*Snímka obrazovky: Domovská obrazovka, režim Client*
![Domov — Client](screenshots/VW_01_home_client.jpg){width=2.3in}

| Prvok | Účel |
|-------|------|
| **Start New Session** | Vytvorí nového účastníka a spustí sedenie — hlavná pracovná akcia |
| **Connect as Client** | Otvorí spojenie medzi zariadeniami, aby zariadenie Server mohlo sledovať túto obrazovku |
| **Completed Sessions** | Prehľad, export a upload dokončených sedení |
| **Sensors** (dole) | Pripojenie a živý stav senzorov — používa sa pred sedením alebo na diagnostiku |
| **Tutorial** (dole) | Úvodný návod — jednorazové čítanie |
| **Settings** (dole) | Prefix zariadenia a režim zariadenia |
| Stavová karta (dole) | Rýchly prehľad pripravenosti: pripojené senzory, aktívne sedenie alebo upozornenia na nastavenie |

> **Počas bežnej prevádzky** operátor prevažne používa iba **Start New Session** a **Completed
> Sessions**. Ostatné položky slúžia na jednorazové nastavenie alebo diagnostiku.

### 3.3 Domovská obrazovka (režim Server)

*Snímka obrazovky: Domovská obrazovka, režim Server*
![Domov — Server](screenshots/VW_09_home_server.jpg){width=2.3in}

Zariadenie v režime Server zobrazuje iba **Connect as Server** a **Settings** — hostenie spojenia a
sledovanie obrazovky spárovaného zariadenia je jeho jedinou úlohou; samotné sedenia ani senzory
nespúšťa.

### 3.4 Jednorazové nastavenie — prefix a režim zariadenia

*Snímka obrazovky: Settings — prefix zariadenia a režim zariadenia*
![Settings](screenshots/VW_02_settings.jpg){width=2.3in}

Pred prvým sedením otvorte **Settings** a zvoľte prefix tohto zariadenia: **A**, **B**, **C** alebo
**D**. Prefix sa pridáva ku každému kódu účastníka (napr. `A-001`) a kódu sedenia
(`VW-A-yyMMdd-HHmmss`) generovanému na tomto zariadení a zároveň obmedzuje spojenie medzi zariadeniami
na jednu dvojicu.

> **Pravidlo:** obe zariadenia jednej dvojice (Client + jeho Server) používajú **rovnaké** písmeno;
> iná dvojica používa iné písmeno. Vďaka tomu sa každý Server spája iba so svojím Clientom a kódy sa
> nikdy nezrazia pri paralelnom testovaní na viacerých tabletoch. Ktoré písmeno patrí ktorej dvojici,
> dohodnite vopred a počas štúdie ho nemeňte.

Tá istá obrazovka obsahuje aj prepínač **Device mode** (Server/Client) opísaný v §3.1 — jeho zmena tu
okamžite zmení podobu domovskej obrazovky po návrate.

---

## 4. Pripojenie senzorov

### 4.1 eSense Pulse (BLE)

1. Na obrazovke nastavenia senzorov (pozri §5) ťuknite na kartu **Heart Rate** (alebo prejdite na
   **Sensors → eSense Pulse**).
2. Aplikácia vyhľadá BLE zariadenia — zo zoznamu vyberte to, ktorého názov začína na `eSense`.
3. Po pripojení sa zobrazí živá hodnota srdcovej frekvencie (BPM) a R-R intervaly.

> Ak sa zariadenie nenájde: skontrolujte, že sú na tablete zapnuté Bluetooth aj lokalizačné služby.

### 4.2 eSense Respiration (jack konektor)

Senzor sa aktivuje automaticky po zapojení do jack konektora. Na obrazovke nastavenia senzorov
skontrolujte, že karta **Respiration** zobrazuje živú hodnotu (jednotka **RA** — surová amplitúda
dýchania, nie počet nádychov za minútu). Odhad dychovej frekvencie (br/min) sa zobrazí približne po
15 sekundách čistého signálu; dovtedy displej zobrazuje `--`.

Aplikácia priebežne sleduje kvalitu signálu a pri probléme zobrazí varovný banner:

- **Respiration signal lost** (strata signálu) — remienok skĺzol z hrudníka (hodnota RA klesne).
  Nasaďte hrudný remienok späť.
- **No breathing detected** (nezistené dýchanie) — remienok je nasadený, ale nesníma dýchanie
  (napr. je príliš voľný). Dotiahnite a upravte polohu remienka.

### 4.3 Galaxy Watch 8

1. Skontrolujte, že je zapnutý Bluetooth — spojenie s hodinkami beží cez priamy Bluetooth, nie cez
   Wi-Fi.
2. Skontrolujte stav karty **Galaxy Watch 8**: **Connected** (živé dáta), **Watch dozing — buffering**
   (obrazovka hodiniek je vypnutá — dáta sa ukladajú na hodinkách, nestrácajú sa) alebo
   **Disconnected**.
3. Stav **dozing** je normálny vždy, keď je obrazovka hodiniek vypnutá — uložené dáta sa na tablet
   automaticky prenesú pri záverečnom prenose na konci sedenia (pozri §8.1).

> Ak stav zostáva **Disconnected**: skontrolujte, že je na tablete zapnutý Bluetooth a že na
> hodinkách beží sprievodná aplikácia.

*Snímka obrazovky: obrazovka nastavenia senzorov — Mindfield eSense (pripája sa) a Galaxy Watch 8
(odpojený)*
![Nastavenie senzorov](screenshots/VW_04_sensor_setup.jpg){width=2.3in}

---

## 5. Zadanie účastníka a spustenie sedenia

1. Na domovskej obrazovke ťuknite na **Start New Session**.

   > **Ak je tlačidlo neaktívne (sivé)** s poznámkou *„Fix the warnings above to start"*, tabletu
   > chýba nastavenie, bez ktorého sedenie nemôže bezpečne bežať — **výnimka z optimalizácie
   > batérie** alebo **povolenie notifikácií** (bez nich môže systém pri vypnutej obrazovke potichu
   > ukončiť nahrávanie a celé sedenie by sa stratilo). Použite tlačidlá **Fix** vo varovnej karte
   > v hornej časti domovskej obrazovky; tlačidlo sa odomkne hneď po udelení oboch povolení.
   > Obnovenie už bežiaceho sedenia nie je nikdy blokované.

2. Zobrazí sa formulár **New Participant**:
   - **Participant code** — automaticky generovaný (napr. `A-004-260722-115828`). Iba na čítanie —
     nepokúšajte sa ho upraviť.
   - **Age** — vek účastníka (18–80 rokov); nastavte pomocou tlačidiel − / +.
   - **Gender** — Male / Female / Other / N/A.
3. Ťuknite na **Start session**.

*Snímka obrazovky: formulár New Participant*
![Nový účastník](screenshots/VW_03_new_participant.jpg){width=2.3in}

4. Aplikácia otvorí **obrazovku nastavenia senzorov** — jednorazovú kontrolu pred začiatkom nahrávania
   (pozri §4 a snímku vyššie). Ťuknite na **Proceed to scenarios**, akonáhle sú potrebné senzory
   pripojené. Tlačidlo zostáva neaktívne (s poznámkou *„Connect at least one sensor to continue."*),
   kým nie je pripojený **aspoň jeden senzor** — bráni to spusteniu scenára, ktorý by nezaznamenal
   žiadne dáta. Odpojené senzory majú prerušovaný (čiarkovaný) okraj so štítkom **Tap to connect**;
   ťuknutie kdekoľvek na takúto kartu spustí jej pripájanie.

> Foreground služba udržiava senzory a (ak je zapnuté) aj spojenie medzi zariadeniami aktívne aj pri
> vypnutej obrazovke tabletu, takže operátor nemusí tablet držať nepretržite v ruke.

---

## 6. Spárovanie druhého zariadenia (zrkadlenie obrazovky)

> Tento krok je **voliteľný** — potrebný iba vtedy, ak chce druhý operátor sledovať obrazovku
> zariadenia Client na diaľku. Pri sedení s jedným tabletom túto časť preskočte.

### 6.1 Postup párovania

1. Na zariadení **Server** ťuknite na **Connect as Server**, potom na **Connect**. Zobrazí sa stav
   *Waiting for monitored device*.

   *Snímka obrazovky: Link — Server, pred pripojením*
   ![Link — Server](screenshots/VW_10_link_server.jpg){width=2.3in}

2. Na zariadení **Client** ťuknite na **Connect as Client**. Aplikácia vyhľadá Server cez mDNS.

   *Snímka obrazovky: Link — Client, vyhľadávanie*
   ![Link — Client](screenshots/VW_08_link_client.jpg){width=2.3in}

3. Na zariadení Client ťuknite na nájdené zariadenie v zozname **Discovered peers** a potom na
   **Connect**. Po nadviazaní WebSocket spojenia sa na oboch obrazovkách zobrazí zelený stav
   **Connected**.
4. Na zariadení Server ťuknite na **View screen** — na zariadení Client sa zobrazí systémové okno so
   žiadosťou o súhlas so zdieľaním obrazovky. Po jeho potvrdení sa obrazovka zariadenia Client začne
   živo prenášať na Server, priamo medzi zariadeniami cez lokálnu Wi-Fi (WebRTC) — dáta neopúšťajú
   lokálnu sieť.

### 6.2 Obnovenie spojenia po výpadku

Ak Wi-Fi spojenie vypadne, znova ho nadviažte rovnakým postupom ako vyššie — najprv Connect na
zariadení Server, potom výber nájdeného zariadenia a Connect na zariadení Client. Výpadok spojenia
nijako neovplyvní samotné sedenie ani nahrávanie senzorov na zariadení Client — zrkadlenie obrazovky
je pomôcka na sledovanie, nie súčasť samotného merania.

---

## 7. Priebeh merania — scenáre A–E

### 7.1 Rozcestník scenárov

Po obrazovke nastavenia senzorov sa zobrazí **rozcestník scenárov** — jedna karta pre každý scenár a
tlačidlo **End Session & Save** na konci.

*Snímka obrazovky: rozcestník scenárov*
![Rozcestník scenárov](screenshots/VW_05_scenario_hub.jpg){width=2.3in}

| Scenár | Trvanie | Účel |
|--------|---------|------|
| **A — Reference State** | 10 min | Základné (referenčné) meranie bez vyvolanej záťaže |
| **B — Increased Cognitive Load** | 20 min | Podmienka kognitívnej záťaže |
| **C — Distracting Environment** | 20 min | Podmienka rušivého prostredia |
| **D — Long-Term Load and Fatigue** | 30 min | Podmienka dlhodobej záťaže a únavy |
| **E — Reaction Tasks** | 10 min | Podmienka reakčných úloh |

### 7.2 Priebeh jedného scenára

1. Ťuknite na kartu scenára. Nahrávanie sa **spustí automaticky** — nie je potrebné žiadne
   samostatné tlačidlo Start.
2. Zobrazí sa odpočítavací kruh so zostávajúcim časom; horná lišta sa zmení na červenú s pulzujúcim
   odznakom **REC** a uplynutým časom. Aplikácia zároveň zamkne dotykové ovládanie a skryje systémové
   lišty, takže tablet možno bezpečne odložiť alebo vložiť do vrecka počas behu.
3. Keď odpočítavanie dosiahne nulu, scenár sa **automaticky zastaví a uzavrie** a aplikácia sa vráti
   do rozcestníka. Dokončený scenár má zelenú fajku a jeho písmenový odznak sa zafarbí na zeleno.
4. Zopakujte pre každý scenár vyžadovaný protokolom. Scenáre možno spúšťať v ľubovoľnom poradí a v
   prípade potreby ich možno zopakovať — rozcestník vždy zobrazuje, ktoré už majú zaznamenaný beh.

> Ak sa senzor počas scenára odpojí, zobrazí sa banner („… odpojený — nahrávanie pokračuje —
> pripojte ho znova, aby sa obnovilo zaznamenávanie dát"). Časovač scenára beží ďalej; senzor
> pripojte čo najskôr, aby bola medzera v dátach daného scenára čo najmenšia.

> Ak sa scenár otvorí **bez jediného pripojeného senzora** (napríklad senzor vypadol medzi
> nastavením a scenárom), zobrazí sa banner **„No sensor connected"** a nespustí sa ani nahrávanie,
> ani odpočet. O nič neprichádzate — pripojte senzor a scenár sa začne normálne.

---

## 8. Ukončenie sedenia a export dát

### 8.1 Ukončenie sedenia

1. V rozcestníku scenárov ťuknite na **End Session & Save** a potvrďte.
2. Ak majú hodinky Galaxy Watch uložené ešte neodoslané dáta, aplikácia vyzve operátora, aby
   **prebudil hodinky** (ťuknutím na ich obrazovku), aby mohli odoslať späť všetko, čo počas sedenia
   uložili.
3. Po dokončení prenosu zelená fajka potvrdí **Watch data saved** a aplikácia automaticky pokračuje na
   obrazovku prehľadu sedenia.

   *Snímka obrazovky: potvrdenie ukončenia sedenia*
   ![Watch data saved](screenshots/VW_06_watch_data_saved.jpg){width=2.3in}

   > Ak hodinky nereagujú, je k dispozícii voľba **End without watch data** — hodinky si svoje
   > uložené dáta bezpečne ponechajú a možno ich získať pri neskoršom sedení; žiadne dáta sa
   > nestrácajú, iba sa oneskoria.

### 8.2 Prehľad, export a upload

Aplikácia automaticky otvorí obrazovku **prehľadu sedenia**.

*Snímka obrazovky: prehľad sedenia*
![Prehľad sedenia](screenshots/VW_07_session_review.jpg){width=2.3in}

| Akcia | Účinok |
|-------|--------|
| **Upload to server** | Odošle celé sedenie (účastník + scenáre + vzorky) na server VitalWork. Bezpečné zopakovať — upload je identifikovaný kódom sedenia (sessionCode), takže opakovanie neduplikuje dáta. |
| **Export to Documents** | Zapíše lokálnu kópiu v JSON + CSV do priečinka Documents na tablete. Nezávisí od stavu uploadu. |
| **Delete Session** | Trvalo odstráni sedenie — až po jeho exporte alebo uploade. |

> Ak upload zlyhá (napr. bez pripojenia), ťuknite znova na **Upload to server**, keď sa pripojenie
> obnoví.

---

## 9. Riešenie bežných problémov

| Problém | Možná príčina | Riešenie |
|---------|----------------|----------|
| eSense Pulse sa nenašiel | Vypnutý Bluetooth alebo lokalizačné služby | Zapnite Bluetooth a lokalizačné služby |
| eSense Respiration nezobrazuje dáta | Nesprávne zapojený kábel | Odpojte a znova zapojte jack konektor |
| Banner „Respiration signal lost" | Hrudný remienok skĺzol z hrudníka | Nasaďte remienok späť; banner zmizne po návrate signálu |
| Banner „No breathing detected" | Príliš voľný remienok — nesníma pohyb hrudníka | Dotiahnite a upravte polohu remienka |
| Galaxy Watch zobrazuje Disconnected | Vypnutý Bluetooth na tablete alebo nebeží sprievodná aplikácia na hodinkách | Zapnite Bluetooth; reštartujte aplikáciu na hodinkách |
| Galaxy Watch zobrazuje „dozing — buffering" | Obrazovka hodiniek je vypnutá (normálny stav) | Nie je potrebná žiadna akcia — dáta sa ukladajú; záverečný prenos ich pri ukončení sedenia získa späť |
| Spojenie medzi zariadeniami — Client nenájde Server | Zariadenia nie sú na tej istej Wi-Fi sieti, alebo Server nebol spustený | Overte, že sú obe na rovnakej Wi-Fi; najprv ťuknite na Connect na zariadení Server |
| Zrkadlenie obrazovky nič nezobrazuje | Súhlas so zdieľaním obrazovky bol na zariadení Client zamietnutý | Na zariadení Server znova ťuknite na View screen a na zariadení Client potvrďte systémový dialóg |
| Upload na server zlyhal | Chýbajúce alebo slabé pripojenie k sieti | Zopakujte po obnovení pripojenia; upload je bezpečné opakovať |
| Tablet po čase odpája senzory | Optimalizácia batérie ukončuje službu bežiacu na pozadí | V systémových nastaveniach vylúčte aplikáciu VitalWork z optimalizácie batérie |

---

*Tento dokument je súčasťou formálneho výstupu projektu RAP_126, Aktivita 2 (modelovanie / simulácia).*
*Celý priebeh sedenia zobrazuje diagram 02:* `doc/grant_RAP_126/diagrams/png/02_operator_session_workflow_en.png`
