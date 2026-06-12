package com.vitalwork.app.data.system

class FakeSystemReadinessChecker(
    var missing: Set<SessionPrerequisite> = emptySet()
) : SystemReadinessChecker {
    override fun missingPrerequisites(): Set<SessionPrerequisite> = missing
}
