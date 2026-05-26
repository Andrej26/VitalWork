package com.biometrix.operator.data.sensor.fibion

import android.bluetooth.le.ScanCallback
import com.biometrix.operator.data.sensor.ble.model.BleDevice
import com.biometrix.operator.data.sensor.fibion.model.FibionFlashDeviceInfo
import org.json.JSONObject

/** Fibion Flash devices advertise as "Movesense" over BLE. */
internal const val FIBION_DEVICE_NAME_PREFIX = "Movesense"

internal object FibionParsers {

    data class HeartRateNotification(
        val heartRate: Int?,
        val rrIntervals: List<Int>
    )

    /**
     * Parse MDS HR notification: `{"Body":{"average":<double>,"rrData":[<int>...]}}`.
     * Returns nulls/empties for missing or non-positive values rather than throwing.
     */
    fun parseHeartRateNotification(data: String): HeartRateNotification {
        val body = runCatching { JSONObject(data).optJSONObject("Body") }.getOrNull()
            ?: return HeartRateNotification(null, emptyList())

        val hr = body.optDouble("average", -1.0).toInt().takeIf { it > 0 }

        val rrArray = body.optJSONArray("rrData")
        val rrList = if (rrArray != null) {
            buildList(rrArray.length()) {
                for (i in 0 until rrArray.length()) {
                    val rr = rrArray.optInt(i, -1)
                    if (rr > 0) add(rr)
                }
            }
        } else {
            emptyList()
        }

        return HeartRateNotification(hr, rrList)
    }

    /**
     * Parse MDS ECG notification: `{"Body":{"Samples":[<num>...]}}`.
     * Tolerates both int (raw) and double (/mV) samples. Skips NaN entries.
     */
    fun parseEcgSamples(data: String): List<Float> {
        val body = runCatching { JSONObject(data).optJSONObject("Body") }.getOrNull()
            ?: return emptyList()
        val samples = body.optJSONArray("Samples") ?: return emptyList()

        return buildList(samples.length()) {
            for (i in 0 until samples.length()) {
                val sample = samples.optDouble(i, Double.NaN)
                if (!sample.isNaN()) add(sample.toFloat())
            }
        }
    }

    /** Parse `{"Content":<int>}` from the MDS battery endpoint. */
    fun parseBatteryLevel(data: String): Int? = runCatching {
        JSONObject(data).getInt("Content")
    }.getOrNull()

    /** Parse `{"Content":{"sw","hw","productName"}}` from the MDS /Info endpoint. */
    fun parseDeviceInfo(data: String, serial: String): FibionFlashDeviceInfo? = runCatching {
        val content = JSONObject(data).getJSONObject("Content")
        FibionFlashDeviceInfo(
            serial = serial,
            swVersion = content.optString("sw", ""),
            hwVersion = content.optString("hw", ""),
            productName = content.optString("productName", "Fibion Flash")
        )
    }.getOrNull()

    /** True if firmware `swVersion` (semver "major.minor...") supports the /mV ECG endpoint (>= 2.3). */
    fun supportsMillivoltEcg(swVersion: String): Boolean {
        val parts = swVersion.split(".")
        if (parts.size < 2) return false
        val major = parts[0].toIntOrNull() ?: return false
        val minor = parts[1].toIntOrNull() ?: return false
        return major > 2 || (major == 2 && minor >= 3)
    }

    /** When `filterByName` is false, all devices pass through (debug/discovery mode). */
    fun isFibionFlashDevice(device: BleDevice, filterByName: Boolean): Boolean {
        if (!filterByName) return true
        return device.name?.startsWith(FIBION_DEVICE_NAME_PREFIX, ignoreCase = true) == true
    }

    /**
     * Merge a new/updated device into a list (replacing by MAC address) and
     * return the result sorted by RSSI descending.
     */
    fun mergeDiscovered(current: List<BleDevice>, device: BleDevice): List<BleDevice> {
        val updated = current.toMutableList()
        val existingIndex = updated.indexOfFirst { it.address == device.address }
        if (existingIndex >= 0) {
            updated[existingIndex] = device
        } else {
            updated.add(device)
        }
        return updated.sortedByDescending { it.rssi }
    }

    fun scanFailureMessage(errorCode: Int): String = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
        else -> "Unknown error ($errorCode)"
    }
}
