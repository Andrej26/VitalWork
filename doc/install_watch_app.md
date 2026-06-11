# Installing the BioMetrix app on the Galaxy Watch (over Wi-Fi)

How to put the latest `:wear` build on the Galaxy Watch 8 whenever there's an update. The watch
connects over **Wi-Fi** (no cable), using Android's Wireless debugging.

> One-time note: `adb` isn't on the system PATH. It lives at
> `C:\Users\andre\AppData\Local\Android\Sdk\platform-tools\adb.exe`. The steps below use that full
> path. To shorten future commands you can add that `platform-tools` folder to your PATH, but it's
> optional.

---

## One-time setup on the watch

1. On the watch: **Settings → About watch → Software** → tap **Software version** ~7 times until
   "Developer mode" turns on.
2. **Settings → Developer options → Wireless debugging** → turn **ON**.
3. Make sure the watch and the PC are on the **same Wi-Fi network**.

---

## Every time you want to update the watch

### Step 1 — Get the watch's Wi-Fi debugging address
On the watch: **Settings → Developer options → Wireless debugging → IP address & Port**.
You'll see something like `192.168.100.37:33479`.

> ⚠️ The **port changes** each time Wireless debugging is toggled off/on (and sometimes after a
> reboot). The IP can also change. Always re-check this screen — don't assume last time's value.

### Step 2 — Connect adb to the watch
In a PowerShell window in the project folder (`d:\00_Projekty_Praca\BioMetrixOperator`):

```powershell
$adb = "C:\Users\andre\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb connect 192.168.100.37:33479      # <-- use the address from Step 1
```

The **first time** (and after the watch reboots) the watch shows a **"Allow wireless debugging?"**
prompt — tap **Allow** (and "Always allow from this network"). If `connect` says `failed to
authenticate`, accept that prompt and run `connect` again.

Confirm both devices are visible:

```powershell
& $adb devices -l
```

You should see the watch (`model:SM_L330`) and, if plugged/connected, the tablet
(`model:2207117BPG`).

### Step 3 — Build the latest watch APK

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\"
.\gradlew.bat :wear:assembleDebug
```

This produces `wear\build\outputs\apk\debug\wear-debug.apk`.

### Step 4 — Install it on the watch (by serial)

```powershell
& $adb -s 192.168.100.37:33479 install -r wear\build\outputs\apk\debug\wear-debug.apk
```

`-r` reinstalls/updates in place (keeps it as the same app). You want `Success`.

> Why `-s <address>`? The watch and tablet **share the same applicationId**
> (`com.biometrix.operator`). If you run a plain `:wear:installDebug` while both are connected,
> Gradle/adb may try to install on the wrong device. Targeting by serial avoids that. If
> *only the watch* is connected, you can instead just run
> `.\gradlew.bat :wear:installDebug`.

### Step 5 — Launch & verify
Open **BioMetrix** on the watch and tap **Start**. On the tablet, go to
**Sensors → Galaxy Watch** and confirm live readings (HR / EDA / battery) appear.

---

## Updating both watch and tablet together

After building both (`.\gradlew.bat :app:assembleDebug :wear:assembleDebug`):

```powershell
$adb = "C:\Users\andre\AppData\Local\Android\Sdk\platform-tools\adb.exe"
# Tablet (USB or its own connection) — replace serial if it differs:
& $adb -s ZL85GMVK49K745HE install -r app\build\outputs\apk\debug\app-debug.apk
# Watch (Wi-Fi) — use the current address from Step 1:
& $adb -s 192.168.100.37:33479 install -r wear\build\outputs\apk\debug\wear-debug.apk
```

(Find current serials any time with `& $adb devices -l`.)

---

## Quick one-shot (copy/paste, edit the watch address)

```powershell
$adb = "C:\Users\andre\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$watch = "192.168.100.37:33479"   # <-- update from watch Settings each time
& $adb connect $watch
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\"
.\gradlew.bat :wear:assembleDebug
& $adb -s $watch install -r wear\build\outputs\apk\debug\wear-debug.apk
```

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `failed to authenticate` / `unauthorized` | Accept the "Allow wireless debugging" prompt on the watch, then re-run `adb connect`. |
| `cannot connect ... No connection could be made` | Re-check IP **and port** on the watch (port changes on toggle/reboot); confirm same Wi-Fi; toggle Wireless debugging off/on. |
| `device offline` | `& $adb disconnect`, then `& $adb connect <address>` again. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature mismatch) | Uninstall first: `& $adb -s <watch> uninstall com.biometrix.operator`, then install again. |
| Watch dropped off Wi-Fi | It sleeps/changes networks; just redo Step 1–2. The connection isn't permanent. |
| `adb` not found | Use the full path shown above, or add `…\Sdk\platform-tools` to PATH. |

---

## Pairing for the very first time (if `connect` alone won't authorize)

Some watches require a one-time pairing code before `connect` works:

1. Watch: **Wireless debugging → Pair new device** — note the **pairing address:port** and the
   **6-digit code**.
2. PC: `& $adb pair <pairing-address:port>` then enter the code when prompted.
3. Then use the normal **IP address & Port** (Step 1) with `& $adb connect` from then on.
