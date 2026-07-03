package com.coursemapper.routing

import com.coursemapper.domain.model.TravelProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouteDirectionsServiceTest {

    /** Real trimmed response from valhalla1.openstreetmap.de, a 148 m cycleway hop in Trondheim. */
    private val valhallaBody = """
        {
          "trip": {
            "status": 0,
            "status_message": "Found route between points",
            "units": "kilometers",
            "summary": { "time": 40.1, "length": 0.148 },
            "legs": [
              {
                "shape": "yfj}wBkhsyRcF?kKdDeRtG_E\\yAw@a@}_@]sCKbBqAlVAhHAlB",
                "maneuvers": [
                  { "type": 1,  "instruction": "Bike north on the cycleway.",          "length": 0.086 },
                  { "type": 10, "instruction": "Turn right onto the cycleway.",        "length": 0.030 },
                  { "type": 14, "instruction": "Make a sharp left onto the cycleway.", "length": 0.032 },
                  { "type": 4,  "instruction": "You have arrived at your destination.","length": 0.0   }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `decodes polyline6 geometry as lat-lon pairs`() {
        val leg = RouteDirectionsService.parseValhallaResponse(valhallaBody)
        assertNotNull(leg)
        assertEquals(12, leg!!.points.size)
        // Six decimal places, not the five of a standard encoded polyline: a
        // five-place decode would put these points 10x further from the stop.
        assertEquals(63.411837, leg.points.first().first, 1e-6)
        assertEquals(10.397846, leg.points.first().second, 1e-6)
        assertEquals(63.412678, leg.points.last().first, 1e-6)
        assertEquals(10.397609, leg.points.last().second, 1e-6)
    }

    @Test
    fun `converts the kilometre summary to metres`() {
        val leg = RouteDirectionsService.parseValhallaResponse(valhallaBody)!!
        assertEquals(148.0, leg.distanceMetres, 1e-6)
    }

    @Test
    fun `keeps Valhalla instructions, skips the start, and rewords arrival`() {
        val leg = RouteDirectionsService.parseValhallaResponse(valhallaBody)!!
        assertEquals(3, leg.steps.size)

        // The opening "Bike north…" maneuver is skipped - the rider is already
        // riding - but its length still counts towards the next one.
        assertEquals("Turn right onto the cycleway", leg.steps[0].instruction)
        assertEquals(86.0, leg.steps[0].startCumulativeMetres, 1e-6)

        assertEquals("Make a sharp left onto the cycleway", leg.steps[1].instruction)
        assertEquals(116.0, leg.steps[1].startCumulativeMetres, 1e-6)

        // Valhalla's "You have arrived at your destination." is reworded to the
        // phrasing the rest of the app uses for reaching a stop.
        assertEquals("Arrive at the stop", leg.steps[2].instruction)
        assertEquals(148.0, leg.steps[2].startCumulativeMetres, 1e-6)
    }

    @Test
    fun `non-zero trip status returns null`() {
        val body = """{"trip":{"status":1,"status_message":"No route","legs":[]}}"""
        assertNull(RouteDirectionsService.parseValhallaResponse(body))
    }

    @Test
    fun `valhalla error body returns null`() {
        val body = """{"error_code":171,"error":"No suitable edges near location"}"""
        assertNull(RouteDirectionsService.parseValhallaResponse(body))
    }

    @Test
    fun `valhalla degenerate geometry returns null`() {
        val body = """
            {"trip":{"status":0,"summary":{"length":0.0},
             "legs":[{"shape":"yfj}wBkhsyR","maneuvers":[]}]}}
        """.trimIndent()
        assertNull(RouteDirectionsService.parseValhallaResponse(body))
    }

    @Test
    fun `truncated polyline decodes to the points it did carry`() {
        // A body cut mid-varint must not loop forever or throw; whatever
        // decoded so far is returned and the caller's size check takes over.
        val points = RouteDirectionsService.decodePolyline6("yfj}wBkhsyRcF")
        assertEquals(1, points.size)
    }

    @Test
    fun `bike requests Valhalla's bicycle costing with roads weighted away`() {
        val json = JSONObject(
            RouteDirectionsService.valhallaRequestJson(
                TravelProfile.BIKE, 63.43, 10.39, 63.44, 10.41
            )
        )
        assertEquals("bicycle", json.getString("costing"))
        val options = json.getJSONObject("costing_options").getJSONObject("bicycle")
        // This is the whole fix: OSRM's bike profile could not be told to stay
        // off roads shared with traffic, so bike mode came back down arterials.
        assertEquals(0.0, options.getDouble("use_roads"), 1e-9)
        assertEquals("Hybrid", options.getString("bicycle_type"))
    }

    @Test
    fun `foot takes Valhalla's pedestrian defaults`() {
        val json = JSONObject(
            RouteDirectionsService.valhallaRequestJson(
                TravelProfile.FOOT, 63.43, 10.39, 63.44, 10.41
            )
        )
        assertEquals("pedestrian", json.getString("costing"))
        // Measured: the defaults already keep a walking route off big roads
        // entirely, and every override tried on top of them was worse.
        assertFalse(json.has("costing_options"))
    }

    @Test
    fun `car takes Valhalla's auto costing`() {
        val json = JSONObject(
            RouteDirectionsService.valhallaRequestJson(
                TravelProfile.CAR, 63.43, 10.39, 63.44, 10.41
            )
        )
        assertEquals("auto", json.getString("costing"))
    }

    @Test
    fun `request carries both waypoints in order`() {
        val json = JSONObject(
            RouteDirectionsService.valhallaRequestJson(
                TravelProfile.BIKE, 63.43, 10.39, 63.44, 10.41
            )
        )
        val locations = json.getJSONArray("locations")
        assertEquals(2, locations.length())
        assertEquals(63.43, locations.getJSONObject(0).getDouble("lat"), 1e-9)
        assertEquals(10.39, locations.getJSONObject(0).getDouble("lon"), 1e-9)
        assertEquals(63.44, locations.getJSONObject(1).getDouble("lat"), 1e-9)
        assertEquals(10.41, locations.getJSONObject(1).getDouble("lon"), 1e-9)
        // The summary is read as kilometres, so it must be requested as such.
        assertEquals(
            "kilometers",
            json.getJSONObject("directions_options").getString("units")
        )
    }

    private val sampleBody = """
        {
          "code": "Ok",
          "routes": [
            {
              "distance": 1234.5,
              "geometry": {
                "type": "LineString",
                "coordinates": [[18.06, 59.33], [18.07, 59.34], [18.08, 59.35]]
              },
              "legs": [
                {
                  "steps": [
                    {
                      "distance": 500.0,
                      "name": "Storgatan",
                      "maneuver": { "type": "depart", "modifier": "straight" }
                    },
                    {
                      "distance": 600.0,
                      "name": "Lillgatan",
                      "maneuver": { "type": "turn", "modifier": "left" }
                    },
                    {
                      "distance": 0.0,
                      "name": "",
                      "maneuver": { "type": "arrive" }
                    }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses geometry as lat-lon pairs`() {
        val leg = RouteDirectionsService.parseOsrmResponse(sampleBody)
        assertNotNull(leg)
        assertEquals(3, leg!!.points.size)
        // GeoJSON is (lon, lat) - the parser must swap to (lat, lon).
        assertEquals(59.33, leg.points.first().first, 1e-9)
        assertEquals(18.06, leg.points.first().second, 1e-9)
        assertEquals(1234.5, leg.distanceMetres, 1e-9)
    }

    @Test
    fun `builds instructions with cumulative start distances and skips depart`() {
        val leg = RouteDirectionsService.parseOsrmResponse(sampleBody)!!
        // depart is skipped; turn + arrive remain.
        assertEquals(2, leg.steps.size)
        val turn = leg.steps[0]
        assertEquals("Turn left onto Lillgatan", turn.instruction)
        // The turn happens after the 500 m depart step.
        assertEquals(500.0, turn.startCumulativeMetres, 1e-9)
        val arrive = leg.steps[1]
        assertEquals("Arrive at the stop", arrive.instruction)
        assertEquals(1100.0, arrive.startCumulativeMetres, 1e-9)
    }

    @Test
    fun `non-Ok code returns null`() {
        assertNull(RouteDirectionsService.parseOsrmResponse("""{"code":"NoRoute","routes":[]}"""))
    }

    @Test
    fun `empty routes returns null`() {
        assertNull(RouteDirectionsService.parseOsrmResponse("""{"code":"Ok","routes":[]}"""))
    }

    @Test
    fun `degenerate geometry returns null`() {
        val body = """
            {"code":"Ok","routes":[{"distance":1.0,
              "geometry":{"type":"LineString","coordinates":[[18.0,59.0]]},"legs":[]}]}
        """.trimIndent()
        assertNull(RouteDirectionsService.parseOsrmResponse(body))
    }
}
