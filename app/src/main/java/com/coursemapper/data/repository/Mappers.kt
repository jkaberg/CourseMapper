package com.coursemapper.data.repository

import com.coursemapper.data.db.*
import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

fun List<MarkerRule>.toJson(): String {
    val arr = JSONArray()
    for (rule in this) {
        val obj = JSONObject().put("type", rule.type.name.lowercase())
        when (rule.type) {
            MarkerType.DISTANCE -> obj.put("intervalMetres", rule.intervalMetres)
            MarkerType.CUSTOM_DISTANCES -> {
                val distArr = JSONArray()
                rule.customDistancesMetres.forEach { distArr.put(it) }
                obj.put("customDistances", distArr)
            }
            else -> {}
        }
        arr.put(obj)
    }
    return arr.toString()
}

fun String.toMarkerRules(): List<MarkerRule> {
    val arr = JSONArray(this)
    return (0 until arr.length()).mapNotNull { i ->
        val obj = arr.getJSONObject(i)
        val type = when (obj.optString("type")) {
            "distance"         -> MarkerType.DISTANCE
            "start"            -> MarkerType.START
            "finish"           -> MarkerType.FINISH
            "checkpoint"       -> MarkerType.CHECKPOINT
            "water_station"    -> MarkerType.WATER_STATION
            "custom_distances" -> MarkerType.CUSTOM_DISTANCES
            else               -> return@mapNotNull null
        }
        val customDistances = if (type == MarkerType.CUSTOM_DISTANCES) {
            val distArr = obj.optJSONArray("customDistances")
            if (distArr != null) (0 until distArr.length()).map { distArr.getDouble(it) }
            else emptyList()
        } else emptyList()
        MarkerRule(
            type                  = type,
            intervalMetres        = obj.optDouble("intervalMetres", 1000.0),
            customDistancesMetres = customDistances
        )
    }
}

fun RoutePointEntity.toDomain() = RoutePoint(
    lat            = latDeg,
    lon            = lonDeg,
    altMetres      = altMetres,
    accuracyMetres = accuracyMetres,
    timestampMs    = timestampMs,
    isSmoothed     = isSmoothed
)

/**
 * [rawLat]/[rawLon] is the observation when the stored point has been
 * smoothed, leave them out for fresh fixes.
 */
fun RoutePoint.toEntity(
    routeId: Long,
    index: Int,
    rawLat: Double? = null,
    rawLon: Double? = null
) = RoutePointEntity(
    routeId        = routeId,
    index          = index,
    latDeg         = lat,
    lonDeg         = lon,
    rawLatDeg      = rawLat,
    rawLonDeg      = rawLon,
    altMetres      = altMetres,
    accuracyMetres = accuracyMetres,
    timestampMs    = timestampMs,
    isSmoothed     = isSmoothed
)

fun BaseRouteEntity.toDomain(
    rawPoints: List<RoutePointEntity> = emptyList()
) = BaseRoute(
    id             = id,
    name           = name,
    notes          = notes,
    createdAt      = createdAt,
    updatedAt      = updatedAt,
    isApproved     = isApproved,
    distanceMetres = distanceMetres,
    source         = source,
    rawPoints      = rawPoints.map { it.toDomain() },
    smoothedPoints = rawPoints.filter { it.isSmoothed }.map { it.toDomain() }
)

