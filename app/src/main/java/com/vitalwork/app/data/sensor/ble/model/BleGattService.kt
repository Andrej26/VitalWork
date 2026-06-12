package com.vitalwork.app.data.sensor.ble.model

import java.util.UUID

/**
 * Represents a discovered GATT service.
 */
data class BleGattService(
    val uuid: UUID,
    val name: String?,
    val characteristics: List<BleGattCharacteristic>
) {
    val displayName: String
        get() = name ?: uuid.toString().uppercase()

    val shortUuid: String
        get() = uuid.toString().substring(4, 8).uppercase()
}

/**
 * Represents a GATT characteristic within a service.
 */
data class BleGattCharacteristic(
    val uuid: UUID,
    val name: String?,
    val properties: Set<CharacteristicProperty>
) {
    val displayName: String
        get() = name ?: uuid.toString().uppercase()

    val shortUuid: String
        get() = uuid.toString().substring(4, 8).uppercase()

    val propertiesString: String
        get() = properties.joinToString(", ") { it.shortName }
}

/**
 * BLE characteristic properties.
 */
enum class CharacteristicProperty(val shortName: String) {
    READ("R"),
    WRITE("W"),
    WRITE_NO_RESPONSE("WNR"),
    NOTIFY("N"),
    INDICATE("I"),
    SIGNED_WRITE("SW"),
    EXTENDED_PROPERTIES("EXT")
}
