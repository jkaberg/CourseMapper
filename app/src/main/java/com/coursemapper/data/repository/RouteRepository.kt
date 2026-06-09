package com.coursemapper.data.repository

import android.util.Log
import com.coursemapper.data.db.*
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.domain.CourseCompositionEngine
import com.coursemapper.domain.CourseDisplayPlanner
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.MarkerPlacementEngine
import com.coursemapper.domain.NavigationSequenceEngine
import com.coursemapper.domain.OfflinePackPlanner
import com.coursemapper.domain.OfflinePromptPolicy
import com.coursemapper.domain.PlacementPlanner
import com.coursemapper.domain.RouteSmoothingEngine
import com.coursemapper.domain.VariantCompilationEngine
import com.coursemapper.domain.VisitOrderPlanner
import com.coursemapper.domain.model.*
import com.coursemapper.gpx.GpxExporter
import com.coursemapper.gpx.GpxImporter
import com.coursemapper.map.MapPalette
import com.coursemapper.map.RibbonLod
import com.coursemapper.domain.model.OfflinePackChoice
import com.coursemapper.offline.OfflineMapRepository
import com.coursemapper.offline.PackQuote
import com.coursemapper.sharing.RouteShareManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes, courses, presets, groups and placement runs. ViewModels go through
 * this, DAOs aren't used outside it.
 */