fun MarkerPresetEntity.toDomain() = MarkerPreset(
    id        = id,
    name      = name,
    rules     = rulesJson.toMarkerRules(),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun DistanceMarkerEntity.toDomain() = DistanceMarker(
    id                       = id,
    courseId                 = courseId,
    sequenceIndex            = sequenceIndex,
    lat                      = latDeg,
    lon                      = lonDeg,
    cumulativeDistanceMetres = cumulativeDistanceMetres,
    type                     = when (type) {
        "start"            -> MarkerType.START
        "finish"           -> MarkerType.FINISH
        "checkpoint"       -> MarkerType.CHECKPOINT
        "water_station"    -> MarkerType.WATER_STATION
        "custom_distances" -> MarkerType.CUSTOM_DISTANCES
        else               -> MarkerType.DISTANCE
    },
    label           = label,
    isManuallyMoved = isManuallyMoved,
    version         = version
)

fun DistanceMarker.toEntity(courseId: Long) = DistanceMarkerEntity(
    id                       = id,
    courseId                 = courseId,
    sequenceIndex            = sequenceIndex,
    latDeg                   = lat,
    lonDeg                   = lon,
    cumulativeDistanceMetres = cumulativeDistanceMetres,
    type                     = type.name.lowercase(),
    label                    = label,
    isManuallyMoved          = isManuallyMoved,
    isDeleted                = false,
    version                  = version
)

fun ComposedCourseEntity.toDomain(
    markers: List<DistanceMarkerEntity> = emptyList()
) = ComposedCourse(
    id                      = id,
    baseRouteId             = baseRouteId,
    name                    = name,
    notes                   = notes,
    createdAt               = createdAt,
    updatedAt               = updatedAt,
    lapCount                = lapCount,
    finalLapDistanceMetres  = finalLapDistanceMetres,
    totalDistanceMetres     = totalDistanceMetres,
    presetSnapshot          = MarkerPresetSnapshot(
        sourcePresetId = presetId,
        rules          = presetSnapshotJson.toMarkerRules()
    ),
    markers   = markers.filter { !it.isDeleted }.map { it.toDomain() },
    variantId = variantId,
    isDraft   = isDraft,
    buildSpec = CourseBuildSpec.fromDb(buildSpecKind, buildSpecLapCount, buildSpecTargetMetres),
    geometryEdit = CourseGeometryEdit.fromJson(geometryEditJson),
    measuredLengthMetres = measuredLengthMetres
)

fun CourseGroupEntity.toDomain(courseIds: List<Long> = emptyList()) = CourseGroup(
    id        = id,
    name      = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    courseIds = courseIds
)

fun PlacementRunEntity.toDomain(
    courseIds: List<Long> = emptyList(),
    stops: List<RunStop> = emptyList()
) = PlacementRun(
    id                 = id,
    name               = name,
    createdAt          = createdAt,
    updatedAt          = updatedAt,
    ordering           = if (orderingMode == "optimized") RunOrdering.OPTIMIZED else RunOrdering.SPINE,
    spineCourseId      = spineCourseId,
    startedAt          = startedAt,
    completedAt        = completedAt,
    totalPlannedMetres = totalPlannedMetres,
    travelProfile      = travelProfile.toTravelProfile(),
    groupId            = groupId,
    courseIds          = courseIds,
    stops              = stops
)

fun RunOrdering.toDb(): String = if (this == RunOrdering.OPTIMIZED) "optimized" else "spine"

/** The stored `travelProfile` column; anything unrecognised reads as car. */
fun String.toTravelProfile(): TravelProfile = when (this) {
    "bike" -> TravelProfile.BIKE
    "foot" -> TravelProfile.FOOT
    else   -> TravelProfile.CAR
}

fun TravelProfile.toDb(): String = when (this) {
    TravelProfile.CAR  -> "car"
    TravelProfile.BIKE -> "bike"
    TravelProfile.FOOT -> "foot"
}

fun PlacementRunStopEntity.toDomain() = RunStop(
    id             = id,
    runId          = runId,
    stopIndex      = stopIndex,
    lat            = latDeg,
    lon            = lonDeg,
    signs          = signsJson.toRunSigns(),
    state          = when (state) {
        "done"    -> RunStopState.DONE
        "skipped" -> RunStopState.SKIPPED
        else      -> RunStopState.PENDING
    },
    stateChangedAt = stateChangedAt
)

fun RunStopState.toDb(): String = when (this) {
    RunStopState.DONE    -> "done"
    RunStopState.SKIPPED -> "skipped"
    RunStopState.PENDING -> "pending"
}

fun RunStop.toEntity(): PlacementRunStopEntity = PlacementRunStopEntity(
    id             = id,
    runId          = runId,
    stopIndex      = stopIndex,
    latDeg         = lat,
    lonDeg         = lon,
    signsJson      = signs.toSignsJson(),
    state          = state.toDb(),
    stateChangedAt = stateChangedAt
)

/** Serialize run signs to a compact JSON array. */
fun List<RunSign>.toSignsJson(): String {
    val arr = JSONArray()
    for (sign in this) {
        arr.put(JSONObject().put("course", sign.courseName).put("label", sign.label))
    }
    return arr.toString()
}

/** Deserialize run signs; malformed input yields an empty list. */
fun String.toRunSigns(): List<RunSign> = try {
    val arr = JSONArray(this)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        RunSign(courseName = o.optString("course"), label = o.optString("label"))
    }
} catch (_: Exception) {
    emptyList()
}

