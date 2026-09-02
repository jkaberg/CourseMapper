package com.coursemapper.ui.navigate

import com.coursemapper.domain.model.OfflinePackStatus
import com.coursemapper.ui.record.GateState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the readiness gate may continue by itself. Tests the predicate, not the
 * countdown, so no fake clock. Green only, tiles present, and Wait always wins.
 */
class NavigationGateAutoAdvanceTest {

    private fun state(
        gate: GateState = GateState.Green(accuracyMetres = 6f, ageSeconds = 2L),
        offline: OfflinePackStatus? = OfflinePackStatus.READY,
        checking: Boolean = false,
        enabled: Boolean = true,
        cancelled: Boolean = false
    ) = NavigationGateUiState(
        gateState            = gate,
        offlineStatus        = offline,
        offlineChecking      = checking,
        autoAdvanceEnabled   = enabled,
        autoAdvanceCancelled = cancelled
    )

    @Test
    fun `green with tiles ready advances`() {
        assertTrue(state().canAutoAdvance)
    }

    @Test
    fun `tiles still downloading are not a reason to stand in a car park`() {
        // Navigation degrades to online tiles rather than failing, and the
        // download continues while the rider drives.
        assertTrue(state(offline = OfflinePackStatus.DOWNLOADING).canAutoAdvance)
        assertTrue(state(offline = OfflinePackStatus.QUEUED).canAutoAdvance)
    }

    @Test
    fun `missing offline coverage always waits`() {
        assertFalse(state(offline = null).canAutoAdvance)
    }

    /** A pack stopped part way isn't coverage, and shows as PAUSED. */
    @Test
    fun `a paused download waits, and is not the same thing as missing`() {
        assertFalse(state(offline = OfflinePackStatus.PAUSED).canAutoAdvance)
    }

    /** Stale tiles are old, not absent; a run on them beats no run. */
    @Test
    fun `stale coverage still advances`() {
        assertTrue(state(offline = OfflinePackStatus.STALE).canAutoAdvance)
    }

    @Test
    fun `a failed download waits`() {
        assertFalse(state(offline = OfflinePackStatus.FAILED).canAutoAdvance)
    }

    @Test
    fun `an unfinished offline check waits rather than guessing`() {
        assertFalse(state(checking = true).canAutoAdvance)
    }

    @Test
    fun `yellow never advances - that is what the gate is for`() {
        assertFalse(
            state(gate = GateState.Yellow(accuracyMetres = 40f, ageSeconds = 30L)).canAutoAdvance
        )
    }

    @Test
    fun `red, initialising, permission and settings problems are a full stop`() {
        assertFalse(state(gate = GateState.Red).canAutoAdvance)
        assertFalse(state(gate = GateState.Initializing).canAutoAdvance)
        assertFalse(state(gate = GateState.NoPermission).canAutoAdvance)
        assertFalse(state(gate = GateState.SettingsRequired).canAutoAdvance)
    }

    @Test
    fun `Wait cancels it for the rest of the visit`() {
        assertFalse(state(cancelled = true).canAutoAdvance)
    }

    @Test
    fun `the Settings toggle turns it off entirely`() {
        assertFalse(state(enabled = false).canAutoAdvance)
    }

    @Test
    fun `a reading that degrades after the countdown starts stops it`() {
        // The composable keys its delay on this flag, so green → yellow between
        // fixes cancels the pending advance rather than letting it fire late.
        val green = state()
        assertTrue(green.canAutoAdvance)
        val degraded = green.copy(
            gateState = GateState.Yellow(accuracyMetres = 45f, ageSeconds = 20L)
        )
        assertFalse(degraded.canAutoAdvance)
    }
}
