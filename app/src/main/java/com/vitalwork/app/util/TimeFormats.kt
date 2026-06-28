package com.vitalwork.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Single source of truth for turning NTP-corrected epoch milliseconds (from
 * [com.vitalwork.app.data.time.TimeProvider]) into **true-UTC** strings.
 *
 * Every persisted/exported/displayed timestamp in the app must read as UTC so the tablet's
 * data lines up with the NTP-stamped sensor streams on a single timeline. Pinning the timezone
 * here — rather than at each call site — keeps the literal `Z`/`yyMMdd-HHmmss` renderings honest
 * and stops the "device-local time mislabeled as UTC" bug from creeping back in.
 *
 * [iso] and [codeToken] allocate a fresh [SimpleDateFormat] per call (it is not thread-safe).
 * They run on rare paths (session export, code minting), so the allocation is negligible. The
 * high-frequency UI debug loggers do NOT use these helpers — they hold a single formatter field
 * and set its `timeZone` to [UTC] instead.
 */
object TimeFormats {

    /** Shared UTC zone — also assigned to the UI loggers' own [SimpleDateFormat] fields. */
    val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /** ISO-8601 UTC instant for exports, e.g. `2026-06-20T14:04:12Z`. */
    fun iso(epochMs: Long): String = fmt("yyyy-MM-dd'T'HH:mm:ss'Z'", epochMs)

    /** Compact UTC token for participant/session codes, e.g. `260620-140412`. */
    fun codeToken(epochMs: Long): String = fmt("yyMMdd-HHmmss", epochMs)

    private fun fmt(pattern: String, epochMs: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = UTC }.format(Date(epochMs))
}
