package com.coursemapper.domain

import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.RaceDistance
import com.coursemapper.domain.model.targetCentimetresToMetres
import com.coursemapper.domain.model.targetMetresToCentimetres
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tapped target must reach the composition engine as the same distance.
 * Tests the round trip back out, a centimetre/metre mixup once composed a half
 * marathon as a single lap while the preview looked fine.
 */
class TargetDistanceUnitsTest {

    private val calc = CumulativeDistanceCalculator()
    private val composer = CourseCompositionEngine(calc)

    @Test
    fun `a half marathon round-trips through the stored unit`() {
        val cm = RaceDistance.HALF_MARATHON.metres.targetMetresToCentimetres()
        assertEquals(2_109_750L, cm)
        assertEquals(21_097.5, cm.targetCentimetresToMetres(), 1e-9)
    }

    @Test
    fun `every race distance round-trips exactly`() {
        for (distance in RaceDistance.entries) {
            val cm = distance.metres.targetMetresToCentimetres()
            assertEquals(
                "round trip for ${distance.label}",
                distance.metres,
                cm.targetCentimetresToMetres(),
                1e-9
            )
            assertEquals(
                "chip re-selects for ${distance.label}",
                distance,
                RaceDistance.matchingCentimetres(cm)
            )
        }
    }

    @Test
    fun `no target is zero in both directions`() {
        assertEquals(0.0, 0L.targetCentimetresToMetres(), 0.0)
        assertEquals(0.0, (-1L).targetCentimetresToMetres(), 0.0)
        assertEquals(0L, 0.0.targetMetresToCentimetres())
        assertEquals(0L, (-5.0).targetMetresToCentimetres())
    }

    /** On the real 5 km loop the stored centimetres must compose a half, not one 5 215 m lap. */
    @Test
    fun `a stored half marathon target composes a half marathon`() {
        val base = GpxFixtures.load(GpxFixtures.FIVE_KM)
        val storedCm = RaceDistance.HALF_MARATHON.metres.targetMetresToCentimetres()

        val spec = CourseBuildSpec.TargetDistance(
            totalMetres = storedCm.targetCentimetresToMetres(),
            lapCount = 1
        )
        val composition = composer.compose(base, spec)

        assertEquals(21_097.5, composition.totalDistanceMetres, 0.5)
        // Three full laps of the loop-closed 5 km plus a trailing partial.
        assertEquals(3, composition.laps.count { !it.isPartial })
        assertTrue("expected a trailing partial lap", composition.laps.last().isPartial)

        // The composed polyline really is that long - the declared total is not
        // arithmetic floating free of the geometry.
        assertEquals(
            composition.totalDistanceMetres,
            calc.pathDistanceMetres(composition.composedPoints, smoothedOnly = false),
            0.5
        )
    }

    /** The persisted target must read back as the same target in the workspace. */
    @Test
    fun `a persisted target reopens as the same target`() {
        val storedCm = RaceDistance.HALF_MARATHON.metres.targetMetresToCentimetres()
        val spec = CourseBuildSpec.TargetDistance(storedCm.targetCentimetresToMetres())

        // What rebuildCourse writes to buildSpecTargetMetres, and reads back.
        val persistedMetres = spec.totalMetres
        val reopened = CourseBuildSpec.fromDb(
            CourseBuildSpec.TargetDistance.KIND, spec.lapCount, persistedMetres
        ) as CourseBuildSpec.TargetDistance

        assertEquals(storedCm, reopened.totalMetres.targetMetresToCentimetres())
        assertNotNull(
            "the Half chip must still read as selected",
            RaceDistance.matchingCentimetres(reopened.totalMetres.targetMetresToCentimetres())
        )
    }
}
