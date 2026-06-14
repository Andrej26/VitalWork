package com.vitalwork.app.data.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic checks for the session-end watch reconciliation verdict (mirrors the Python proof). */
class WatchReconciliationReportTest {

    @Test
    fun ok_whenFullyTransferredAndAllInWindowRowsPersisted() {
        val r = WatchReconciliationReport(claimed = 1031, received = 1031, inScenario = 1024, dbRows = 1024, clockSkewMs = 40)
        assertTrue(r.ok)
        assertEquals(7, r.betweenScenario) // 1031 recorded − 1024 in-scenario
        assertFalse(r.clockSkewSuspect)
    }

    @Test
    fun mismatch_whenTransportDroppedRows() {
        // received < claimed → a chunk/row was lost in transfer.
        val r = WatchReconciliationReport(claimed = 1031, received = 1020, inScenario = 1013, dbRows = 1013, clockSkewMs = null)
        assertFalse(r.ok)
    }

    @Test
    fun mismatch_whenInWindowRowMissingFromDb() {
        // dbRows < inScenario → an in-scenario row never landed in the DB (the loss the fix prevents).
        val r = WatchReconciliationReport(claimed = 1031, received = 1031, inScenario = 1024, dbRows = 1023, clockSkewMs = 0)
        assertFalse(r.ok)
    }

    @Test
    fun clockSkewSuspect_onlyBeyondThreshold() {
        val within = WatchReconciliationReport(0, 0, 0, 0, clockSkewMs = WatchReconciliationReport.CLOCK_SKEW_WARN_MS)
        val beyond = WatchReconciliationReport(0, 0, 0, 0, clockSkewMs = WatchReconciliationReport.CLOCK_SKEW_WARN_MS + 1)
        val negative = WatchReconciliationReport(0, 0, 0, 0, clockSkewMs = -(WatchReconciliationReport.CLOCK_SKEW_WARN_MS + 1))
        assertFalse(within.clockSkewSuspect)
        assertTrue(beyond.clockSkewSuspect)   // watch clock behind
        assertTrue(negative.clockSkewSuspect) // watch clock ahead
        assertFalse(WatchReconciliationReport(0, 0, 0, 0, clockSkewMs = null).clockSkewSuspect)
    }

    @Test
    fun summary_reflectsVerdict() {
        assertTrue(WatchReconciliationReport(10, 10, 8, 8, null).summary().contains("OK"))
        assertTrue(WatchReconciliationReport(10, 9, 8, 8, null).summary().contains("MISMATCH"))
    }
}
