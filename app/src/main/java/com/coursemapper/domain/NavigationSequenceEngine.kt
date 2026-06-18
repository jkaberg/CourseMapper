package com.coursemapper.domain

import com.coursemapper.domain.model.RunStop
import com.coursemapper.domain.model.RunStopState

/** Visit-order transformations used by placement-run navigation. */
object NavigationSequenceEngine {

    /**
     * Make [stopId] next, then continue from where it was and wrap around.
     * Resolved stops stay first. Pending 1,2,3,4 with 3 selected gives 3,4,1,2.
     */
    fun takeNextThenContinue(stops: List<RunStop>, stopId: Long): List<RunStop> {
        val ordered = stops.sortedBy { it.stopIndex }
        val targetIndex = ordered.indexOfFirst {
            it.id == stopId && it.state == RunStopState.PENDING
        }
        if (targetIndex < 0) return ordered

        val target = ordered[targetIndex]
        val resolved = ordered.filter { it.state != RunStopState.PENDING }
        val circularPending = (ordered.drop(targetIndex + 1) + ordered.take(targetIndex))
            .filter { it.state == RunStopState.PENDING }

        return (resolved + target + circularPending).mapIndexed { index, stop ->
            stop.copy(stopIndex = index)
        }
    }
}
