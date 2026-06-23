package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import javax.inject.Inject
import javax.inject.Singleton

data class VariantCompilationResult(
    val compiledPoints: List<RoutePoint>,
    val totalDistanceMetres: Double
)

/**
 * One segment's part of a variant. [fromMetres]/[toMetres] slice [points], null
 * uses the full polyline.
 */
data class SegmentPolylineSlice(
    val points: List<RoutePoint>,
    val fromMetres: Double?,
    val toMetres: Double?
)

/**
 * Stitches segment polylines into one variant polyline. Trunk segments can be
 * sliced between junctions, and a first point within
 * [JUNCTION_DEDUP_THRESHOLD_M] of the previous segment's end is dropped.
 */
@Singleton
class VariantCompilationEngine @Inject constructor(
    private val distanceCalc: CumulativeDistanceCalculator
) {

    companion object {
        /** Drop the leading point of a segment if it is within this distance of the prior segment's tail. */
        const val JUNCTION_DEDUP_THRESHOLD_M = 5.0
    }

    /** Compile [slices], in traversal order, into one polyline. */
    fun compile(slices: List<SegmentPolylineSlice>): VariantCompilationResult {
        if (slices.isEmpty()) return VariantCompilationResult(emptyList(), 0.0)

        val stitched = mutableListOf<RoutePoint>()

        for ((index, slice) in slices.withIndex()) {
            // Apply slice if bounds are present; otherwise use full polyline.
            val segment: List<RoutePoint> = if (slice.fromMetres != null && slice.toMetres != null) {
                distanceCalc.slicePolyline(slice.points, slice.fromMetres, slice.toMetres)
            } else {
                slice.points
            }

            if (segment.isEmpty()) continue

            if (index == 0) {
                stitched.addAll(segment)
            } else {
                val lastPoint  = stitched.lastOrNull()
                val firstPoint = segment.first()
                val gapM = if (lastPoint != null) {
                    distanceCalc.haversineMetres(
                        lastPoint.lat, lastPoint.lon,
                        firstPoint.lat, firstPoint.lon
                    )
                } else Double.MAX_VALUE

                // Drop the first point of this segment if it coincides with
                // the tail of the previous segment (junction boundary dedup).
                val skip = if (gapM < JUNCTION_DEDUP_THRESHOLD_M) 1 else 0
                stitched.addAll(segment.drop(skip))
            }
        }

        val totalDistM = distanceCalc.pathDistanceMetres(stitched, smoothedOnly = false)
        return VariantCompilationResult(compiledPoints = stitched, totalDistanceMetres = totalDistM)
    }

    /** Same as [compile] without slice bounds. */
    @JvmName("compileRaw")
    fun compile(segmentPolylines: List<List<RoutePoint>>): VariantCompilationResult =
        compile(segmentPolylines.map { SegmentPolylineSlice(it, null, null) })
}
