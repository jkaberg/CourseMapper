package com.coursemapper.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coursemapper.domain.model.OfflineFailureReason
import com.coursemapper.domain.model.OfflinePackChoice
import com.coursemapper.domain.model.OfflinePackStatus

/**
 * Named set of marker rules. Approved courses snapshot the rules
 * ([ComposedCourseEntity.presetSnapshotJson]) so editing a preset doesn't move them.
 */
@Entity(tableName = "marker_presets")
data class MarkerPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    /**
     * JSON array of marker rules serialised from the domain model.
     * Example: [{"type":"distance","intervalMetres":1000},{"type":"start"},{"type":"finish"}]
     */
    val rulesJson: String = "[]"
)

/**
 * A [BaseRouteEntity] repeated for [lapCount] laps plus an optional partial
 * [finalLapDistanceMetres]. [variantId] is set when compiled from a route variant.
 *
 * The build spec columns keep what the organiser asked for, so a reload can
 * recompose the course and not just show the result.
 */
@Entity(
    tableName = "composed_courses",
    foreignKeys = [ForeignKey(
        entity = BaseRouteEntity::class,
        parentColumns = ["id"],
        childColumns = ["baseRouteId"],
        onDelete = ForeignKey.RESTRICT
    )],
    indices = [Index("baseRouteId")]
)
data class ComposedCourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val baseRouteId: Long,
    val name: String,
    /** Organiser notes; editable in the Course Workspace. */
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val lapCount: Int = 1,
    /** 0.0 means no partial final lap. */
    val finalLapDistanceMetres: Double = 0.0,
    /** Total distance of the composed course in metres. */
    val totalDistanceMetres: Double = 0.0,
    /** Snapshot of the preset rules at the time of approval. */
    val presetSnapshotJson: String = "[]",
    /** ID of the live preset at time of creation (for reference only). */
    val presetId: Long? = null,
    /** Non-null when generated from an approved [RouteVariantEntity]. */
    val variantId: Long? = null,
    /** True while the course has not yet been published by the organiser. */
    val isDraft: Boolean = false,

    /** "fixed_laps", "target_distance" or "manual" (rows from before v6). */
    val buildSpecKind: String = "manual",
    /** Explicit lap count, or the minimum for [TargetDistance]. */
    val buildSpecLapCount: Int = 1,
    /** Target total for [TargetDistance], final partial lap for [Manual], 0.0 for [FixedLaps]. */
    val buildSpecTargetMetres: Double = 0.0,

    /**
     * Start/finish adjustment as a serialised
     * [com.coursemapper.domain.model.CourseGeometryEdit], empty for none. The base
     * route is never rewritten, several courses can share one recording.
     */
    val geometryEditJson: String = "",

    /**
     * Measured course length in metres, 0.0 if not measured.
     *
     * A certified distance is measured on the shortest legal line with a
     * calibrated bike, a polyline along road centrelines is always longer. When
     * set, marker distances are in course metres:
     *
     *     polylineMetres = courseMetres × (totalDistanceMetres / measuredLengthMetres)
     *
     * The ratio is derived, never stored (see
     * [com.coursemapper.domain.model.ComposedCourse.distanceScale]).
     * [DistanceMarkerEntity.cumulativeDistanceMetres] is always in course metres.
     */
    val measuredLengthMetres: Double = 0.0
)

/**
 * An auto-placed or manually adjusted distance marker on a [ComposedCourseEntity].
 */
