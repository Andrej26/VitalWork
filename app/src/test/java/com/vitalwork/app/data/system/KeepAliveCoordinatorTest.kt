package com.vitalwork.app.data.system

import com.vitalwork.app.data.db.SessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the reason-set logic + the empty→non-empty service start, with a fake launcher (host JVM,
 * no Android `Context`). `start()` is never called, so the session flow is irrelevant here.
 */
class KeepAliveCoordinatorTest {

    private class FakeLauncher : ForegroundServiceLauncher {
        var startCount = 0
        override fun startBackgroundService() { startCount++ }
    }

    private fun coordinator(launcher: ForegroundServiceLauncher) =
        KeepAliveCoordinator(launcher, MutableStateFlow<SessionEntity?>(null))

    @Test
    fun `first acquire starts the service exactly once`() {
        val launcher = FakeLauncher()
        val sut = coordinator(launcher)

        sut.acquire(KeepAliveReason.LINK)

        assertEquals(1, launcher.startCount)
        assertEquals(setOf(KeepAliveReason.LINK), sut.activeReasons.value)
    }

    @Test
    fun `acquiring a second reason does not restart the service`() {
        val launcher = FakeLauncher()
        val sut = coordinator(launcher)

        sut.acquire(KeepAliveReason.LINK)
        sut.acquire(KeepAliveReason.SESSION)

        assertEquals(1, launcher.startCount)
        assertEquals(setOf(KeepAliveReason.LINK, KeepAliveReason.SESSION), sut.activeReasons.value)
    }

    @Test
    fun `re-acquiring the same reason is idempotent`() {
        val launcher = FakeLauncher()
        val sut = coordinator(launcher)

        sut.acquire(KeepAliveReason.LINK)
        sut.acquire(KeepAliveReason.LINK)

        assertEquals(1, launcher.startCount)
        assertEquals(setOf(KeepAliveReason.LINK), sut.activeReasons.value)
    }

    @Test
    fun `releasing reasons drains the set and re-acquire starts again`() {
        val launcher = FakeLauncher()
        val sut = coordinator(launcher)

        sut.acquire(KeepAliveReason.LINK)
        sut.release(KeepAliveReason.LINK)
        assertTrue(sut.activeReasons.value.isEmpty())

        sut.acquire(KeepAliveReason.SESSION)
        // Empty → non-empty again triggers a fresh start.
        assertEquals(2, launcher.startCount)
    }

    @Test
    fun `releasing an unheld reason is a no-op`() {
        val launcher = FakeLauncher()
        val sut = coordinator(launcher)

        sut.release(KeepAliveReason.SESSION)

        assertEquals(0, launcher.startCount)
        assertTrue(sut.activeReasons.value.isEmpty())
    }
}
