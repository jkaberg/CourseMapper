package com.coursemapper.domain.model

data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val altMetres: Double?,
    val accuracyMetres: Float?,
    val timestampMs: Long,
    val isSmoothed: Boolean = true
)

data class BaseRoute(
    val id: Long,
    val name: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isApproved: Boolean,
    /** Cumulative distance of the approved polyline in metres. */
    val distanceMetres: Double,
    /** "recorded" | "gpx_import" */
    val source: String,
    val rawPoints: List<RoutePoint> = emptyList(),
    val smoothedPoints: List<RoutePoint> = emptyList()
)

data class MarkerRule(
    val type: MarkerType,
    /** Only relevant for [MarkerType.DISTANCE]; interval between auto-placed markers. */
    val intervalMetres: Double = 1000.0,
    /** Only relevant for [MarkerType.CUSTOM_DISTANCES]; specific cumulative distances to mark. */
    val customDistancesMetres: List<Double> = emptyList()
)

enum class MarkerType {
    DISTANCE,
    START,
    FINISH,
    CHECKPOINT,
    WATER_STATION,
    CUSTOM_DISTANCES
}

data class MarkerPreset(
    val id: Long,
    val name: String,
    val rules: List<MarkerRule>,
    val createdAt: Long,
    val updatedAt: Long
)

/** A frozen copy of a [MarkerPreset] captured at course-approval time. */
data class MarkerPresetSnapshot(
    val sourcePresetId: Long?,
    val rules: List<MarkerRule>
)

data class CourseLap(
    val lapIndex: Int,
    /** Distance covered by this lap in metres. */
    val distanceMetres: Double,
    /** True for the final partial lap; false for all full laps. */
    val isPartial: Boolean
)

data class DistanceMarker(
    val id: Long,
    val courseId: Long,
    /** 1-based cumulative index across the entire composed course. */
    val sequenceIndex: Int,
    val lat: Double,
    val lon: Double,
    val cumulativeDistanceMetres: Double,
    val type: MarkerType,
    val label: String,
    val isManuallyMoved: Boolean,
    /** Optimistic-lock version; incremented on every edit. */
    val version: Int = 0
)

data class ComposedCourse(
    val id: Long,
    val baseRouteId: Long,
    val name: String,
    /** Organiser notes; copied from BaseRoute at creation and editable independently. */
    val notes: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val lapCount: Int,
    val finalLapDistanceMetres: Double,
    val totalDistanceMetres: Double,
    val presetSnapshot: MarkerPresetSnapshot,
    val laps: List<CourseLap> = emptyList(),
    val markers: List<DistanceMarker> = emptyList(),
    /** Set when this course was generated from an approved [RouteVariant]. */
    val variantId: Long? = null,
    /** True while the course has not yet been published (approved) by the organiser. */
    val isDraft: Boolean = false,
    /** The organiser's original composition intent, reconstructable on reload. */
    val buildSpec: CourseBuildSpec = CourseBuildSpec.Manual(lapCount, finalLapDistanceMetres),
    /** Where the organiser moved this course's start and finish. See [CourseGeometryEdit]. */
    val geometryEdit: CourseGeometryEdit = CourseGeometryEdit.NONE,
    /**
     * Measured course length in metres, 0.0 when not measured. Not the same as
     * [totalDistanceMetres], which is the drawn line.
     */
    val measuredLengthMetres: Double = 0.0
) {

    /** Polyline metres per course metre, derived so it can't go stale. 1.0 when unmeasured. */
    val distanceScale: Double
        get() = if (measuredLengthMetres > 0.0 && totalDistanceMetres > 0.0) {
            totalDistanceMetres / measuredLengthMetres
        } else {
            1.0
        }

    /** True when someone has measured this course on the ground. */
    val isMeasured: Boolean get() = measuredLengthMetres > 0.0

    /** Distance to show and export: measured if set, otherwise the drawn length. */
    val displayDistanceMetres: Double
        get() = if (isMeasured) measuredLengthMetres else totalDistanceMetres
}

data class PlacementStop(
    val id: Long,
    val courseId: Long,
    /** 0-based index in visit order. */
    val stopIndex: Int,
    val lat: Double,
    val lon: Double,
    /** Markers grouped at this physical stop. */
    val markers: List<DistanceMarker>,
    val isCompleted: Boolean
)

/** Named set of courses, eg one event's distances. No per-outing state. */
data class CourseGroup(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val courseIds: List<Long> = emptyList()
)

/** How a run's visit order was computed. */
enum class RunOrdering { SPINE, OPTIMIZED }

/** Leg routing mode. [BIKE] and [FOOT] may use paths a car can't. */
enum class TravelProfile { CAR, BIKE, FOOT }

enum class RunStopState { PENDING, DONE, SKIPPED }

