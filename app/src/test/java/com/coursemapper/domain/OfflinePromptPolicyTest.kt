package com.coursemapper.domain

import com.coursemapper.domain.OfflinePromptPolicy.Decision
import com.coursemapper.domain.model.OfflineDownloadPolicy
import com.coursemapper.domain.model.OfflinePackChoice
import com.coursemapper.domain.model.OfflinePackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Policy × prior answer × current coverage. Asking twice is how people switch a feature off. */
class OfflinePromptPolicyTest {

    private val policy = OfflinePromptPolicy()

    private fun decide(
        setting: OfflineDownloadPolicy = OfflineDownloadPolicy.ASK,
        status: OfflinePackStatus? = null,
        choice: OfflinePackChoice? = null
    ) = policy.onCourseCreated(setting, status, choice)

    @Test
    fun `a brand-new course with no pack is offered one`() {
        assertEquals(Decision.ASK, decide())
    }

    @Test
    fun `ALWAYS downloads without asking`() {
        assertEquals(
            Decision.DOWNLOAD_SILENTLY,
            decide(setting = OfflineDownloadPolicy.ALWAYS)
        )
    }

    @Test
    fun `NEVER says nothing at all`() {
        assertEquals(Decision.STAY_QUIET, decide(setting = OfflineDownloadPolicy.NEVER))
    }

    /** One no covers the area, the fourth import at the same venue doesn't ask again. */
    @Test
    fun `a declined area is never offered again, under any policy`() {
        OfflineDownloadPolicy.entries.forEach { setting ->
            assertEquals(
                "$setting must respect a previous no",
                Decision.STAY_QUIET,
                decide(setting = setting, choice = OfflinePackChoice.DECLINED)
            )
        }
    }

    @Test
    fun `an area already covered is not offered again`() {
        assertEquals(
            Decision.STAY_QUIET,
            decide(status = OfflinePackStatus.READY, choice = OfflinePackChoice.ACCEPTED)
        )
    }

    @Test
    fun `a stale pack still counts as covered - it draws a map`() {
        assertEquals(Decision.STAY_QUIET, decide(status = OfflinePackStatus.STALE))
    }

    @Test
    fun `a download already in flight is not offered again`() {
        assertEquals(Decision.STAY_QUIET, decide(status = OfflinePackStatus.DOWNLOADING))
        assertEquals(Decision.STAY_QUIET, decide(status = OfflinePackStatus.QUEUED))
    }

    /** Failed or paused isn't coverage, offering again is the only way to get a map. */
    @Test
    fun `a failed pack is offered again rather than counted as covered`() {
        assertEquals(
            Decision.ASK,
            decide(status = OfflinePackStatus.FAILED, choice = OfflinePackChoice.ACCEPTED)
        )
        assertEquals(
            Decision.ASK,
            decide(status = OfflinePackStatus.PAUSED, choice = OfflinePackChoice.ACCEPTED)
        )
    }

    @Test
    fun `a failed pack the user declined stays quiet - they said no`() {
        assertEquals(
            Decision.STAY_QUIET,
            decide(status = OfflinePackStatus.FAILED, choice = OfflinePackChoice.DECLINED)
        )
    }

    @Test
    fun `ALWAYS re-downloads a failed pack without asking`() {
        assertEquals(
            Decision.DOWNLOAD_SILENTLY,
            decide(setting = OfflineDownloadPolicy.ALWAYS, status = OfflinePackStatus.FAILED)
        )
    }

    /** Declining hides the dialog, not the fact that there's no map. */
    @Test
    fun `an uncovered course is still shown as uncovered after declining`() {
        assertTrue(policy.shouldWarnUncovered(null))
        assertTrue(policy.shouldWarnUncovered(OfflinePackStatus.FAILED))
        assertTrue(policy.shouldWarnUncovered(OfflinePackStatus.PAUSED))
    }

    @Test
    fun `a covered or downloading course raises no warning`() {
        assertFalse(policy.shouldWarnUncovered(OfflinePackStatus.READY))
        assertFalse(policy.shouldWarnUncovered(OfflinePackStatus.STALE))
        assertFalse(policy.shouldWarnUncovered(OfflinePackStatus.DOWNLOADING))
        assertFalse(policy.shouldWarnUncovered(OfflinePackStatus.QUEUED))
    }

    /** The gate ignores the policy, at the start line an earlier "not now" has expired. */
    @Test
    fun `the gate offers a download whenever there is not one`() {
        assertTrue(policy.gateOffersDownload(null))
        assertTrue(policy.gateOffersDownload(OfflinePackStatus.FAILED))
        assertTrue(policy.gateOffersDownload(OfflinePackStatus.PAUSED))
        assertFalse(policy.gateOffersDownload(OfflinePackStatus.READY))
        assertFalse(policy.gateOffersDownload(OfflinePackStatus.DOWNLOADING))
    }

    @Test
    fun `an unknown or missing stored policy falls back to asking`() {
        assertEquals(OfflineDownloadPolicy.ASK, OfflineDownloadPolicy.fromKey(null))
        assertEquals(OfflineDownloadPolicy.ASK, OfflineDownloadPolicy.fromKey("nonsense"))
        assertEquals(
            OfflineDownloadPolicy.ALWAYS,
            OfflineDownloadPolicy.fromKey("ALWAYS")
        )
    }
}
