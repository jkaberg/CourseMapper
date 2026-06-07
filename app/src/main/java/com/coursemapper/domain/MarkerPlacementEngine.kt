package com.coursemapper.domain

import com.coursemapper.domain.model.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Places markers on a composed course from a [MarkerPreset].
 *
 * - START at 0, FINISH at [totalDistanceMetres]
 * - interval markers every [MarkerRule.intervalMetres] of course distance, so
 *   the 5 km sign is where the runner has covered 5 km
 * - with lap metadata each distance is resolved on the lap template, so the
 *   same offset in different laps gives identical coordinates (one stop, several
 *   signs) and nothing leaks across a lap join
 * - sorted by distance and numbered from 1
 *
 * [distanceScale] separates course metres from polyline metres. A drawn line is
 * always longer than the measured course - on a 42.195 km course drawn at 42.650
 * km the 42 km sign lands 448 m early. Rules and stored distances are in course
 * metres, and positions are found at `courseMetres × distanceScale`. Start and
 * finish can't move, only the markers in between. 1.0 means unmeasured.
 */
@Singleton
class MarkerPlacementEngine @Inject constructor(
    private val distanceCalculator: CumulativeDistanceCalculator
) {

    data class PlacementResult(val markers: List<DistanceMarker>)

    /**
     * Markers for a composed course.
     *
     * @param totalDistanceMetres from [CourseCompositionEngine], used for FINISH
     * @param courseId 0 before the course is saved
     * @param distanceScale see [com.coursemapper.domain.model.ComposedCourse.distanceScale]
     */
    fun place(
        composedPoints: List<RoutePoint>,
        totalDistanceMetres: Double,
        preset: MarkerPreset,
        courseId: Long = 0L,
        lapTemplatePoints: List<RoutePoint> = emptyList(),
        laps: List<CourseLap> = emptyList(),
        distanceScale: Double = 1.0
    ): PlacementResult {
        if (composedPoints.isEmpty() || totalDistanceMetres <= 0.0) {
            return PlacementResult(emptyList())
        }

        // A nonsensical scale must not take a course's markers away from it - 
        // fall back to the uncalibrated behaviour rather than dividing by zero
        // or placing every sign at NaN.
        val scale = distanceScale.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        // The course's own length, in the metres the organiser measured.
        val courseTotal = totalDistanceMetres / scale

        val pts = composedPoints.filter { it.isSmoothed }.ifEmpty { composedPoints }
        val templatePts = lapTemplatePoints.filter { it.isSmoothed }.ifEmpty { lapTemplatePoints }
        val useLapAwareIntervals = laps.isNotEmpty() && templatePts.isNotEmpty()
        val staged = mutableListOf<DistanceMarker>()

        for (rule in preset.rules) {
            when (rule.type) {
                MarkerType.START -> {
                    val pos = interpolate(pts, 0.0) ?: continue
                    staged.add(marker(courseId, MarkerType.START, "Start", 0.0, pos))
                }

                MarkerType.FINISH -> {
                    // from the polyline length, the round trip through the
                    // scale isn't exact and the finish belongs on the last point
                    val pos = interpolate(pts, totalDistanceMetres) ?: continue
                    staged.add(marker(courseId, MarkerType.FINISH, "Finish", courseTotal, pos))
                }

                MarkerType.DISTANCE -> {
                    if (useLapAwareIntervals) {
                        placeAtIntervalByLap(
                            templatePts, laps, courseId, rule, courseTotal, scale, staged,
                            labelFn = { _, distM ->
                                val km = distM / 1_000.0
                                "${String.format("%.1f", km)} km"
                            }
                        )
                    } else {
                        placeAtInterval(
                            pts, courseId, rule, courseTotal, scale, staged,
                            labelFn = { _, distM ->
                                val km = distM / 1_000.0
                                "${String.format("%.1f", km)} km"
                            }
                        )
                    }
                }

                MarkerType.CHECKPOINT -> {
                    if (rule.intervalMetres > 0.0) {
                        if (useLapAwareIntervals) {
                            placeAtIntervalByLap(
                                templatePts, laps, courseId, rule, courseTotal, scale, staged,
                                labelFn = { n, _ -> "CP$n" }
                            )
                        } else {
                            placeAtInterval(
                                pts, courseId, rule, courseTotal, scale, staged,
                                labelFn = { n, _ -> "CP$n" }
                            )
                        }
                    }
                }

                MarkerType.WATER_STATION -> {
                    if (rule.intervalMetres > 0.0) {
                        if (useLapAwareIntervals) {
                            placeAtIntervalByLap(
                                templatePts, laps, courseId, rule, courseTotal, scale, staged,
                                labelFn = { n, _ -> "Water $n" }
                            )
                        } else {
                            placeAtInterval(
                                pts, courseId, rule, courseTotal, scale, staged,
                                labelFn = { n, _ -> "Water $n" }
                            )
                        }
                    }
                }

                MarkerType.CUSTOM_DISTANCES -> {
                    rule.customDistancesMetres.sorted().forEach { cumDist ->
                        if (cumDist > 0.0 && cumDist <= courseTotal) {
                            val pos = interpolate(pts, cumDist * scale) ?: return@forEach
                            val km = cumDist / 1_000.0
                            staged.add(
                                marker(
                                    courseId = courseId,
                                    type     = MarkerType.CUSTOM_DISTANCES,
                                    label    = "${String.format("%.1f", km)} km",
                                    cumDist  = cumDist,
                                    pos      = pos
                                )
                            )
                        }
                    }
                }
            }
        }

        // Sort by cumulative distance and reassign 1-based sequence indices.
        val sorted = staged.sortedBy { it.cumulativeDistanceMetres }
        val indexed = sorted.mapIndexed { i, m -> m.copy(sequenceIndex = i + 1) }
        return PlacementResult(indexed)
    }

    /** [courseTotalMetres] in course metres, [scale] polyline metres per course metre. */
    private fun placeAtInterval(
        pts: List<RoutePoint>,
        courseId: Long,
        rule: MarkerRule,
        courseTotalMetres: Double,
        scale: Double,
        out: MutableList<DistanceMarker>,
        labelFn: (num: Int, distM: Double) -> String
    ) {
        val interval = rule.intervalMetres
        var cumDist = interval
        var n = 1
        while (cumDist < courseTotalMetres - 0.5) {
            val pos = interpolate(pts, cumDist * scale) ?: break
            out.add(marker(courseId, rule.type, labelFn(n, cumDist), cumDist, pos))
            n++
            cumDist += interval
        }
    }

    /**
     * Interval markers at course distances, resolved through the lap template.
     *
     * The counter runs over the whole course, not per lap - per lap dropped every
     * lap boundary km (17 markers instead of 21 for a half on a 5 km loop). A
     * distance exactly on a boundary maps to offset 0 of the next lap.
     *
     * Lap lengths are polyline metres, so they're divided by [scale] for the
     * counter and offsets multiplied back before interpolating.
     */
    private fun placeAtIntervalByLap(
        templatePts: List<RoutePoint>,
        laps: List<CourseLap>,
        courseId: Long,
        rule: MarkerRule,
        courseTotalMetres: Double,
        scale: Double,
        out: MutableList<DistanceMarker>,
        labelFn: (num: Int, distM: Double) -> String
    ) {
        val interval = rule.intervalMetres
        if (interval <= 0.0) return

        // Course distance at which each lap begins.
        val lapStarts = ArrayList<Double>(laps.size)
        var acc = 0.0
        laps.forEach { lap -> lapStarts.add(acc); acc += lap.distanceMetres / scale }

        var markerNumber = 1
        var cumDist = interval
        while (cumDist < courseTotalMetres - 0.5) {
            val lapIdx      = lapStarts.indexOfLast { it <= cumDist + 1e-9 }.coerceAtLeast(0)
            val offsetInLap = cumDist - lapStarts[lapIdx]
            val pos = interpolate(templatePts, offsetInLap * scale) ?: break
            out.add(marker(courseId, rule.type, labelFn(markerNumber, cumDist), cumDist, pos))
            markerNumber++
            cumDist += interval
        }
    }

    private fun interpolate(pts: List<RoutePoint>, targetMetres: Double): Pair<Double, Double>? =
        distanceCalculator.interpolateAt(pts, targetMetres, smoothedOnly = false)

    private fun marker(
        courseId: Long,
        type: MarkerType,
        label: String,
        cumDist: Double,
        pos: Pair<Double, Double>
    ) = DistanceMarker(
        id                       = 0L,
        courseId                 = courseId,
        sequenceIndex            = 0, // Assigned after sorting
        lat                      = pos.first,
        lon                      = pos.second,
        cumulativeDistanceMetres = cumDist,
        type                     = type,
        label                    = label,
        isManuallyMoved          = false
    )
}
