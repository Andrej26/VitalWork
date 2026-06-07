package com.biometrix.operator.data.vr

import com.biometrix.operator.data.vr.VrPairingManager.PairingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VrPairingManagerTest {

    private lateinit var manager: VrPairingManager

    @Before
    fun setUp() {
        manager = VrPairingManager()
    }

    @Test
    fun claimThenConfirm_movesUnpairedToPendingToBonded() {
        assertEquals(PairingState.UNPAIRED, manager.pairingState.value)

        manager.onClaim(questId = "quest-A", sourceIp = "192.168.1.50")
        assertEquals(PairingState.PENDING, manager.pairingState.value)
        assertEquals(VrPairingManager.VrCandidate("quest-A", "192.168.1.50"), manager.candidate.value)

        manager.confirm()
        assertEquals(PairingState.BONDED, manager.pairingState.value)
    }

    @Test
    fun isAuthorized_falseWhileUnpairedOrPending() {
        assertFalse(manager.isAuthorized("quest-A", "192.168.1.50"))

        manager.onClaim("quest-A", "192.168.1.50")
        // Pending is not yet bonded — requests must still be rejected until the operator taps Connect.
        assertFalse(manager.isAuthorized("quest-A", "192.168.1.50"))
    }

    @Test
    fun isAuthorized_trueOnlyForBondedQuestIdAndIp() {
        manager.onClaim("quest-A", "192.168.1.50")
        manager.confirm()

        assertTrue(manager.isAuthorized("quest-A", "192.168.1.50"))
        assertFalse(manager.isAuthorized("quest-B", "192.168.1.50")) // wrong id (second Quest)
        assertFalse(manager.isAuthorized("quest-A", "192.168.1.99")) // wrong ip
        assertFalse(manager.isAuthorized(null, null))
    }

    @Test
    fun isBondedTo_trueOnlyForBondedQuest() {
        manager.onClaim("quest-A", "192.168.1.50")
        assertFalse(manager.isBondedTo("quest-A")) // pending, not bonded yet

        manager.confirm()
        assertTrue(manager.isBondedTo("quest-A"))
        assertFalse(manager.isBondedTo("quest-B"))
    }

    @Test
    fun confirm_withoutCandidate_isNoOp() {
        manager.confirm()
        assertEquals(PairingState.UNPAIRED, manager.pairingState.value)
    }

    @Test
    fun reArm_dropsBondAndClearsCandidate() {
        manager.onClaim("quest-A", "192.168.1.50")
        manager.confirm()
        assertTrue(manager.isAuthorized("quest-A", "192.168.1.50"))

        manager.reArm()
        assertEquals(PairingState.UNPAIRED, manager.pairingState.value)
        assertNull(manager.candidate.value)
        assertFalse(manager.isAuthorized("quest-A", "192.168.1.50"))
    }

    @Test
    fun onSessionEnd_clearsBond() {
        manager.onClaim("quest-A", "192.168.1.50")
        manager.confirm()

        manager.onSessionEnd()
        assertEquals(PairingState.UNPAIRED, manager.pairingState.value)
        assertNull(manager.candidate.value)
    }

    @Test
    fun bondedQuest_ignoresStrayBroadcastFromSecondQuest() {
        manager.onClaim("quest-A", "192.168.1.50")
        manager.confirm()

        // A second Quest in the room keeps broadcasting; it must not disturb the existing bond.
        manager.onClaim("quest-B", "192.168.1.60")
        assertEquals(PairingState.BONDED, manager.pairingState.value)
        assertEquals(VrPairingManager.VrCandidate("quest-A", "192.168.1.50"), manager.candidate.value)
        assertFalse(manager.isAuthorized("quest-B", "192.168.1.60"))
    }
}
