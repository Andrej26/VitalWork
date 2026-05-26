package com.biometrix.operator.data.system

class FakeLocationChecker(var locationEnabled: Boolean = true) : LocationChecker {
    override fun isLocationEnabled(): Boolean = locationEnabled
}
