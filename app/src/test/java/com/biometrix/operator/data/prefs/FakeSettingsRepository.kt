package com.biometrix.operator.data.prefs

/** In-memory [SettingsRepository] for host-JVM tests (no Context / SharedPreferences). */
class FakeSettingsRepository(var prefix: String = DEFAULT_DEVICE_PREFIX) : SettingsRepository {
    override fun getDevicePrefix(): String = prefix
    override fun setDevicePrefix(value: String) {
        prefix = value
    }
}
