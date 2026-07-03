package com.coursemapper.routing

import com.coursemapper.domain.model.TravelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Route between two stops for the selected travel profile.
 *
 * Valhalla first, since OSRM's bike profile has no idea about cycle
 * infrastructure and happily sends you down the arterial road. With
 * `use_roads = 0` a 4 km leg went from 44 % to 16 % big roads for 0.6 % more
 * distance. `RouteDirectionsOnDeviceTest` covers this.
 *
 * Falls back to OSRM, then null (the caller draws a dashed straight line).
 * Guidance is never required, runs work fine offline.
 */
@Singleton
class RouteDirectionsService @Inject constructor() {

    companion object {
        private const val VALHALLA_URL = "https://valhalla1.openstreetmap.de/route"
        private const val OSRM_BASE_URL = "https://routing.openstreetmap.de"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 6_000
        private const val USER_AGENT = "CourseMapper/1.0"

        /** Valhalla costing model for each travel profile. */
        private fun costing(profile: TravelProfile) = when (profile) {
            TravelProfile.CAR  -> "auto"
            TravelProfile.BIKE -> "bicycle"
            TravelProfile.FOOT -> "pedestrian"
        }

        /**
         * Per profile costing, null for Valhalla defaults. Bike stays off roads
         * with traffic, hybrid bike, neutral on hills, mild on bad surfaces.
         * Foot defaults already avoid big roads.
         */
        private fun costingOptions(profile: TravelProfile): JSONObject? = when (profile) {
            TravelProfile.BIKE -> JSONObject()
                .put("bicycle_type", "Hybrid")
                .put("use_roads", 0.0)
                .put("use_hills", 0.5)
                .put("avoid_bad_surfaces", 0.25)
            // Measured: Valhalla's pedestrian defaults already keep off big
            // roads entirely.  Nothing to add.
            TravelProfile.FOOT -> null
            TravelProfile.CAR  -> null
        }

        private fun osrmPathSegment(profile: TravelProfile) = when (profile) {
            TravelProfile.CAR  -> "routed-car/route/v1/driving"
            TravelProfile.BIKE -> "routed-bike/route/v1/cycling"
            TravelProfile.FOOT -> "routed-foot/route/v1/walking"
        }

        /** The Valhalla request body, as the `json=` query parameter takes it. */
        internal fun valhallaRequestJson(
            profile: TravelProfile,
            fromLat: Double,
            fromLon: Double,
            toLat: Double,
            toLon: Double
        ): String {
            val locations = JSONArray()
                .put(JSONObject().put("lat", fromLat).put("lon", fromLon))
                .put(JSONObject().put("lat", toLat).put("lon", toLon))
            val costingName = costing(profile)
            val request = JSONObject()
                .put("locations", locations)
                .put("costing", costingName)
                .put("directions_options", JSONObject().put("units", "kilometers"))
            costingOptions(profile)?.let {
                request.put("costing_options", JSONObject().put(costingName, it))
            }
            return request.toString()
        }

        /** Parse a Valhalla `/route` response, null when there's no usable route. */
        internal fun parseValhallaResponse(body: String): DirectionsLeg? {
            val trip = JSONObject(body).optJSONObject("trip") ?: return null
            // 0 == "Found route between points"; anything else is not a route.
            if (trip.optInt("status", -1) != 0) return null
            val leg = trip.optJSONArray("legs")?.optJSONObject(0) ?: return null

            val points = decodePolyline6(leg.optString("shape"))
            if (points.size < 2) return null

            // The summary is in the requested units (kilometres).
            val distanceMetres = trip.optJSONObject("summary")
                ?.optDouble("length", Double.NaN)
                ?.takeIf { !it.isNaN() }
                ?.times(1_000.0)
                ?: return null

            val steps = mutableListOf<Step>()
            var cumulative = 0.0
            val maneuvers = leg.optJSONArray("maneuvers")
            if (maneuvers != null) {
                for (i in 0 until maneuvers.length()) {
                    val maneuver = maneuvers.getJSONObject(i)
                    valhallaInstruction(maneuver)?.let { steps.add(Step(it, cumulative)) }
                    cumulative += maneuver.optDouble("length", 0.0) * 1_000.0
                }
            }

            return DirectionsLeg(
                points         = points,
                distanceMetres = distanceMetres,
                steps          = steps,
                source         = Backend.VALHALLA
            )
        }

        /** Valhalla maneuver types that open a leg - no banner, like OSRM's depart. */
        private val START_MANEUVER_TYPES = setOf(1, 2, 3)

        /** Valhalla maneuver types that end a leg. */
        private val DESTINATION_MANEUVER_TYPES = setOf(4, 5, 6)

        /** Banner text for a maneuver, null to skip. Valhalla's own text minus the full stop. */
        private fun valhallaInstruction(maneuver: JSONObject): String? {
            val type = maneuver.optInt("type", -1)
            if (type in START_MANEUVER_TYPES) return null
            if (type in DESTINATION_MANEUVER_TYPES) return "Arrive at the stop"
            return maneuver.optString("instruction")
                .trim()
                .removeSuffix(".")
                .takeIf { it.isNotBlank() }
        }

        /** Encoded polyline with six decimals. */
        internal fun decodePolyline6(encoded: String): List<Pair<Double, Double>> {
            val points = mutableListOf<Pair<Double, Double>>()
            var index = 0
            var lat = 0
            var lon = 0
            while (index < encoded.length) {
                var result = 0
                var shift = 0
                var b: Int
                do {
                    if (index >= encoded.length) return points
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

                result = 0
                shift = 0
                do {
                    if (index >= encoded.length) return points
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1

                points.add(lat / 1e6 to lon / 1e6)
            }
            return points
        }

        /** Parse an OSRM route response, null when there's no usable route. */
        internal fun parseOsrmResponse(body: String): DirectionsLeg? {
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") return null
            val routes = root.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val route = routes.getJSONObject(0)

            val coords = route.optJSONObject("geometry")?.optJSONArray("coordinates")
                ?: return null
            val points = (0 until coords.length()).map { i ->
                val pair = coords.getJSONArray(i)
                // GeoJSON order is (lon, lat); the app uses (lat, lon).
                pair.getDouble(1) to pair.getDouble(0)
            }
            if (points.size < 2) return null

            val steps = mutableListOf<Step>()
            var cumulative = 0.0
            val legs = route.optJSONArray("legs")
            if (legs != null) {
                for (l in 0 until legs.length()) {
                    val legSteps = legs.getJSONObject(l).optJSONArray("steps") ?: continue
                    for (s in 0 until legSteps.length()) {
                        val step = legSteps.getJSONObject(s)
                        val instruction = buildInstruction(step)
                        if (instruction != null) {
                            steps.add(Step(instruction, cumulative))
                        }
                        cumulative += step.optDouble("distance", 0.0)
                    }
                }
            }

            return DirectionsLeg(
                points         = points,
                distanceMetres = route.optDouble("distance", 0.0),
                steps          = steps,
                source         = Backend.OSRM
            )
        }

        /** Human-readable instruction for one OSRM step; null = skip (e.g. depart). */
        private fun buildInstruction(step: JSONObject): String? {
            val maneuver = step.optJSONObject("maneuver") ?: return null
            val type     = maneuver.optString("type")
            val modifier = maneuver.optString("modifier")
            val name     = step.optString("name").takeIf { it.isNotBlank() }

            val action = when (type) {
                "depart"       -> return null   // "start" needs no banner
                "arrive"       -> "Arrive at the stop"
                "turn", "end of road", "fork" -> when (modifier) {
                    "left", "sharp left"   -> "Turn left"
                    "right", "sharp right" -> "Turn right"
                    "slight left"          -> "Bear left"
                    "slight right"         -> "Bear right"
                    "uturn"                -> "Make a U-turn"
                    "straight"             -> "Continue straight"
                    else                   -> "Continue"
                }
                "roundabout", "rotary" -> "Take the roundabout"
                "merge"        -> "Merge"
                "on ramp"      -> "Take the ramp"
                "off ramp"     -> "Take the exit"
                "continue", "new name" -> when (modifier) {
                    "left"  -> "Turn left"
                    "right" -> "Turn right"
                    else    -> "Continue"
                }
                else -> "Continue"
            }
            return if (name != null && type != "arrive") "$action onto $name" else action
        }
    }

    /** One upcoming maneuver along a leg. */
    data class Step(
        val instruction: String,
        /** Metres from the leg start to where this maneuver happens. */
        val startCumulativeMetres: Double
    )

    /** Which backend answered. A shorter [OSRM] bike route isn't better than a [VALHALLA] one. */
    enum class Backend { OSRM, VALHALLA }

    /** A routed path between two points, with distance and maneuvers. */
    data class DirectionsLeg(
        /** (lat, lon) pairs of the road geometry. */
        val points: List<Pair<Double, Double>>,
        val distanceMetres: Double,
        val steps: List<Step>,
        val source: Backend = Backend.VALHALLA
    )

    /** Route using [profile], null on any failure. */
    suspend fun route(
        profile: TravelProfile,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): DirectionsLeg? = withContext(Dispatchers.IO) {
        valhallaRoute(profile, fromLat, fromLon, toLat, toLon)
            ?: osrmRoute(profile, fromLat, fromLon, toLat, toLon)
    }

    private fun valhallaRoute(
        profile: TravelProfile,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): DirectionsLeg? = fetch(
        URL(
            VALHALLA_URL + "?json=" + URLEncoder.encode(
                valhallaRequestJson(profile, fromLat, fromLon, toLat, toLon), "UTF-8"
            )
        ),
        Companion::parseValhallaResponse
    )

    private fun osrmRoute(
        profile: TravelProfile,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): DirectionsLeg? = fetch(
        URL(
            "$OSRM_BASE_URL/${osrmPathSegment(profile)}/" +
                "$fromLon,$fromLat;$toLon,$toLat" +
                "?overview=full&geometries=geojson&steps=true"
        ),
        Companion::parseOsrmResponse
    )

    /** GET [url] and hand the body to [parse]; null on any failure at all. */
    private fun fetch(url: URL, parse: (String) -> DirectionsLeg?): DirectionsLeg? = try {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout    = READ_TIMEOUT_MS
            requestMethod  = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode != 200) {
                null
            } else {
                parse(connection.inputStream.bufferedReader().use { it.readText() })
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