@Singleton
class RouteRepository @Inject constructor(
    private val routeDao: RouteDao,
    private val courseDao: CourseDao,
    private val presetDao: MarkerPresetDao,
    private val runDao: PlacementRunDao,
    private val groupDao: CourseGroupDao,
    private val networkDao: RouteNetworkDao,
    private val variantDao: RouteVariantDao,
    private val smoother: RouteSmoothingEngine,
    private val distanceCalc: CumulativeDistanceCalculator,
    private val packPlanner: OfflinePackPlanner,
    private val promptPolicy: OfflinePromptPolicy,
    private val offlineMapRepository: OfflineMapRepository,
    private val compilationEngine: VariantCompilationEngine,
    private val compositionEngine: CourseCompositionEngine,
    private val placementEngine: MarkerPlacementEngine,
    private val placementPlanner: PlacementPlanner,
    private val visitOrderPlanner: VisitOrderPlanner,
    private val courseAxisBuilder: com.coursemapper.domain.CourseAxisBuilder,
    private val directionalSequencer: com.coursemapper.domain.DirectionalSequencer,
    private val displayPlanner: CourseDisplayPlanner,
    private val geometryEditor: com.coursemapper.domain.CourseGeometryEditor,
    private val userPreferences: UserPreferencesRepository,
    private val gpxImporter: GpxImporter,
    private val gpxExporter: GpxExporter,
    private val shareManager: RouteShareManager
) {

    companion object {
        /** [BaseRouteEntity.source] for a route captured by the recorder. */
        const val SOURCE_RECORDED = "recorded"

        /** [BaseRouteEntity.source] for a route parsed from a GPX file. */
        const val SOURCE_GPX_IMPORT = "gpx_import"
    }

    /**
     * The navigable polyline for [courseId]: the compiled variant for
     * variant-backed courses, otherwise the base route composed from the build
     * spec. Use this everywhere course geometry is needed.
     */
    suspend fun getCourseGeometry(courseId: Long): List<com.coursemapper.domain.model.RoutePoint> {
        val course = getCourse(courseId) ?: return emptyList()
        if (course.variantId != null) {
            val compiled = getVariant(course.variantId)?.compiledPolyline
            if (!compiled.isNullOrEmpty()) return geometryEditor.apply(compiled, course.geometryEdit)
        }
        val route = getRoute(course.baseRouteId) ?: return emptyList()
        // geometry edit goes before composition so laps repeat the kept stretch
        // and distances start at the real start line
        val basePoints = geometryEditor.apply(
            route.smoothedPoints.ifEmpty { route.rawPoints },
            course.geometryEdit
        )
        // Compose from the persisted build spec so the partial final lap is
        // included - recomposing from lapCount alone would drop it.
        return compositionEngine.compose(basePoints, course.buildSpec)
            .composedPoints.ifEmpty { basePoints }
    }

    /**
     * Geometry, colour and shared corridors for several courses on one map.
     * Colours are dealt by position in [courseIds] so a course keeps its colour
     * across screens. Courses that fail to load are skipped.
     */
    suspend fun getCourseDisplayLines(
        courseIds: List<Long>,
        unifyShared: Boolean = true
    ): CourseDisplay {
        data class Loaded(val id: Long, val name: String, val points: List<RoutePoint>)

        val loaded = courseIds.mapNotNull { id ->
            val course = getCourse(id) ?: return@mapNotNull null
            val geometry = getCourseGeometry(id)
            if (geometry.isEmpty()) null else Loaded(id, course.name, geometry)
        }
        if (loaded.isEmpty()) return CourseDisplay(emptyList(), emptyList(), emptyList())

        val indexed = loaded.mapNotNull { displayPlanner.index(it.id, it.points) }
        val tolerance = userPreferences.clusteringThresholdMetres.first()
        val plan = displayPlanner.plan(
            indexed,
            toleranceMetres = tolerance,
            chunkMetres = RibbonLod.CHUNK_METRES,
            unifyShared = unifyShared
        )
        val plannedById = plan.coursePaths.associateBy { it.courseId }

        return CourseDisplay(
            lines = loaded.mapIndexed { idx, course ->
                val planned = plannedById[course.id]
                CourseDisplayLine(
                    courseId = course.id,
                    courseName = course.name,
                    colorHex = MapPalette.COURSE_CYCLE[idx % MapPalette.COURSE_CYCLE.size],
                    // A course the planner could not index (too few distinct
                    // points) still has to appear, so fall back to its raw line.
                    soloPaths = planned?.soloPaths ?: listOf(course.points.map { it.lat to it.lon }),
                    ribbonPaths = planned?.ribbonPaths.orEmpty(),
                    coarseRibbonBands = planned?.coarseRibbonBands.orEmpty()
                )
            },
            sharedCorridors = plan.sharedCorridorPaths,
            indices = indexed
        )
    }

    /** Everything a map needs to draw one or more courses.  See [getCourseDisplayLines]. */
    data class CourseDisplay(
        val lines: List<CourseDisplayLine>,
        /** Stretches shared by more than one course, with the courses on them. */
        val sharedCorridors: List<CourseDisplayPlanner.SharedCorridor>,
        /**
         * Indexed geometry behind [lines], handed back since navigation needs it
         * on every fix to snap the rider and indexing a marathon isn't free.
         */
        val indices: List<com.coursemapper.domain.CorridorAnalyzer.IndexedCourse> = emptyList()
    )

    /** One course's drawable geometry and its colour. */
    data class CourseDisplayLine(
        val courseId: Long,
        val courseName: String,
        val colorHex: String,
        /** Stretches this course runs alone. */
        val soloPaths: List<List<Pair<Double, Double>>>,
        /** This course's share of the ribbons it shares with other courses. */
        val ribbonPaths: List<List<Pair<Double, Double>>>,
        /** The ribbons cut at each coarser chunk length, see [com.coursemapper.map.RibbonLod]. */
        val coarseRibbonBands: List<List<List<Pair<Double, Double>>>> = emptyList()
    ) {
        val paths: List<List<Pair<Double, Double>>> get() = soloPaths + ribbonPaths
    }

    /** An offline download about to be offered: area, name and size. */
    data class OfflinePackProposal(
        val courseIds: List<Long>,
        val courseNames: List<String>,
        val label: String,
        val quote: PackQuote
    )

    /** Plan the area and size of a pack for [courseIds] without downloading anything. */
    suspend fun quoteOfflinePack(courseIds: List<Long>): OfflinePackProposal? {
        if (courseIds.isEmpty()) return null
        val geometry = courseIds.map { getCourseGeometry(it) }
        val plan = packPlanner.planForCourses(geometry)
        if (plan !is OfflinePackPlanner.PlanResult.Planned) return null

        val names = courseIds.mapNotNull { getCourse(it)?.name }
        return OfflinePackProposal(
            courseIds = courseIds,
            courseNames = names,
            label = names.joinToString(" · ").ifBlank { "Offline map" },
            quote = offlineMapRepository.quote(plan.bounds)
        )
    }

    /** Create the pack and start downloading. */
    suspend fun startOfflineDownload(proposal: OfflinePackProposal): Long =
        offlineMapRepository.createPack(
            label = proposal.label,
            bounds = proposal.quote.bounds,
            courseIds = proposal.courseIds,
            choice = OfflinePackChoice.ACCEPTED,
            startNow = true
        )

    /**
     * Store a declined pack rather than forgetting it, so the same venue isn't
     * offered again. Still startable from the gate and Map Storage.
     */
    suspend fun declineOfflineDownload(proposal: OfflinePackProposal) {
        offlineMapRepository.createPack(
            label = proposal.label,
            bounds = proposal.quote.bounds,
            courseIds = proposal.courseIds,
            choice = OfflinePackChoice.DECLINED,
            startNow = false
        )
    }

    /** Offline readiness for a set of courses - see [OfflineMapRepository.observeCoverage]. */
    fun observeOfflineCoverage(courseIds: List<Long>) =
        offlineMapRepository.observeCoverage(courseIds)

    /**
     * Pack proposal for [courseIds] if the policy says to ask. Null when covered,
     * declined, policy is never, or policy is always and it already started.
     */
    suspend fun proposeOfflinePackOnCreate(courseIds: List<Long>): OfflinePackProposal? {
        val coverage = offlineMapRepository.coverageOf(courseIds)
        val choice = offlineMapRepository.userChoiceFor(courseIds)
        val decision = promptPolicy.onCourseCreated(
            policy = userPreferences.offlineDownloadPolicy.first(),
            existingStatus = coverage.status,
            existingChoice = choice
        )
        if (decision == OfflinePromptPolicy.Decision.STAY_QUIET) return null

        val proposal = quoteOfflinePack(courseIds) ?: return null
        return when (decision) {
            OfflinePromptPolicy.Decision.DOWNLOAD_SILENTLY -> {
                startOfflineDownload(proposal)
                null
            }
            // not viable means too big for MapLibre or the disk, it could only fail
            OfflinePromptPolicy.Decision.ASK -> proposal.takeIf { it.quote.isViable }
            OfflinePromptPolicy.Decision.STAY_QUIET -> null
        }
    }

    /** Observe all non-deleted routes, newest first. */
    fun observeRoutes(): Flow<List<BaseRoute>> =
        routeDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /**
     * Unapproved recordings with points, for recovery on Home. Returned without
     * points, Home only needs name and id.
     */
    fun observeInterruptedRecordings(): Flow<List<BaseRoute>> =
        routeDao.observeAll().map { entities ->
            entities
                .filter { it.source == SOURCE_RECORDED && !it.isApproved }
                .filter { routeDao.hasAnyPoints(it.id) }
                .map { it.toDomain() }
        }

    /** Load a single route with its raw + smoothed points. */
    suspend fun getRoute(id: Long): BaseRoute? {
        val entity = routeDao.getById(id) ?: return null
        val points = routeDao.getPoints(id)
        return entity.toDomain(points)
    }

    /** Create an empty route before recording. Returns the id. */
    suspend fun createRoute(
        name: String,
        notes: String = "",
        source: String = SOURCE_RECORDED
    ): Long {
        val entity = BaseRouteEntity(name = name, notes = notes, source = source)
        return routeDao.insert(entity)
    }

    /**
     * Append points as observed, no filtering. Smoothing runs on the whole
     * route (see [smoothRoute]), per flush it would depend on the write interval.
     */
    suspend fun saveRoutePoints(routeId: Long, points: List<RoutePoint>) {
        val existing = routeDao.getPoints(routeId)
        val startIndex = existing.size
        routeDao.insertPoints(points.mapIndexed { i, p -> p.toEntity(routeId, startIndex + i) })
    }

    /**
     * (Re-)run [RouteSmoothingEngine] on a stored route from the raw
     * observations. Idempotent, so the filter can be retuned later.
     *
     * Imported tracks are skipped, filtering them again shortens the route.
     * See [com.coursemapper.gpx.GpxImporter].
     */
    suspend fun smoothRoute(routeId: Long) {
        val route = routeDao.getById(routeId) ?: return
        if (route.source == SOURCE_GPX_IMPORT) return

        val stored = routeDao.getPoints(routeId)
        if (stored.isEmpty()) return

        val observations = stored.map { e ->
            e.toDomain().copy(lat = e.rawLatDeg ?: e.latDeg, lon = e.rawLonDeg ?: e.lonDeg)
        }
        val filtered = smoother.smooth(observations)

        routeDao.replacePoints(
            routeId,
            filtered.mapIndexed { i, p ->
                val observed = observations[i]
                p.toEntity(routeId, i, rawLat = observed.lat, rawLon = observed.lon)
            }
        )
    }

    /** Smooth, measure and approve a route, then schedule offline prep. */
    suspend fun approveRoute(routeId: Long) {
        val entity = routeDao.getById(routeId) ?: return
        smoothRoute(routeId)
        val smoothed = routeDao.getSmoothedPoints(routeId).map { it.toDomain() }
        val distM = distanceCalc.pathDistanceMetres(smoothed, smoothedOnly = false)
        routeDao.update(
            entity.copy(
                isApproved     = true,
                distanceMetres = distM,
                updatedAt      = System.currentTimeMillis()
            )
        )
    }

    /** Soft-delete a route (revisions and points remain for diagnostics). */
    suspend fun deleteRoute(id: Long) = routeDao.softDelete(id)

    /** Parse GPX for preview in the event wizard, nothing is stored. */
    suspend fun previewGpx(
        stream: java.io.InputStream,
        defaultName: String = "Imported route"
    ): List<GpxImporter.ImportResult> = gpxImporter.parse(stream, defaultName)

    /**
     * Store a GPX track as an approved route and a draft course. The course is
     * finished in the workspace before publishing.
     */
    suspend fun importGpxTrackAsDraft(
        result: GpxImporter.ImportResult,
        name: String,
        notes: String,
        presetId: Long,
        lapCount: Int,
        targetDistanceCm: Long,
        /** null = use the profile's own endpoint rules. See [withEndpoints]. */
        includeStart: Boolean? = null,
        includeFinish: Boolean? = null
    ): Long {
        val routeId = createRoute(
            name   = name.ifBlank { result.route.name },
            notes  = notes,
            source = SOURCE_GPX_IMPORT
        )
        val entities = result.points.mapIndexed { i, p -> p.toEntity(routeId, i) }
        routeDao.insertPoints(entities)
        approveRoute(routeId)
        return createDraftCourse(
            routeId, presetId, lapCount, targetDistanceCm, includeStart, includeFinish
        )
    }

    /** Observe all non-deleted composed courses, newest first. */
    fun observeCourses(): Flow<List<ComposedCourse>> =
        courseDao.observeAll().map { entities ->
            entities.map { e ->
                val markers = courseDao.getMarkers(e.id)
                e.toDomain(markers)
            }
        }

    /** Load a single course with its markers. */
    suspend fun getCourse(id: Long): ComposedCourse? {
        val entity  = courseDao.getById(id) ?: return null
        val markers = courseDao.getMarkers(id)
        return entity.toDomain(markers)
    }

    suspend fun getCourseByVariantId(variantId: Long): ComposedCourse? {
        val entity  = courseDao.getByVariantId(variantId) ?: return null
        val markers = courseDao.getMarkers(entity.id)
        return entity.toDomain(markers)
    }

    /** Persist a newly composed course and its markers. Returns the course id. */
    suspend fun saveCourse(
        baseRouteId: Long,
        name: String,
        notes: String = "",
        lapCount: Int,
        finalLapDistanceMetres: Double,
        totalDistanceMetres: Double,
        presetId: Long?,
        presetSnapshotJson: String,
        markers: List<DistanceMarker>,
        isDraft: Boolean = false,
        buildSpec: com.coursemapper.domain.model.CourseBuildSpec =
            com.coursemapper.domain.model.CourseBuildSpec.Manual(lapCount, finalLapDistanceMetres),
        /** 0.0 = not measured, which every new course honestly is. */
        measuredLengthMetres: Double = 0.0
    ): Long {
        val entity = ComposedCourseEntity(
            baseRouteId            = baseRouteId,
            name                   = name,
            notes                  = notes,
            lapCount               = lapCount,
            finalLapDistanceMetres = finalLapDistanceMetres,
            totalDistanceMetres    = totalDistanceMetres,
            measuredLengthMetres   = measuredLengthMetres,
            presetId               = presetId,
            presetSnapshotJson     = presetSnapshotJson,
            isDraft                = isDraft,
            buildSpecKind          = buildSpec.kind,
            buildSpecLapCount      = when (buildSpec) {
                is com.coursemapper.domain.model.CourseBuildSpec.FixedLaps      -> buildSpec.lapCount
                is com.coursemapper.domain.model.CourseBuildSpec.TargetDistance -> buildSpec.lapCount
                is com.coursemapper.domain.model.CourseBuildSpec.Manual         -> buildSpec.lapCount
            },
            buildSpecTargetMetres  = when (buildSpec) {
                is com.coursemapper.domain.model.CourseBuildSpec.TargetDistance -> buildSpec.totalMetres
                is com.coursemapper.domain.model.CourseBuildSpec.Manual         -> buildSpec.finalLapDistanceMetres
                else                                                              -> 0.0
            }
        )
        val courseId = courseDao.insert(entity)
        courseDao.insertMarkers(markers.map { it.toEntity(courseId) })

        // no offline download here, the creating screen asks once per batch so a
        // four track import doesn't start four downloads
        return courseId
    }

    /** Optimistic-lock update. False when stale, reload and retry. */
    suspend fun updateMarkerVersioned(marker: DistanceMarker): Boolean {
        val rows = courseDao.updateMarkerVersioned(
            id                       = marker.id,
            latDeg                   = marker.lat,
            lonDeg                   = marker.lon,
            cumulativeDistanceMetres = marker.cumulativeDistanceMetres,
            isManuallyMoved          = marker.isManuallyMoved,
            isDeleted                = false,
            label                    = marker.label,
            expectedVersion          = marker.version
        )
        return rows > 0
    }

    /** Soft-delete a single marker (e.g. removed by the organiser in the workspace). */
    suspend fun deleteMarker(markerId: Long) = courseDao.softDeleteMarker(markerId)

    /** Persist placement stops for a course, replacing any existing stops. */
    suspend fun savePlacementStops(courseId: Long, stops: List<PlacementStop>) {
        val persistedMarkers = courseDao.getMarkers(courseId).map { it.toDomain() }
        val threshold = userPreferences.clusteringThresholdMetres.first()
        val resolvedStops = resolveStopMarkers(stops, persistedMarkers, threshold)
        courseDao.deleteAllStops(courseId)
        if (resolvedStops.isNotEmpty()) {
            courseDao.insertStops(resolvedStops.map { it.toEntity() })
        }
    }

    /** Load all placement stops for a course, resolved with their markers. */
    suspend fun getStops(courseId: Long): List<PlacementStop> {
        val stopEntities   = courseDao.getStops(courseId)
        val allMarkers     = courseDao.getMarkers(courseId).map { it.toDomain() }
        val markersById    = allMarkers.associateBy { it.id }
        val threshold      = userPreferences.clusteringThresholdMetres.first()
        val consumedMarkerIds = mutableSetOf<Long>()
        var repaired = false

        val stops = stopEntities.map { entity ->
            val ids = try {
                org.json.JSONArray(entity.markerIdsJson)
            } catch (e: org.json.JSONException) {
                Log.w("RouteRepository", "Malformed markerIdsJson for stop ${entity.id}: '${entity.markerIdsJson}'", e)
                org.json.JSONArray()
            }
            val stopMarkers = (0 until ids.length()).mapNotNull { markersById[ids.getLong(it)] }
            if (stopMarkers.isNotEmpty()) {
                consumedMarkerIds.addAll(stopMarkers.map { it.id })
                entity.toDomain(stopMarkers)
            } else {
                val recoveredMarkers = allMarkers
                    .asSequence()
                    .filter { it.id !in consumedMarkerIds }
                    .filter {
                        distanceCalc.haversineMetres(entity.latDeg, entity.lonDeg, it.lat, it.lon) <= threshold + 0.5
                    }
                    .sortedBy { it.sequenceIndex }
                    .toList()

                if (recoveredMarkers.isNotEmpty()) {
                    repaired = true
                    consumedMarkerIds.addAll(recoveredMarkers.map { it.id })
                } else {
                    Log.w("RouteRepository",
                        "Stop ${entity.id} (index ${entity.stopIndex}) at " +
                        "${entity.latDeg},${entity.lonDeg} resolved to zero markers. " +
                        "markerIdsJson='${entity.markerIdsJson}', " +
                        "allMarkers=${allMarkers.size}, threshold=${threshold}m"
                    )
                }

                entity.toDomain(recoveredMarkers)
            }
        }

        if (repaired) {
            savePlacementStops(courseId, stops)
        }

        return stops
    }

    /** Mark a single placement stop as completed. */
    suspend fun completeStop(stop: PlacementStop) {
        courseDao.updateStop(stop.copy(isCompleted = true).toEntity())
    }

    /** Revert a placement stop to not-completed (used by Undo Last / Undo All). */
    suspend fun uncompleteStop(stop: PlacementStop) {
        courseDao.updateStop(stop.copy(isCompleted = false).toEntity())
    }

    /**
     * Soft-delete a course and release its offline pack unless another live
     * course shares it. MapLibre's store is repacked so the space comes back.
     */
    suspend fun deleteCourse(id: Long) {
        courseDao.softDelete(id)
        offlineMapRepository.onCourseDeleted(id)
    }

    /** Bytes freed by deleting [courseId], null if the pack is shared with a course that stays. */
    suspend fun offlineBytesFreedByDeleting(courseId: Long): Long? =
        offlineMapRepository.bytesFreedByDeletingCourse(courseId)

    /**
     * Rename only. [updateCourse] regenerates all markers and stops, which is
     * overkill for a label.
     */
    suspend fun renameCourse(courseId: Long, name: String) {
        val entity = courseDao.getById(courseId) ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed == entity.name) return
        courseDao.update(entity.copy(name = trimmed, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Update name, notes, composition and preset, then regenerate markers and
     * stops.
     *
     * [newPresetId]: -1 keeps the snapshot, null removes the preset, anything
     * else switches to that preset.
     */
    suspend fun updateCourse(
        courseId: Long,
        newName: String,
        newNotes: String = "",
        newLapCount: Int,
        newTargetDistanceCm: Long,
        newPresetId: Long? = -1L,  // -1 = keep current; null = clear; ≥0 = switch
        /** null = keep the course's current endpoint rules. See [withEndpoints]. */
        includeStart: Boolean? = null,
        includeFinish: Boolean? = null,
        /** null = keep the course's current measured length; 0.0 = clear it. */
        newMeasuredLengthMetres: Double? = null
    ) {
        val entity = courseDao.getById(courseId) ?: return

        // reselecting the current preset keeps the frozen snapshot, editing a
        // preset must never move an existing course's markers
        val resolvedPresetId: Long?
        val resolvedSnapshotJson: String
        when {
            newPresetId == -1L || (newPresetId != null && newPresetId == entity.presetId) -> {
                resolvedPresetId     = entity.presetId
                resolvedSnapshotJson = entity.presetSnapshotJson
            }
            newPresetId == null -> {
                // Remove preset entirely.
                resolvedPresetId     = null
                resolvedSnapshotJson = "[]"
            }
            else -> {
                // Switch to a different preset - load it and snapshot its rules.
                val preset = presetDao.getById(newPresetId)?.toDomain()
                resolvedPresetId     = newPresetId
                resolvedSnapshotJson = preset?.rules?.toJson() ?: "[]"
            }
        }
        // placement and the stored snapshot must use the same rules, or a reload
        // disagrees with the markers on the map
        val rules = resolvedSnapshotJson.toMarkerRules().withEndpoints(includeStart, includeFinish)
        val effectiveSnapshotJson = rules.toJson()
        val syntheticPreset = com.coursemapper.domain.model.MarkerPreset(
            id        = resolvedPresetId ?: -1L,
            name      = "",
            rules     = rules,
            createdAt = 0L,
            updatedAt = 0L
        )

        val targetMetres = newTargetDistanceCm.targetCentimetresToMetres()
        val newBuildSpec: com.coursemapper.domain.model.CourseBuildSpec = when {
            targetMetres > 0.0 -> com.coursemapper.domain.model.CourseBuildSpec.TargetDistance(
                totalMetres = targetMetres,
                lapCount    = newLapCount.coerceAtLeast(1)
            )
            else -> com.coursemapper.domain.model.CourseBuildSpec.FixedLaps(newLapCount.coerceAtLeast(1))
        }

        rebuildCourse(
            entity       = entity,
            rules        = rules,
            presetId     = resolvedPresetId,
            snapshotJson = effectiveSnapshotJson,
            name         = newName,
            notes        = newNotes,
            buildSpec    = newBuildSpec,
            geometryEdit = CourseGeometryEdit.fromJson(entity.geometryEditJson),
            measuredLengthMetres =
                (newMeasuredLengthMetres ?: entity.measuredLengthMetres).coerceAtLeast(0.0)
        )
    }

    /**
     * Move start, finish or direction along the base route and rebuild length,
     * markers and stops. Check [hasRunInProgress] first, run stops are snapshots.
     */
    suspend fun updateCourseGeometryEdit(courseId: Long, edit: CourseGeometryEdit) {
        val entity = courseDao.getById(courseId) ?: return
        rebuildCourse(
            entity       = entity,
            rules        = entity.presetSnapshotJson.toMarkerRules(),
            presetId     = entity.presetId,
            snapshotJson = entity.presetSnapshotJson,
            name         = entity.name,
            notes        = entity.notes,
            buildSpec    = com.coursemapper.domain.model.CourseBuildSpec.fromDb(
                entity.buildSpecKind, entity.buildSpecLapCount, entity.buildSpecTargetMetres
            ),
            geometryEdit = edit,
            // keep the measurement, it describes the ground and not the line - the
            // workspace warns if the resulting scale looks off
            measuredLengthMetres = entity.measuredLengthMetres
        )
    }

    /**
     * Polyline metres per course metre, 1.0 when not measured. Write side of
     * [com.coursemapper.domain.model.ComposedCourse.distanceScale].
     */
    private fun distanceScale(measuredLengthMetres: Double, polylineMetres: Double): Double =
        if (measuredLengthMetres > 0.0 && polylineMetres > 0.0) {
            polylineMetres / measuredLengthMetres
        } else {
            1.0
        }

    /**
     * Set the measured length and rebuild, 0.0 clears it. Check
     * [hasRunInProgress] first.
     */
    suspend fun updateCourseMeasuredLength(courseId: Long, measuredLengthMetres: Double) {
        val entity = courseDao.getById(courseId) ?: return
        rebuildCourse(
            entity       = entity,
            rules        = entity.presetSnapshotJson.toMarkerRules(),
            presetId     = entity.presetId,
            snapshotJson = entity.presetSnapshotJson,
            name         = entity.name,
            notes        = entity.notes,
            buildSpec    = com.coursemapper.domain.model.CourseBuildSpec.fromDb(
                entity.buildSpecKind, entity.buildSpecLapCount, entity.buildSpecTargetMetres
            ),
            geometryEdit = CourseGeometryEdit.fromJson(entity.geometryEditJson),
            measuredLengthMetres = measuredLengthMetres.coerceAtLeast(0.0)
        )
    }

    /**
     * Recompose a course and regenerate markers and stops. Every edit path ends
     * up here so they all rebuild the same things.
     */
    private suspend fun rebuildCourse(
        entity: ComposedCourseEntity,
        rules: List<com.coursemapper.domain.model.MarkerRule>,
        presetId: Long?,
        snapshotJson: String,
        name: String,
        notes: String,
        buildSpec: com.coursemapper.domain.model.CourseBuildSpec,
        geometryEdit: CourseGeometryEdit,
        measuredLengthMetres: Double
    ) {
        val courseId = entity.id
        val syntheticPreset = com.coursemapper.domain.model.MarkerPreset(
            id        = presetId ?: -1L,
            name      = "",
            rules     = rules,
            createdAt = 0L,
            updatedAt = 0L
        )
        val editJson = geometryEdit.toJson()

        if (entity.variantId != null) {
            // variant-backed: the compiled variant is the geometry, laps and
            // targets don't apply
            val variant        = variantDao.getById(entity.variantId)
            val compiledPoints = geometryEditor.apply(
                variant?.compiledPolylineJson?.toRoutePoints().orEmpty(),
                geometryEdit
            )
            // Measured, not taken from the variant row: an edited course is no
            // longer the length the variant was compiled to.
            val totalDistM = distanceCalc.pathDistanceMetres(compiledPoints, smoothedOnly = false)
            // from the length this rebuild produced, the stored one may be stale
            val scale = distanceScale(measuredLengthMetres, totalDistM)

            val newMarkers = if (rules.isNotEmpty() && compiledPoints.isNotEmpty()) {
                placementEngine.place(
                    composedPoints      = compiledPoints,
                    totalDistanceMetres = totalDistM,
                    preset              = syntheticPreset,
                    distanceScale       = scale
                ).markers
            } else emptyList()

            courseDao.softDeleteAllMarkers(courseId)
            courseDao.deleteAllStops(courseId)
            courseDao.update(
                entity.copy(
                    name                 = name,
                    notes                = notes,
                    totalDistanceMetres  = totalDistM,
                    measuredLengthMetres = measuredLengthMetres,
                    presetId             = presetId,
                    presetSnapshotJson   = snapshotJson,
                    geometryEditJson     = editJson,
                    updatedAt            = System.currentTimeMillis()
                )
            )
            if (newMarkers.isNotEmpty()) {
                courseDao.insertMarkers(newMarkers.map { it.toEntity(courseId) })
            }
            if (!entity.isDraft) {
                rebuildPlacementStops(courseId, newMarkers)
            }
            return
        }

        val route = getRoute(entity.baseRouteId) ?: return
        val basePoints = geometryEditor.apply(
            route.smoothedPoints.ifEmpty { route.rawPoints },
            geometryEdit
        )

        val newBuildSpec = buildSpec
        val composition = compositionEngine.compose(basePoints, newBuildSpec)

        val scale = distanceScale(measuredLengthMetres, composition.totalDistanceMetres)

        val newMarkers = if (rules.isNotEmpty() && composition.composedPoints.isNotEmpty()) {
            placementEngine.place(
                composedPoints      = composition.composedPoints,
                totalDistanceMetres = composition.totalDistanceMetres,
                preset              = syntheticPreset,
                lapTemplatePoints   = composition.lapTemplatePoints,
                laps                = composition.laps,
                distanceScale       = scale
            ).markers
        } else emptyList()

        val finalLapDistance = composition.laps.lastOrNull()?.let {
            if (it.isPartial) it.distanceMetres else 0.0
        } ?: 0.0

        // store the composed lap count, not the minimum, so laps × base + final ≈ total
        val composedFullLaps = composition.laps.count { !it.isPartial }.coerceAtLeast(1)

        courseDao.softDeleteAllMarkers(courseId)
        courseDao.deleteAllStops(courseId)
        courseDao.update(
            entity.copy(
                name                   = name,
                notes                  = notes,
                lapCount               = composedFullLaps,
                finalLapDistanceMetres = finalLapDistance,
                totalDistanceMetres    = composition.totalDistanceMetres,
                measuredLengthMetres   = measuredLengthMetres,
                presetId               = presetId,
                presetSnapshotJson     = snapshotJson,
                geometryEditJson       = editJson,
                updatedAt              = System.currentTimeMillis(),
                buildSpecKind          = newBuildSpec.kind,
                buildSpecLapCount      = when (newBuildSpec) {
                    is com.coursemapper.domain.model.CourseBuildSpec.FixedLaps      -> newBuildSpec.lapCount
                    is com.coursemapper.domain.model.CourseBuildSpec.TargetDistance -> newBuildSpec.lapCount
                    is com.coursemapper.domain.model.CourseBuildSpec.Manual         -> newBuildSpec.lapCount
                },
                buildSpecTargetMetres  = when (newBuildSpec) {
                    is com.coursemapper.domain.model.CourseBuildSpec.TargetDistance -> newBuildSpec.totalMetres
                    is com.coursemapper.domain.model.CourseBuildSpec.Manual         -> newBuildSpec.finalLapDistanceMetres
                    else                                                              -> 0.0
                }
            )
        )
        if (newMarkers.isNotEmpty()) {
            courseDao.insertMarkers(newMarkers.map { it.toEntity(courseId) })
        }
        if (!entity.isDraft) {
            rebuildPlacementStops(courseId, newMarkers)
        }
    }

    /** Compose a draft course from an approved route. Returns the course id. */
    suspend fun createDraftCourse(
        routeId: Long,
        presetId: Long,
        lapCount: Int,
        targetDistanceCm: Long,
        /** null = use the profile's own endpoint rules. See [withEndpoints]. */
        includeStart: Boolean? = null,
        includeFinish: Boolean? = null
    ): Long {
        val route = getRoute(routeId) ?: return -1L
        val basePoints = route.smoothedPoints.ifEmpty { route.rawPoints }
        val targetMetres = targetDistanceCm.targetCentimetresToMetres()

        // Derive the build spec from the caller's inputs and persist it.
        val buildSpec: com.coursemapper.domain.model.CourseBuildSpec = when {
            targetMetres > 0.0 -> com.coursemapper.domain.model.CourseBuildSpec.TargetDistance(
                totalMetres = targetMetres,
                lapCount    = lapCount.coerceAtLeast(1)
            )
            else -> com.coursemapper.domain.model.CourseBuildSpec.FixedLaps(lapCount.coerceAtLeast(1))
        }

        val composition = compositionEngine.compose(basePoints, buildSpec)

        val preset = if (presetId > 0) getPreset(presetId) else null

        // endpoints on top of the profile, so "no intervals but start and finish"
        // doesn't need its own profile
        val effectiveRules = preset?.rules.orEmpty().withEndpoints(includeStart, includeFinish)

        val markers = if (effectiveRules.isNotEmpty() && composition.composedPoints.isNotEmpty()) {
            placementEngine.place(
                composedPoints      = composition.composedPoints,
                totalDistanceMetres = composition.totalDistanceMetres,
                preset              = com.coursemapper.domain.model.MarkerPreset(
                    id        = presetId,
                    name      = preset?.name.orEmpty(),
                    rules     = effectiveRules,
                    createdAt = 0L,
                    updatedAt = 0L
                ),
                lapTemplatePoints   = composition.lapTemplatePoints,
                laps                = composition.laps
            ).markers
        } else emptyList()

        // the snapshot must include the endpoint choice or a reload shows the profile's
        val presetSnapshotJson = effectiveRules.toJson()
        val partialLap = composition.laps.lastOrNull()
            ?.takeIf { it.isPartial }?.distanceMetres ?: 0.0

        // composed lap count, see rebuildCourse
        val composedFullLaps = composition.laps.count { !it.isPartial }.coerceAtLeast(1)

        return saveCourse(
            baseRouteId            = routeId,
            name                   = route.name,
            notes                  = route.notes,
            lapCount               = composedFullLaps,
            finalLapDistanceMetres = partialLap,
            totalDistanceMetres    = composition.totalDistanceMetres,
            presetId               = if (presetId > 0) presetId else null,
            presetSnapshotJson     = presetSnapshotJson,
            markers                = markers,
            isDraft                = true,
            buildSpec              = buildSpec
        )
    }

    /** Publish a draft course and generate its placement stops. */
    suspend fun publishCourse(courseId: Long) {
        val entity = courseDao.getById(courseId) ?: return
        courseDao.update(entity.copy(isDraft = false, updatedAt = System.currentTimeMillis()))
        rebuildPlacementStops(courseId, courseDao.getMarkers(courseId).map { it.toDomain() })
    }

    fun observePresets(): Flow<List<MarkerPreset>> =
        presetDao.observeAll().map { it.map { e -> e.toDomain() } }

    suspend fun getPreset(id: Long): MarkerPreset? =
        presetDao.getById(id)?.toDomain()

    suspend fun savePreset(preset: MarkerPreset): Long {
        val entity = MarkerPresetEntity(
            id        = preset.id,
            name      = preset.name,
            rulesJson = preset.rules.toJson(),
            updatedAt = System.currentTimeMillis()
        )
        return presetDao.insert(entity)
    }

    suspend fun deletePreset(id: Long) = presetDao.softDelete(id)

    /**
     * Groups with their live member ids, newest first. Groups with no live
     * members are dropped.
     */
    fun observeCourseGroups(): Flow<List<CourseGroup>> =
        groupDao.observeAll().map { entities ->
            entities
                .map { e -> e.toDomain(groupDao.getMemberCourseIds(e.id)) }
                .filter { it.courseIds.isNotEmpty() }
        }

    suspend fun getCourseGroup(groupId: Long): CourseGroup? {
        val entity = groupDao.getById(groupId) ?: return null
        return entity.toDomain(groupDao.getMemberCourseIds(groupId))
    }

    /** Create a combined course over [courseIds]. Returns the group id. */
    suspend fun createCourseGroup(name: String, courseIds: List<Long>): Long {
        if (courseIds.isEmpty()) return -1L
        val groupId = groupDao.insertGroup(
            CourseGroupEntity(name = name.ifBlank { "Combined course" })
        )
        groupDao.insertMembers(
            courseIds.distinct().map { CourseGroupMemberEntity(groupId = groupId, courseId = it) }
        )
        return groupId
    }

    /** Rename a combined course.  Membership and every run over it are untouched. */
    suspend fun renameCourseGroup(groupId: Long, name: String) =
        groupDao.rename(groupId, name.trim().ifBlank { "Combined course" })

    /** Replace a group's members. Runs already created keep their own course list. */
    suspend fun setCourseGroupMembers(groupId: Long, courseIds: List<Long>) =
        groupDao.replaceMembers(groupId, courseIds.distinct())

    /** Live groups containing [courseId], so the delete dialog can mention them. */
    suspend fun getGroupsContainingCourse(courseId: Long): List<CourseGroup> =
        groupDao.getGroupsContainingCourse(courseId).map { it.toDomain() }

    suspend fun deleteCourseGroup(groupId: Long) = groupDao.softDelete(groupId)

    /** Dry run for run setup: stops, both visit orders and drive estimates. */
    data class RunPreview(
        /** Clustered stop positions (index-stable; orderings index into this). */
        val stopPositions: List<Pair<Double, Double>>,
        /** Signs per stop, aligned with [stopPositions]. */
        val signCounts: List<Int>,
        /** Signs per stop, for the packing list before a run exists. */
        val signs: List<List<RunSign>>,
        val spineOrder: List<Int>,
        val optimizedOrder: List<Int>,
        val spineMetres: Double,
        val optimizedMetres: Double,
        val spineCourseName: String?
    ) {
        val stopCount: Int get() = stopPositions.size
        val signCount: Int get() = signCounts.sum()
    }

    private data class AssembledRun(
        /** Marker clusters (cross-course); index-aligned with the orderings. */
        val clusters: List<PlacementStop>,
        /** Course id → display name, for sign snapshots. */
        val courseNames: Map<Long, String>,
        val spineOrder: List<Int>,
        val optimizedOrder: List<Int>,
        val spineMetres: Double,
        val optimizedMetres: Double,
        val spineCourseId: Long?
    )

    /**
     * Cluster the markers of [courseIds] into cross-course stops and work out
     * both visit orders. Markers within the threshold become one stop with
     * several signs.
     */
    private suspend fun assembleRun(
        courseIds: List<Long>,
        spineCourseId: Long?
    ): AssembledRun? {
        val courses = courseIds.mapNotNull { getCourse(it) }
        if (courses.isEmpty()) return null
        val courseNames = courses.associate { it.id to it.name }

        val allMarkers = courses.flatMap { it.markers }
        if (allMarkers.isEmpty()) {
            return AssembledRun(emptyList(), courseNames, emptyList(), emptyList(), 0.0, 0.0, null)
        }

        val threshold = userPreferences.clusteringThresholdMetres.first()
        val clusters  = placementPlanner.plan(
            markers = allMarkers,
            courseId = 0L,
            groupingThresholdMetres = threshold
        ).stops

        val stopPoints = clusters.map { VisitOrderPlanner.StopPoint(it.lat, it.lon) }
        // per-course distances place stops whose course left the spine, see
        // VisitOrderPlanner.axisOrder
        val axisStops = clusters.map { cluster ->
            VisitOrderPlanner.AxisStop(
                lat = cluster.lat,
                lon = cluster.lon,
                courseDistances = cluster.markers
                    .groupBy { it.courseId }
                    .mapValues { (_, m) -> m.minOf { it.cumulativeDistanceMetres } }
            )
        }

        // Spine = explicit choice, else the longest selected course.
        val resolvedSpineId = spineCourseId
            ?: courses.maxByOrNull { it.totalDistanceMetres }?.id
        val spineGeometry = resolvedSpineId?.let { getCourseGeometry(it) }.orEmpty()
        val start = spineGeometry.firstOrNull()
            ?.let { VisitOrderPlanner.StopPoint(it.lat, it.lon) }

        val axis = courseAxisBuilder.build(spineGeometry)
        val spineOrder = if (axis != null) {
            visitOrderPlanner.axisOrder(axisStops, axis)
        } else {
            visitOrderPlanner.spineOrder(stopPoints, spineGeometry)
        }
        // seeded with the axis order so shortest path is never longer than
        // follow main route
        val optimizedOrder = visitOrderPlanner.optimizedOrder(stopPoints, start, spineOrder)

        return AssembledRun(
            clusters        = clusters,
            courseNames     = courseNames,
            spineOrder      = spineOrder,
            optimizedOrder  = optimizedOrder,
            spineMetres     = visitOrderPlanner.pathLengthMetres(stopPoints, spineOrder),
            optimizedMetres = visitOrderPlanner.pathLengthMetres(stopPoints, optimizedOrder),
            spineCourseId   = resolvedSpineId
        )
    }

    /** Dry-run assembly for the run setup screen. */
    suspend fun previewRun(
        courseIds: List<Long>,
        spineCourseId: Long? = null
    ): RunPreview? {
        val assembled = assembleRun(courseIds, spineCourseId) ?: return null
        return RunPreview(
            stopPositions   = assembled.clusters.map { it.lat to it.lon },
            signCounts      = assembled.clusters.map { it.markers.size },
            signs           = assembled.clusters.map { cluster ->
                cluster.markers
                    .map { m ->
                        RunSign(
                            courseName = assembled.courseNames[m.courseId] ?: "Course",
                            label      = m.label.ifBlank { m.type.name }
                        )
                    }
                    .sortedBy { it.courseName }
            },
            spineOrder      = assembled.spineOrder,
            optimizedOrder  = assembled.optimizedOrder,
            spineMetres     = assembled.spineMetres,
            optimizedMetres = assembled.optimizedMetres,
            spineCourseName = assembled.spineCourseId?.let { assembled.courseNames[it] }
        )
    }

    /**
     * Create a run over [courseIds] with [ordering], snapshotting the signs.
     * Returns the run id, or -1 when there was nothing to assemble.
     */
    suspend fun createRun(
        name: String,
        courseIds: List<Long>,
        ordering: RunOrdering,
        spineCourseId: Long? = null,
        travelProfile: TravelProfile = TravelProfile.CAR,
        /** Combined course this run was launched from; null for an ad-hoc run. */
        groupId: Long? = null
    ): Long {
        val assembled = assembleRun(courseIds, spineCourseId) ?: return -1L
        val order = if (ordering == RunOrdering.OPTIMIZED) assembled.optimizedOrder
                    else assembled.spineOrder
        val plannedMetres = if (ordering == RunOrdering.OPTIMIZED) assembled.optimizedMetres
                            else assembled.spineMetres

        val now = System.currentTimeMillis()
        val runId = runDao.insertRun(
            PlacementRunEntity(
                name               = name.ifBlank { "Placement run" },
                createdAt          = now,
                updatedAt          = now,
                orderingMode       = ordering.toDb(),
                spineCourseId      = assembled.spineCourseId,
                totalPlannedMetres = plannedMetres,
                travelProfile      = travelProfile.toDb(),
                groupId            = groupId
            )
        )
        runDao.insertCourses(courseIds.map { PlacementRunCourseEntity(runId = runId, courseId = it) })
        runDao.insertStops(
            order.mapIndexed { visitIdx, clusterIdx ->
                val cluster = assembled.clusters[clusterIdx]
                val signs = cluster.markers
                    .map { m ->
                        RunSign(
                            courseName = assembled.courseNames[m.courseId] ?: "Course",
                            label      = m.label.ifBlank { m.type.name }
                        )
                    }
                    .sortedBy { it.courseName }
                PlacementRunStopEntity(
                    runId     = runId,
                    stopIndex = visitIdx,
                    latDeg    = cluster.lat,
                    lonDeg    = cluster.lon,
                    signsJson = signs.toSignsJson()
                )
            }
        )
        // no offline download here on purpose, this is when the phone goes on the
        // ATV. It's offered when the course is created, and the gate is the fallback.
        return runId
    }

    /**
     * Whether a run with [courseId] has progress and isn't finished. Such a run
     * keeps its old stops if the course is recomposed, so warn first.
     */
    suspend fun hasRunInProgress(courseId: Long): Boolean =
        runDao.countRunsInProgressForCourse(courseId) > 0

    /**
     * The single-course run whose order is settled, or null if the next one is
     * assembled fresh. Runs without progress are rebuilt on open, so the packing
     * list uses the preview for those.
     */
    suspend fun getCommittedSingleCourseRunId(courseId: Long): Long? {
        val existing = runDao.findSingleCourseRun(courseId) ?: return null
        val hasProgress = runDao.getStops(existing.id)
            .any { it.state != RunStopState.PENDING.toDb() }
        return existing.id.takeIf { hasProgress }
    }

    /** Latest run from [groupId], for "same as last time" defaults. */
    suspend fun getLatestRunForGroup(groupId: Long): PlacementRun? =
        runDao.findLatestRunForGroup(groupId)?.let { entity ->
            entity.toDomain(runDao.getCourseIds(entity.id))
        }

    /** Latest unfinished run from [groupId], turns the launch button into Resume. */
    suspend fun getUnfinishedRunForGroup(groupId: Long): PlacementRun? =
        runDao.findUnfinishedRunForGroup(groupId)?.let { entity ->
            entity.toDomain(
                courseIds = runDao.getCourseIds(entity.id),
                stops     = runDao.getStops(entity.id).map { it.toDomain() }
            )
        }

    /** Load a run with its course ids and stops in visit order. */
    suspend fun getRun(runId: Long): PlacementRun? {
        val entity = runDao.getById(runId) ?: return null
        val courseIds = runDao.getCourseIds(runId)
        val stops = runDao.getStops(runId).map { it.toDomain() }
        return entity.toDomain(courseIds, stops)
    }

    /** Unfinished runs (with stops, for progress display), newest first. */
    fun observeIncompleteRuns(): Flow<List<PlacementRun>> =
        runDao.observeIncompleteRuns().map { entities ->
            entities.map { e ->
                e.toDomain(stops = runDao.getStops(e.id).map { it.toDomain() })
            }
        }

    /** Record that navigation has opened this run (first time only). */
    suspend fun markRunStarted(runId: Long) {
        val run = runDao.getById(runId) ?: return
        if (run.startedAt == null) {
            runDao.updateRun(run.copy(startedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
        }
    }

    /** Set one stop's state and bump the run's updatedAt. */
    suspend fun setRunStopState(runId: Long, stopId: Long, state: RunStopState) {
        val at = if (state == RunStopState.PENDING) null else System.currentTimeMillis()
        runDao.updateStopState(stopId, state.toDb(), at)
        runDao.getById(runId)?.let { runDao.updateRun(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    /** Switch the run's travel profile (re-routing is the caller's concern). */
    suspend fun updateRunTravelProfile(runId: Long, profile: TravelProfile) {
        val run = runDao.getById(runId) ?: return
        runDao.updateRun(
            run.copy(travelProfile = profile.toDb(), updatedAt = System.currentTimeMillis())
        )
    }

    /** Mark the run finished. */
    suspend fun completeRun(runId: Long) {
        val run = runDao.getById(runId) ?: return
        val now = System.currentTimeMillis()
        runDao.updateRun(run.copy(completedAt = now, updatedAt = now))
    }

    suspend fun deleteRun(runId: Long) = runDao.softDelete(runId)

    /**
     * End of run sweep: reopen skipped stops and replan pending ones as a
     * shortest path from the rider. Completed stops stay first.
     */
    suspend fun reactivateSkippedStops(runId: Long, fromLat: Double, fromLon: Double) {
        val stops = runDao.getStops(runId).map { it.toDomain() }
        if (stops.none { it.state == RunStopState.SKIPPED }) return

        val reopened = stops.map {
            if (it.state == RunStopState.SKIPPED) it.copy(state = RunStopState.PENDING, stateChangedAt = null)
            else it
        }
        val done    = reopened.filter { it.state == RunStopState.DONE }.sortedBy { it.stopIndex }
        val pending = reopened.filter { it.state == RunStopState.PENDING }

        val order = visitOrderPlanner.optimizedOrder(
            stops = pending.map { VisitOrderPlanner.StopPoint(it.lat, it.lon) },
            start = VisitOrderPlanner.StopPoint(fromLat, fromLon)
        )

        val reindexed =
            done.mapIndexed { i, stop -> stop.copy(stopIndex = i) } +
            order.mapIndexed { i, pendingIdx -> pending[pendingIdx].copy(stopIndex = done.size + i) }

        runDao.updateStops(reindexed.map { it.toEntity() })
        runDao.getById(runId)?.let { runDao.updateRun(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    /**
     * Resequence for a rider who turned around: nearest pending stop ahead
     * becomes next and the rest follows from there. See
     * [com.coursemapper.domain.DirectionalSequencer].
     */
    suspend fun resequenceFromDirection(
        runId: Long,
        riderLat: Double,
        riderLon: Double,
        headingDegrees: Double?
    ) {
        val run = getRun(runId) ?: return
        val axis = if (run.ordering == RunOrdering.SPINE) {
            run.spineCourseId
                ?.let { getCourseGeometry(it) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { courseAxisBuilder.build(it) }
        } else null

        val stops = runDao.getStops(runId).map { it.toDomain() }
        val resequenced = directionalSequencer.resequence(
            stops = stops,
            riderLat = riderLat,
            riderLon = riderLon,
            headingDegrees = headingDegrees,
            axis = axis
        )
        runDao.updateStops(resequenced.map { it.toEntity() })
        runDao.getById(runId)?.let { runDao.updateRun(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    /** Make a pending stop next and continue in the existing order, wrapping around. */
    suspend fun makeStopNext(runId: Long, stopId: Long) {
        val stops = runDao.getStops(runId).map { it.toDomain() }.sortedBy { it.stopIndex }
        if (stops.none { it.id == stopId && it.state == RunStopState.PENDING }) return
        val reindexed = NavigationSequenceEngine.takeNextThenContinue(stops, stopId)

        runDao.updateStops(reindexed.map { it.toEntity() })
        runDao.getById(runId)?.let { runDao.updateRun(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    /**
     * Single-course run for the workspace quick start. Reuses an unfinished run,
     * and reassembles its stops if it has no progress yet.
     */
    suspend fun getOrCreateSingleCourseRun(courseId: Long): Long {
        val course = getCourse(courseId) ?: return -1L
        val existing = runDao.findSingleCourseRun(courseId)
        if (existing != null) {
            val stops = runDao.getStops(existing.id)
            val hasProgress = stops.any { it.state != "pending" }
            if (hasProgress) return existing.id
            // No progress: rebuild stops to reflect the course's current markers.
            runDao.deleteStops(existing.id)
            val assembled = assembleRun(listOf(courseId), courseId) ?: return existing.id
            runDao.insertStops(
                assembled.spineOrder.mapIndexed { visitIdx, clusterIdx ->
                    val cluster = assembled.clusters[clusterIdx]
                    val signs = cluster.markers.map { m ->
                        RunSign(
                            courseName = assembled.courseNames[m.courseId] ?: course.name,
                            label      = m.label.ifBlank { m.type.name }
                        )
                    }
                    PlacementRunStopEntity(
                        runId     = existing.id,
                        stopIndex = visitIdx,
                        latDeg    = cluster.lat,
                        lonDeg    = cluster.lon,
                        signsJson = signs.toSignsJson()
                    )
                }
            )
            runDao.updateRun(
                existing.copy(
                    totalPlannedMetres = assembled.spineMetres,
                    updatedAt          = System.currentTimeMillis()
                )
            )
            return existing.id
        }
        return createRun(
            name          = course.name,
            courseIds     = listOf(courseId),
            ordering      = RunOrdering.SPINE,
            spineCourseId = courseId,
            // no setup screen here, reuse the last profile instead of forcing car
            travelProfile = runDao.findLatestRun()?.travelProfile?.toTravelProfile()
                ?: TravelProfile.CAR
        )
    }

    /** Share intent for the run log CSV. */
    suspend fun buildRunLogShareIntent(runId: Long): android.content.Intent? {
        val run = getRun(runId) ?: return null
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        val csv = buildString {
            appendLine("visit_order,state,timestamp,lat,lon,signs")
            run.stops.sortedBy { it.stopIndex }.forEachIndexed { i, stop ->
                val ts = stop.stateChangedAt?.let { iso.format(java.util.Date(it)) } ?: ""
                val signs = stop.signs.joinToString("; ") { "${it.courseName} ${it.label}" }
                    .replace("\"", "'")
                appendLine("${i + 1},${stop.state.name.lowercase()},$ts,${stop.lat},${stop.lon},\"$signs\"")
            }
        }
        return shareManager.buildTextFileShareIntent(
            fileName = "${run.name}-placement-log.csv",
            content  = csv,
            mimeType = "text/csv",
            summary  = "Placement log for ${run.name}: " +
                "${run.stops.count { it.state == RunStopState.DONE }} placed, " +
                "${run.stops.count { it.state == RunStopState.SKIPPED }} skipped, " +
                "${run.stops.size} total stops."
        )
    }

    /** Share intent for [routeId], wrap in [android.content.Intent.createChooser]. */
    suspend fun buildRouteShareIntent(routeId: Long): android.content.Intent? {
        val route = getRoute(routeId) ?: return null
        return shareManager.buildRouteShareIntent(route)
    }

    /** Share intent for [courseId], [composedPoints] must be the full composed polyline. */
    suspend fun buildCourseShareIntent(
        courseId: Long,
        composedPoints: List<com.coursemapper.domain.model.RoutePoint>
    ): android.content.Intent? {
        val course = getCourse(courseId) ?: return null
        return shareManager.buildCourseShareIntent(course, composedPoints)
    }


    /** All networks with segments and junctions, newest first. */
    fun observeNetworks(): Flow<List<RouteNetwork>> =
        networkDao.observeAll().map { entities ->
            entities.map { e ->
                val segments  = networkDao.getSegments(e.id)
                val junctions = networkDao.getJunctions(e.id)
                e.toDomain(segments, junctions)
            }
        }

    /** Load a single network with its segments and junctions. */
    suspend fun getNetwork(id: Long): RouteNetwork? {
        val entity    = networkDao.getById(id) ?: return null
        val segments  = networkDao.getSegments(id)
        val junctions = networkDao.getJunctions(id)
        return entity.toDomain(segments, junctions)
    }

    /** Create a network with [baseRouteId] as its trunk. Returns the id. */
    suspend fun createNetwork(baseRouteId: Long, name: String, notes: String = ""): Long {
        val route = routeDao.getById(baseRouteId) ?: return -1L
        val now   = System.currentTimeMillis()
        val networkId = networkDao.insertNetwork(
            RouteNetworkEntity(name = name, notes = notes, createdAt = now, updatedAt = now)
        )
        networkDao.insertSegment(
            RouteSegmentEntity(
                networkId      = networkId,
                baseRouteId    = baseRouteId,
                isMainTrunk    = true,
                orderIndex     = 0,
                distanceMetres = route.distanceMetres
            )
        )
        return networkId
    }

    /** Persist a junction anchor and return its assigned ID. */
    suspend fun saveJunction(junction: RouteJunction): Long =
        networkDao.insertJunction(junction.toEntity().copy(id = 0))

    /**
     * Attach [baseRouteId] as a branch between two junctions, at
     * [fromMetres]/[toMetres] on the trunk. Returns the segment id.
     */
    suspend fun addBranchSegment(
        networkId: Long,
        baseRouteId: Long,
        fromJunctionId: Long?,
        toJunctionId: Long?,
        fromMetres: Double? = null,
        toMetres: Double? = null,
        orderIndex: Int = 0
    ): Long {
        val route = routeDao.getById(baseRouteId)
        return networkDao.insertSegment(
            RouteSegmentEntity(
                networkId      = networkId,
                baseRouteId    = baseRouteId,
                isMainTrunk    = false,
                fromJunctionId = fromJunctionId,
                toJunctionId   = toJunctionId,
                orderIndex     = orderIndex,
                distanceMetres = route?.distanceMetres ?: 0.0,
                fromMetres     = fromMetres,
                toMetres       = toMetres
            )
        )
    }

    /** Observe all non-deleted variants for [networkId] in creation order. */
    fun observeVariants(networkId: Long): Flow<List<RouteVariant>> =
        variantDao.observeByNetwork(networkId).map { it.map { e -> e.toDomain() } }

    /** Load all non-deleted variants for [networkId] (one-shot). */
    suspend fun getVariantsForNetwork(networkId: Long): List<RouteVariant> =
        variantDao.getByNetwork(networkId).map { it.toDomain() }

    /** Load a single variant. */
    suspend fun getVariant(id: Long): RouteVariant? = variantDao.getById(id)?.toDomain()

    /** New variant from [segmentIds], see [compileAndSaveVariant]. */
    suspend fun createVariant(networkId: Long, name: String, segmentIds: List<Long>): Long {
        val now = System.currentTimeMillis()
        return variantDao.insert(
            RouteVariantEntity(
                networkId      = networkId,
                name           = name,
                segmentIdsJson = segmentIds.toIdsJson(),
                createdAt      = now,
                updatedAt      = now
            )
        )
    }

    /**
     * Stitch the segments of [variantId] into one polyline and store it. Trunk
     * segments with from/to metres are sliced first.
     */
    suspend fun compileAndSaveVariant(variantId: Long) {
        val variant    = variantDao.getById(variantId) ?: return
        val segmentIds = variant.segmentIdsJson.toLongIds()
        val segments   = networkDao.getSegments(variant.networkId)

        val slices = segmentIds.mapNotNull { segId ->
            val seg = segments.find { it.id == segId } ?: return@mapNotNull null
            val points = routeDao.getSmoothedPoints(seg.baseRouteId).map { it.toDomain() }
            com.coursemapper.domain.SegmentPolylineSlice(
                points     = points,
                fromMetres = seg.fromMetres,
                toMetres   = seg.toMetres
            )
        }

        val result = compilationEngine.compile(slices)
        variantDao.update(
            variant.copy(
                compiledPolylineJson = result.compiledPoints.toPolylineJson(),
                totalDistanceMetres  = result.totalDistanceMetres,
                updatedAt            = System.currentTimeMillis()
            )
        )
    }

    /**
     * Compile, place markers and create a draft course for the variant. Drafts
     * so markerless courses don't get published by accident.
     */
    suspend fun approveVariant(variantId: Long, presetId: Long?) =
        createVariantCourseDraft(variantId, presetId)

    /** Compile if needed and create a draft course for [variantId]. Returns the course id. */
    suspend fun createVariantCourseDraft(variantId: Long, presetId: Long?): Long {
        if (variantDao.getById(variantId)?.compiledPolylineJson == null) {
            compileAndSaveVariant(variantId)
        }
        val variant        = variantDao.getById(variantId) ?: return -1L
        val compiledPoints = variant.compiledPolylineJson?.toRoutePoints() ?: return -1L
        val totalDistM     = variant.totalDistanceMetres ?: 0.0

        val preset = if (presetId != null && presetId > 0) presetDao.getById(presetId)?.toDomain() else null

        val markers = if (preset != null && compiledPoints.isNotEmpty()) {
            placementEngine.place(
                composedPoints      = compiledPoints,
                totalDistanceMetres = totalDistM,
                preset              = preset
            ).markers
        } else emptyList()

        val presetSnapshotJson = preset?.let { it.rules.toJson() } ?: "[]"

        // Use the trunk segment's baseRouteId as the course's primary route reference.
        val trunkSegment = networkDao.getSegments(variant.networkId).firstOrNull { it.isMainTrunk }
        val baseRouteId  = trunkSegment?.baseRouteId ?: return -1L

        val courseEntity = ComposedCourseEntity(
            baseRouteId            = baseRouteId,
            name                   = variant.name,
            lapCount               = 1,
            finalLapDistanceMetres = 0.0,
            totalDistanceMetres    = totalDistM,
            presetId               = presetId,
            presetSnapshotJson     = presetSnapshotJson,
            variantId              = variantId,
            isDraft                = true
        )
        val courseId = courseDao.insert(courseEntity)
        if (markers.isNotEmpty()) {
            courseDao.insertMarkers(markers.map { it.toEntity(courseId) })
        }
        rebuildPlacementStops(courseId, markers)

        variantDao.update(variant.copy(isApproved = true, updatedAt = System.currentTimeMillis()))
        return courseId
    }

    private suspend fun rebuildPlacementStops(courseId: Long, markers: List<DistanceMarker>) {
        val threshold = userPreferences.clusteringThresholdMetres.first()
        val stops = if (markers.isEmpty()) {
            emptyList()
        } else {
            placementPlanner.plan(
                markers = markers,
                courseId = courseId,
                groupingThresholdMetres = threshold
            ).stops
        }
        savePlacementStops(courseId, stops)
    }

    private fun resolveStopMarkers(
        stops: List<PlacementStop>,
        persistedMarkers: List<DistanceMarker>,
        thresholdMetres: Double
    ): List<PlacementStop> {
        if (stops.isEmpty() || persistedMarkers.isEmpty()) return stops

        val persistedById = persistedMarkers.associateBy { it.id }
        val persistedBySequence = persistedMarkers.associateBy { it.sequenceIndex }

        return stops.map { stop ->
            val resolvedMarkers = stop.markers
                .mapNotNull { marker ->
                    when {
                        marker.id > 0L -> persistedById[marker.id]
                        else -> persistedBySequence[marker.sequenceIndex]
                    }
                }
                .distinctBy { it.id }
                .ifEmpty {
                    persistedMarkers.filter { marker ->
                        distanceCalc.haversineMetres(stop.lat, stop.lon, marker.lat, marker.lon) <= thresholdMetres + 0.5
                    }.sortedBy { it.sequenceIndex }
                }

            stop.copy(markers = resolvedMarkers)
        }
    }
}