/** One physical sign to place at a stop, snapshotted at run assembly. */
data class RunSign(
    /** Course (route) the sign belongs to, e.g. "Marathon". */
    val courseName: String,
    /** Sign label, e.g. "5 km". */
    val label: String
)

/** One stop of a run, possibly with signs from several courses. */
data class RunStop(
    val id: Long,
    val runId: Long,
    /** Visit-order key (ascending = visit order; not necessarily contiguous). */
    val stopIndex: Int,
    val lat: Double,
    val lon: Double,
    val signs: List<RunSign>,
    val state: RunStopState,
    /** When the stop last left PENDING; null while pending. */
    val stateChangedAt: Long? = null
)

/** One outing placing the markers of [courseIds]. Stops are snapshots. */
data class PlacementRun(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val ordering: RunOrdering,
    val spineCourseId: Long?,
    val startedAt: Long?,
    val completedAt: Long?,
    /** Straight-line length of the planned visit order, metres. */
    val totalPlannedMetres: Double,
    val travelProfile: TravelProfile = TravelProfile.CAR,
    /** Group this run was launched from, null for ad-hoc. */
    val groupId: Long? = null,
    val courseIds: List<Long> = emptyList(),
    val stops: List<RunStop> = emptyList()
)

/** Where segments connect in a network. */
data class RouteJunction(
    val id: Long,
    val networkId: Long,
    val lat: Double,
    val lon: Double,
    val label: String?
)

/**
 * Part of a [RouteNetwork] backed by one [BaseRoute]. [fromMetres]/[toMetres]
 * are where it leaves and rejoins the trunk, null uses the full polyline.
 */
data class RouteSegment(
    val id: Long,
    val networkId: Long,
    val baseRouteId: Long,
    val isMainTrunk: Boolean,
    val fromJunctionId: Long?,
    val toJunctionId: Long?,
    val orderIndex: Int,
    val distanceMetres: Double,
    /** Cumulative metres on this segment's polyline where the slice starts; null = use full polyline. */
    val fromMetres: Double? = null,
    /** Cumulative metres on this segment's polyline where the slice ends; null = use full polyline. */
    val toMetres: Double? = null
)

/** Segments sharing junctions, all distances of an event compile from one network. */
data class RouteNetwork(
    val id: Long,
    val name: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val segments: List<RouteSegment> = emptyList(),
    val junctions: List<RouteJunction> = emptyList()
)

/** A distance compiled from [RouteSegment]s. */
data class RouteVariant(
    val id: Long,
    val networkId: Long,
    val name: String,
    val segmentIds: List<Long>,
    /** Compiled flat polyline; null until [compileAndSaveVariant] is called. */
    val compiledPolyline: List<RoutePoint>?,
    val totalDistanceMetres: Double?,
    val isApproved: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Course status for lists and badges.
 *
 * - [DRAFT] not published
 * - [MARKERLESS] published without markers
 * - [PUBLISHED] has markers, no offline map
 * - [NEEDS_FIELD_PREP] offline coverage incomplete
 * - [OFFLINE_READY] ready for the field
 */
enum class CourseStatus { DRAFT, MARKERLESS, PUBLISHED, NEEDS_FIELD_PREP, OFFLINE_READY }

val ComposedCourse.status: CourseStatus
    get() = when {
        isDraft           -> CourseStatus.DRAFT
        markers.isEmpty() -> CourseStatus.MARKERLESS
        else              -> CourseStatus.PUBLISHED
    }

/**
 * Higher is worse. A group reports its worst member, one course not ready means
 * the pass can't be finished.
 */
val CourseStatus.fieldSeverity: Int
    get() = when (this) {
        CourseStatus.OFFLINE_READY    -> 0
        CourseStatus.PUBLISHED        -> 1   // tiles in flight
        CourseStatus.NEEDS_FIELD_PREP -> 2   // published, no offline coverage
        CourseStatus.DRAFT            -> 3   // never published
        CourseStatus.MARKERLESS       -> 4   // nothing to place
    }

/** Completion event on the navigation undo stack. */
sealed class UndoEvent {
    abstract val timestamp: Long

    /** A grouped placement stop was auto-completed when the rider passed it. */
    data class StopAutoCompleted(
        val stopIndex: Int,
        val stopLabel: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UndoEvent()

    /** A grouped placement stop was manually confirmed by the organiser. */
    data class StopManuallyCompleted(
        val stopIndex: Int,
        val stopLabel: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UndoEvent()

    /** An individual marker was auto-completed when the rider passed it. */
    data class MarkerAutoCompleted(
        val markerSequenceIndex: Int,
        val markerLabel: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UndoEvent()

    /** An individual marker was manually confirmed by the organiser. */
    data class MarkerManuallyCompleted(
        val markerSequenceIndex: Int,
        val markerLabel: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : UndoEvent()
}
