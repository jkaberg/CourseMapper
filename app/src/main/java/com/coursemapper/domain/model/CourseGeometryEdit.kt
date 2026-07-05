package com.coursemapper.domain.model

import org.json.JSONObject

/**
 * Start, finish and direction adjustments for a course, stored as intent.
 *
 * The recording is never edited. Several courses can share it, and it can be
 * re-cut later. Typical case is a track started in the car park and stopped past
 * the line, which puts every km marker early.
 *
 * [startOffsetMetres] and [keepLengthMetres] is the kept window. On a closed
 * loop it may wrap around, which is how the start line of a lap gets moved.
 * Applied in order: [reversed], window, [closeLoop].
 */
data class CourseGeometryEdit(
    /** Run the course the other way round. */
    val reversed: Boolean = false,
    /** Where the course starts, in metres along the base route. */
    val startOffsetMetres: Double = 0.0,
    /** Length to keep, 0 is everything (the whole lap on a loop). */
    val keepLengthMetres: Double = 0.0,
    /** Straight leg from finish back to start, for tracks that end a street short. */
    val closeLoop: Boolean = false
) {

    /** True when this edit leaves the base route exactly as recorded. */
    val isIdentity: Boolean
        get() = !reversed && startOffsetMetres <= 0.0 && keepLengthMetres <= 0.0 && !closeLoop

    fun toJson(): String = JSONObject().apply {
        put(KEY_REVERSED, reversed)
        put(KEY_START, startOffsetMetres)
        put(KEY_KEEP, keepLengthMetres)
        put(KEY_CLOSE, closeLoop)
    }.toString()

    companion object {
        /** The base route exactly as recorded. */
        val NONE = CourseGeometryEdit()

        private const val KEY_REVERSED = "reversed"
        private const val KEY_START = "startM"
        private const val KEY_KEEP = "keepM"
        private const val KEY_CLOSE = "closeLoop"

        /** Anything unreadable gives [NONE], a bad edit must never drop a course's geometry. */
        fun fromJson(json: String?): CourseGeometryEdit {
            if (json.isNullOrBlank()) return NONE
            return try {
                val o = JSONObject(json)
                CourseGeometryEdit(
                    reversed = o.optBoolean(KEY_REVERSED, false),
                    startOffsetMetres = o.optDouble(KEY_START, 0.0).takeIf { it.isFinite() } ?: 0.0,
                    keepLengthMetres = o.optDouble(KEY_KEEP, 0.0).takeIf { it.isFinite() } ?: 0.0,
                    closeLoop = o.optBoolean(KEY_CLOSE, false)
                )
            } catch (e: org.json.JSONException) {
                NONE
            }
        }
    }
}