@Entity(
    tableName = "distance_markers",
    foreignKeys = [ForeignKey(
        entity = ComposedCourseEntity::class,
        parentColumns = ["id"],
        childColumns = ["courseId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("courseId")]
)
data class DistanceMarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    /** Global sequential index across the entire composed course (1-based). */
    val sequenceIndex: Int,
    val latDeg: Double,
    val lonDeg: Double,
    /** Cumulative course distance at this marker in metres. */
    val cumulativeDistanceMetres: Double,
    /** Type: "distance" | "start" | "finish" | "checkpoint" | "water_station" */
    val type: String,
    val label: String = "",
    val isManuallyMoved: Boolean = false,
    val isDeleted: Boolean = false,
    /** Optimistic lock version, bumped on every edit. */
    val version: Int = 0
)

/** Field stop: markers close enough together to be placed at one spot. */
@Entity(
    tableName = "placement_stops",
    foreignKeys = [ForeignKey(
        entity = ComposedCourseEntity::class,
        parentColumns = ["id"],
        childColumns = ["courseId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("courseId")]
)
data class PlacementStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val stopIndex: Int,
    val latDeg: Double,
    val lonDeg: Double,
    /** JSON array of [DistanceMarkerEntity] ids grouped at this stop. */
    val markerIdsJson: String = "[]",
    val isCompleted: Boolean = false
)

/** Named set of courses, eg one event's marathon, half, 10K and 5K. */
@Entity(tableName = "course_groups")
data class CourseGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

/** Membership row linking a group to one of its courses. */
@Entity(
    tableName = "course_group_members",
    foreignKeys = [
        ForeignKey(
            entity = CourseGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ComposedCourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("groupId"), Index("courseId")]
)
data class CourseGroupMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val courseId: Long
)

/**
 * One field outing placing the markers of one or more courses. Stops and sign
 * labels are snapshotted so editing a course can't break a run in progress.
 */
@Entity(tableName = "placement_runs")
data class PlacementRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** "spine" | "optimized" - how the visit order was computed. */
    val orderingMode: String = "spine",
    /** Course whose geometry anchors spine ordering; null = first course. */
    val spineCourseId: Long? = null,
    /** Set when navigation first opens the run. */
    val startedAt: Long? = null,
    /** Set when the organiser finishes the run. */
    val completedAt: Long? = null,
    /** Straight-line length of the planned visit order, for display. */
    val totalPlannedMetres: Double = 0.0,
    /** "car", "bike" or "foot". Bike and foot may use paths. */
    val travelProfile: String = "car",
    /**
     * Group the run was launched from, null for ad-hoc. Only used for "same as
     * last time", the run still owns its courses, order and profile.
     */
    val groupId: Long? = null,
    val isDeleted: Boolean = false
)

/** Membership row linking a run to one of its source courses. */
@Entity(
    tableName = "placement_run_courses",
    foreignKeys = [
        ForeignKey(
            entity = PlacementRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ComposedCourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("runId"), Index("courseId")]
)
data class PlacementRunCourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val courseId: Long
)

/**
 * One stop of a run, possibly with signs from several courses. [signsJson] is
 * a snapshot, editing a course regenerates marker ids and must not break a run.
 */
@Entity(
    tableName = "placement_run_stops",
    foreignKeys = [ForeignKey(
        entity = PlacementRunEntity::class,
        parentColumns = ["id"],
        childColumns = ["runId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("runId")]
)
data class PlacementRunStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    /** 0-based visit order key (contiguity not guaranteed after re-planning). */
    val stopIndex: Int,
    val latDeg: Double,
    val lonDeg: Double,
    /** JSON array of {"course": name, "label": signLabel} objects. */
    val signsJson: String = "[]",
    /** "pending" | "done" | "skipped" */
    val state: String = "pending",
    /** When [state] last left "pending"; null while pending. */
    val stateChangedAt: Long? = null
)

/**
 * One downloadable map area, as shown in Map Storage. Deleted with the last
 * course it covers.
 *
 * Progress isn't written here per tile - that made every observer recompute
 * thousands of times. Live progress is in
 * [com.coursemapper.offline.OfflineDownloadRegistry], Room only gets state
 * changes and a throttled checkpoint.
 */
@Entity(tableName = "offline_packs")
data class OfflinePackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Human-readable name, derived from the courses it covers. */
    val label: String,
    /** JSON: {"south":..,"north":..,"west":..,"east":..} - see `GeoBounds`. */
    val boundsJson: String,
    val status: OfflinePackStatus = OfflinePackStatus.QUEUED,
    /** Set only while [status] is FAILED. */
    val failureReason: OfflineFailureReason? = null,
    /** Diagnostic detail behind [failureReason]; never the sole explanation shown. */
    val failureMessage: String? = null,
    val userChoice: OfflinePackChoice = OfflinePackChoice.UNSET,
    /** What the size estimator predicted, and what the prompt showed the user. */
    val estimatedBytes: Long = 0,
    /** What is actually on disk once downloaded. */
    val sizeBytes: Long = 0,
    /** Throttled checkpoint of MapLibre's counters, so a resume can show progress. */
    val completedResourceCount: Long = 0,
    val requiredResourceCount: Long = 0,
    val minZoom: Double = 5.0,
    val maxZoom: Double = 16.0,
    val createdAt: Long = System.currentTimeMillis(),
    val downloadedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * A pack's MapLibre region for one basemap style. Both light and dark are
 * downloaded since the app follows the system theme, and a style that failed to
 * load renders as a blank map. Tiles are shared so the second style is cheap.
 */
@Entity(
    tableName = "offline_pack_regions",
    foreignKeys = [ForeignKey(
        entity = OfflinePackEntity::class,
        parentColumns = ["id"],
        childColumns = ["packId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("packId")]
)
data class OfflinePackRegionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packId: Long,
    /** [com.coursemapper.domain.model.MapStyleVariant.key] - "light" or "dark". */
    val styleVariant: String,
    /** MapLibre's own region id; null until the region has been created. */
    val maplibreRegionId: Long? = null,
    val status: OfflinePackStatus = OfflinePackStatus.QUEUED,
    val sizeBytes: Long = 0
)

/**
 * Courses covered by a pack. Courses are soft-deleted, so anything asking if a
 * pack is still needed must join on `isDeleted = 0`.
 */
@Entity(
    tableName = "offline_pack_courses",
    primaryKeys = ["packId", "courseId"],
    foreignKeys = [ForeignKey(
        entity = OfflinePackEntity::class,
        parentColumns = ["id"],
        childColumns = ["packId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("packId"), Index("courseId")]
)
data class OfflinePackCourseEntity(
    val packId: Long,
    val courseId: Long
)

/** Segments sharing one polyline graph, an event's distances are compiled from it. */
@Entity(tableName = "route_networks")
data class RouteNetworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

/** Split or rejoin point on the trunk where a branch attaches. */
@Entity(
    tableName = "route_junctions",
    foreignKeys = [ForeignKey(
        entity = RouteNetworkEntity::class,
        parentColumns = ["id"],
        childColumns = ["networkId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("networkId")]
)
data class RouteJunctionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val latDeg: Double,
    val lonDeg: Double,
    val label: String? = null
)

/** Part of a [RouteNetworkEntity] backed by one route. [isMainTrunk] for the primary route. */
@Entity(
    tableName = "route_segments",
    foreignKeys = [
        ForeignKey(
            entity = RouteNetworkEntity::class,
            parentColumns = ["id"],
            childColumns = ["networkId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BaseRouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["baseRouteId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("networkId"), Index("baseRouteId")]
)
data class RouteSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val baseRouteId: Long,
    val isMainTrunk: Boolean = true,
    val fromJunctionId: Long? = null,
    val toJunctionId: Long? = null,
    val orderIndex: Int = 0,
    val distanceMetres: Double = 0.0,
    val isDeleted: Boolean = false,
    /** Cumulative metres on this segment’s polyline where the slice starts; null = full polyline. */
    val fromMetres: Double? = null,
    /** Cumulative metres on this segment’s polyline where the slice ends; null = full polyline. */
    val toMetres: Double? = null
)

/** A distance compiled from segments, approving it creates a [ComposedCourseEntity]. */
@Entity(
    tableName = "route_variants",
    foreignKeys = [ForeignKey(
        entity = RouteNetworkEntity::class,
        parentColumns = ["id"],
        childColumns = ["networkId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("networkId")]
)
data class RouteVariantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val networkId: Long,
    val name: String,
    /** JSON array of [RouteSegmentEntity] ids in traversal order. */
    val segmentIdsJson: String = "[]",
    /** Compiled flat polyline stored as JSON; null until compilation is run. */
    val compiledPolylineJson: String? = null,
    val totalDistanceMetres: Double? = null,
    val isApproved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)