fun PlacementStopEntity.toDomain(markers: List<DistanceMarker>): PlacementStop {
    return PlacementStop(
        id          = id,
        courseId    = courseId,
        stopIndex   = stopIndex,
        lat         = latDeg,
        lon         = lonDeg,
        markers     = markers,
        isCompleted = isCompleted
    )
}

fun PlacementStop.toEntity(): PlacementStopEntity {
    val arr = org.json.JSONArray()
    markers.forEach { arr.put(it.id) }
    return PlacementStopEntity(
        id           = id,
        courseId     = courseId,
        stopIndex    = stopIndex,
        latDeg       = lat,
        lonDeg       = lon,
        markerIdsJson = arr.toString(),
        isCompleted  = isCompleted
    )
}

/** Serialize a list of Long IDs to a JSON array string. */
fun List<Long>.toIdsJson(): String {
    val arr = JSONArray()
    for (id in this) arr.put(id)
    return arr.toString()
}

/** Deserialize a JSON array string to a list of Long IDs. */
fun String.toLongIds(): List<Long> {
    val arr = JSONArray(this)
    return (0 until arr.length()).map { arr.getLong(it) }
}

/** Serialize a list of [RoutePoint]s to compact JSON. */
fun List<com.coursemapper.domain.model.RoutePoint>.toPolylineJson(): String {
    val arr = JSONArray()
    for (p in this) {
        val obj = JSONObject()
            .put("la", p.lat)
            .put("lo", p.lon)
            .put("ts", p.timestampMs)
            .put("sm", if (p.isSmoothed) 1 else 0)
        p.altMetres?.let { obj.put("al", it) }
        p.accuracyMetres?.let { obj.put("ac", it.toDouble()) }
        arr.put(obj)
    }
    return arr.toString()
}

/** Deserialize a compact JSON string to a list of [RoutePoint]s. */
fun String.toRoutePoints(): List<com.coursemapper.domain.model.RoutePoint> {
    val arr = JSONArray(this)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        com.coursemapper.domain.model.RoutePoint(
            lat            = o.getDouble("la"),
            lon            = o.getDouble("lo"),
            altMetres      = if (o.has("al")) o.getDouble("al") else null,
            accuracyMetres = if (o.has("ac")) o.getDouble("ac").toFloat() else null,
            timestampMs    = o.getLong("ts"),
            isSmoothed     = o.optInt("sm", 1) == 1
        )
    }
}

fun com.coursemapper.data.db.RouteNetworkEntity.toDomain(
    segments: List<com.coursemapper.data.db.RouteSegmentEntity> = emptyList(),
    junctions: List<com.coursemapper.data.db.RouteJunctionEntity> = emptyList()
) = com.coursemapper.domain.model.RouteNetwork(
    id        = id,
    name      = name,
    notes     = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    segments  = segments.map { it.toDomain() },
    junctions = junctions.map { it.toDomain() }
)

fun com.coursemapper.data.db.RouteSegmentEntity.toDomain() =
    com.coursemapper.domain.model.RouteSegment(
        id             = id,
        networkId      = networkId,
        baseRouteId    = baseRouteId,
        isMainTrunk    = isMainTrunk,
        fromJunctionId = fromJunctionId,
        toJunctionId   = toJunctionId,
        orderIndex     = orderIndex,
        distanceMetres = distanceMetres,
        fromMetres     = fromMetres,
        toMetres       = toMetres
    )

fun com.coursemapper.data.db.RouteJunctionEntity.toDomain() =
    com.coursemapper.domain.model.RouteJunction(
        id        = id,
        networkId = networkId,
        lat       = latDeg,
        lon       = lonDeg,
        label     = label
    )

fun com.coursemapper.domain.model.RouteJunction.toEntity() =
    com.coursemapper.data.db.RouteJunctionEntity(
        id        = id,
        networkId = networkId,
        latDeg    = lat,
        lonDeg    = lon,
        label     = label
    )

fun com.coursemapper.data.db.RouteVariantEntity.toDomain() =
    com.coursemapper.domain.model.RouteVariant(
        id                   = id,
        networkId            = networkId,
        name                 = name,
        segmentIds           = segmentIdsJson.toLongIds(),
        compiledPolyline     = compiledPolylineJson?.toRoutePoints(),
        totalDistanceMetres  = totalDistanceMetres,
        isApproved           = isApproved,
        createdAt            = createdAt,
        updatedAt            = updatedAt
    )
