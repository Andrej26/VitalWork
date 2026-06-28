package com.vitalwork.app.data.link

/**
 * Encodes the device prefix (A/B/C/D — see
 * [com.vitalwork.app.data.prefs.SettingsRepository]) into the mDNS instance name so several
 * server/client pairs can share one Wi-Fi without crossing wires.
 *
 * A server advertises `VitalWork-<prefix>-<model>`; a client only connects to peers whose embedded
 * prefix matches its own. Pairing rule for operators: **both devices of a pair use the same letter**
 * (e.g. server + client of pair A both set to `A`); different pairs use different letters. Clients
 * keep distinct letters across pairs, so participant/session code generation stays collision-free.
 */
object PeerNaming {
    private const val BASE = "VitalWork"

    /** Build the advertised instance name, e.g. `VitalWork-A-Pixel7`. */
    fun advertise(prefix: String, model: String): String = "$BASE-$prefix-$model"

    /**
     * Extract the pair prefix from an advertised instance name, or null if it carries none.
     * Tolerant of NsdManager's collision-rename suffix (e.g. `VitalWork-A-Pixel7 (1)`).
     */
    fun prefixOf(serviceName: String): String? =
        Regex("^$BASE-([A-Za-z])(?:-|$| )").find(serviceName)?.groupValues?.get(1)?.uppercase()

    /** True when [serviceName]'s embedded prefix matches [localPrefix] (case-insensitive). */
    fun matchesPrefix(serviceName: String, localPrefix: String): Boolean =
        prefixOf(serviceName)?.equals(localPrefix, ignoreCase = true) == true
}
