package com.coursemapper.domain

import com.coursemapper.domain.model.OfflineDownloadPolicy
import com.coursemapper.domain.model.OfflinePackChoice
import com.coursemapper.domain.model.OfflinePackStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offer, silently download, or do nothing. Asks once when the course is created
 * (at home, on wifi), never when the run starts at the venue.
 */
@Singleton
class OfflinePromptPolicy @Inject constructor() {

    enum class Decision {
        /** Show the prompt and wait for an answer. */
        ASK,

        /** Start the download without asking. */
        DOWNLOAD_SILENTLY,

        /** Do nothing; the pack is covered, refused, or already in flight. */
        STAY_QUIET
    }

    /** When a course was just created. The existing* params describe a pack already covering it. */
    fun onCourseCreated(
        policy: OfflineDownloadPolicy,
        existingStatus: OfflinePackStatus?,
        existingChoice: OfflinePackChoice?
    ): Decision {
        // Already downloaded, or already downloading: nothing to decide.
        if (existingStatus != null && (existingStatus.isUsable || existingStatus.isInFlight)) {
            return Decision.STAY_QUIET
        }
        // The user has already said no to this area.  Asking again for every
        // new course at the same venue is exactly the nagging that makes people
        // turn a feature off for good.
        if (existingChoice == OfflinePackChoice.DECLINED) return Decision.STAY_QUIET

        return when (policy) {
            OfflineDownloadPolicy.NEVER -> Decision.STAY_QUIET
            OfflineDownloadPolicy.ALWAYS -> Decision.DOWNLOAD_SILENTLY
            OfflineDownloadPolicy.ASK -> Decision.ASK
        }
    }

    /**
     * Whether to show "no offline map". A declined pack still counts as
     * uncovered, not now isn't the same as ready.
     */
    fun shouldWarnUncovered(status: OfflinePackStatus?): Boolean =
        status == null || !(status.isUsable || status.isInFlight)

    /**
     * Whether the navigation gate offers a download. Ignores the policy, being at
     * the start line without a map overrides an earlier "not now".
     */
    fun gateOffersDownload(status: OfflinePackStatus?): Boolean =
        status == null || !(status.isUsable || status.isInFlight)
}
