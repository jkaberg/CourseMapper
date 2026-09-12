package com.coursemapper.routing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coursemapper.domain.model.TravelProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Checks over the real network that bike mode comes back on cycle
 * infrastructure. Offline tests can't see this, the backend's costing is the
 * whole point. Not for CI, skips when the service can't be reached.
 */
@RunWith(AndroidJUnit4::class)
class RouteDirectionsOnDeviceTest {

    private val service = RouteDirectionsService()

    /** Innherredsveien, Trondheim: an arterial with a parallel cycleway the whole way. */
    private val corridorFromLat = 63.4331479
    private val corridorFromLon = 10.4109287
    private val corridorToLat = 63.4406607
    private val corridorToLon = 10.4427421

    private fun route(profile: TravelProfile) = runBlocking {
        service.route(
            profile = profile,
            fromLat = corridorFromLat, fromLon = corridorFromLon,
            toLat   = corridorToLat,   toLon   = corridorToLon
        )
    }

    @Test
    fun bikeDoesNotReturnTheCarRoute() {
        val car = route(TravelProfile.CAR)
        val bike = route(TravelProfile.BIKE)
        assumeTrue("routing service unreachable", car != null && bike != null)

        assertTrue("car route is degenerate", car!!.points.size >= 2)
        assertTrue("bike route is degenerate", bike!!.points.size >= 2)

        // Before the fix this sat near 100%: bike mode was handing back the
        // arterial the car route uses.  A cycling route down this corridor uses
        // the separated path, so the two geometries mostly do not coincide.
        val shared = sharedFraction(bike.points, car.points)
        assertTrue(
            "bike route still follows the car route (${(shared * 100).toInt()}% shared)",
            shared < 0.6
        )
    }

    /** Roads are weighted away for bikes, not forbidden - a road-only leg must still route. */
    @Test
    fun bikeStillUsesRoadsWhenThatIsTheOnlyWay() {
        // A short residential hop between two marker stops with no path option.
        val leg = runBlocking {
            service.route(
                profile = TravelProfile.BIKE,
                fromLat = 63.417748, fromLon = 10.395136,
                toLat   = 63.420593, toLon   = 10.393717
            )
        }
        assumeTrue("routing service unreachable", leg != null)
        assertNotNull(leg)
        assertTrue("no geometry for a road-only leg", leg!!.points.size >= 2)
        assertTrue("implausible detour on a 325 m hop", leg.distanceMetres < 800.0)
    }

    @Test
    fun everyProfileRoutesAndCarriesManeuvers() {
        for (profile in TravelProfile.entries) {
            val leg = route(profile)
            assumeTrue("routing service unreachable for $profile", leg != null)
            assertTrue("$profile: no geometry", leg!!.points.size >= 2)
            assertTrue("$profile: no distance", leg.distanceMetres > 0.0)
            assertTrue("$profile: no maneuvers", leg.steps.isNotEmpty())
            // Steps must be ordered along the leg for the banner's "in N metres".
            val offsets = leg.steps.map { it.startCumulativeMetres }
            assertTrue("$profile: maneuvers out of order", offsets == offsets.sorted())
        }
    }

    /** Fraction of [a]'s points that also lie on [b], to ~11 m. */
    private fun sharedFraction(
        a: List<Pair<Double, Double>>,
        b: List<Pair<Double, Double>>
    ): Double {
        if (a.isEmpty()) return 0.0
        val onB = a.count { (lat, lon) ->
            b.any { abs(it.first - lat) < 1e-4 && abs(it.second - lon) < 1e-4 }
        }
        return onB.toDouble() / a.size
    }
}
