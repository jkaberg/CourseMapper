package com.coursemapper.domain.model

/**
 * Start and finish markers are chosen per course, not per preset. Four courses
 * sharing one gantry want the endpoints placed once, and a preset shouldn't have
 * to be duplicated per distance for that. Every entry point goes through these.
 */

/** The two rule types that mark where a course begins and ends. */
private val ENDPOINT_TYPES = setOf(MarkerType.START, MarkerType.FINISH)

/** True when [type] is placed at a course endpoint rather than along it. */
fun MarkerType.isEndpoint(): Boolean = this in ENDPOINT_TYPES

/** Whether this rule list would place a marker of [type]. */
fun List<MarkerRule>.hasEndpoint(type: MarkerType): Boolean = any { it.type == type }

/**
 * These rules with START and FINISH as requested. Null leaves an endpoint as it
 * is. Idempotent, duplicate endpoint rules get normalised to one of each.
 */
fun List<MarkerRule>.withEndpoints(
    includeStart: Boolean?,
    includeFinish: Boolean?
): List<MarkerRule> {
    val start  = includeStart  ?: hasEndpoint(MarkerType.START)
    val finish = includeFinish ?: hasEndpoint(MarkerType.FINISH)
    // Bound outside buildList: inside it, `this` is the new mutable list and a
    // bare filterNot would silently read that instead of the receiver.
    val alongCourse = filterNot { it.type.isEndpoint() }
    return buildList {
        addAll(alongCourse)
        if (start)  add(MarkerRule(MarkerType.START))
        if (finish) add(MarkerRule(MarkerType.FINISH))
    }
}
