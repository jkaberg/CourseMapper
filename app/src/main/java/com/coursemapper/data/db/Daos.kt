package com.coursemapper.data.db

import androidx.room.*
import com.coursemapper.domain.model.OfflineFailureReason
import com.coursemapper.domain.model.OfflinePackChoice
import com.coursemapper.domain.model.OfflinePackStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDao {
    @Query("SELECT * FROM base_routes WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<BaseRouteEntity>>

    @Query("SELECT * FROM base_routes WHERE id = :id AND isDeleted = 0")
    suspend fun getById(id: Long): BaseRouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(route: BaseRouteEntity): Long

    @Update
    suspend fun update(route: BaseRouteEntity)

    @Query("UPDATE base_routes SET isDeleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<RoutePointEntity>)

    @Query("SELECT * FROM route_points WHERE routeId = :routeId ORDER BY `index` ASC")
    suspend fun getPoints(routeId: Long): List<RoutePointEntity>

    @Query("SELECT * FROM route_points WHERE routeId = :routeId AND isSmoothed = 1 ORDER BY `index` ASC")
    suspend fun getSmoothedPoints(routeId: Long): List<RoutePointEntity>

    /**
     * Whether a route has any points at all. Home asks this for every
     * unapproved recording on every emission, so no loading points.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM route_points WHERE routeId = :routeId)")
    suspend fun hasAnyPoints(routeId: Long): Boolean

    /** Replace all points of a route in one go, a partial write would leave it half smoothed. */
    @Transaction
    suspend fun replacePoints(routeId: Long, points: List<RoutePointEntity>) {
        deletePoints(routeId)
        insertPoints(points)
    }

    @Query("DELETE FROM route_points WHERE routeId = :routeId")
    suspend fun deletePoints(routeId: Long)
}

@Dao
interface CourseDao {
    @Query("SELECT * FROM composed_courses WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ComposedCourseEntity>>

    @Query("SELECT * FROM composed_courses WHERE id = :id AND isDeleted = 0")
    suspend fun getById(id: Long): ComposedCourseEntity?

    @Query("SELECT * FROM composed_courses WHERE variantId = :variantId AND isDeleted = 0 LIMIT 1")
    suspend fun getByVariantId(variantId: Long): ComposedCourseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(course: ComposedCourseEntity): Long

    @Update
    suspend fun update(course: ComposedCourseEntity)

    @Query("UPDATE composed_courses SET isDeleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarkers(markers: List<DistanceMarkerEntity>)

    @Query("SELECT * FROM distance_markers WHERE courseId = :courseId AND isDeleted = 0 ORDER BY sequenceIndex ASC")
    suspend fun getMarkers(courseId: Long): List<DistanceMarkerEntity>

    @Update
    suspend fun updateMarker(marker: DistanceMarkerEntity)

    /** Optimistic-lock update. Returns 0 on version conflict, caller reloads and retries. */
    @Query("""
        UPDATE distance_markers
        SET latDeg = :latDeg, lonDeg = :lonDeg,
            cumulativeDistanceMetres = :cumulativeDistanceMetres,
            isManuallyMoved = :isManuallyMoved, isDeleted = :isDeleted,
            label = :label, version = :expectedVersion + 1
        WHERE id = :id AND version = :expectedVersion
    """)
    suspend fun updateMarkerVersioned(
        id: Long,
        latDeg: Double,
        lonDeg: Double,
        cumulativeDistanceMetres: Double,
        isManuallyMoved: Boolean,
        isDeleted: Boolean,
        label: String,
        expectedVersion: Int
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStops(stops: List<PlacementStopEntity>)

    @Query("SELECT * FROM placement_stops WHERE courseId = :courseId ORDER BY stopIndex ASC")
    suspend fun getStops(courseId: Long): List<PlacementStopEntity>

    @Update
    suspend fun updateStop(stop: PlacementStopEntity)

    /** Soft-delete all markers for a course (e.g. when recomposing after an edit). */
    @Query("UPDATE distance_markers SET isDeleted = 1 WHERE courseId = :courseId")
    suspend fun softDeleteAllMarkers(courseId: Long)

    /** Soft-delete a single marker. */
    @Query("UPDATE distance_markers SET isDeleted = 1 WHERE id = :id")
    suspend fun softDeleteMarker(id: Long)

    /** Hard-delete all placement stops for a course (they become stale after composition changes). */
    @Query("DELETE FROM placement_stops WHERE courseId = :courseId")
    suspend fun deleteAllStops(courseId: Long)
}

@Dao
interface MarkerPresetDao {
    @Query("SELECT * FROM marker_presets WHERE isDeleted = 0 ORDER BY name ASC")
    fun observeAll(): Flow<List<MarkerPresetEntity>>

    @Query("SELECT * FROM marker_presets WHERE id = :id AND isDeleted = 0")
    suspend fun getById(id: Long): MarkerPresetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: MarkerPresetEntity): Long

    @Update
    suspend fun update(preset: MarkerPresetEntity)

    @Query("UPDATE marker_presets SET isDeleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())
}

@Dao
interface CourseGroupDao {
    @Query("SELECT * FROM course_groups WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CourseGroupEntity>>

    @Query("SELECT * FROM course_groups WHERE id = :id AND isDeleted = 0")
    suspend fun getById(id: Long): CourseGroupEntity?

    /**
     * Live members only. Courses are soft-deleted so the FK never fires, without
     * the join deleted courses end up in runs.
     */
    @Query("""
        SELECT m.courseId FROM course_group_members m
        JOIN composed_courses c ON c.id = m.courseId
        WHERE m.groupId = :groupId AND c.isDeleted = 0
    """)
    suspend fun getMemberCourseIds(groupId: Long): List<Long>

    /** Live groups that [courseId] belongs to - what makes a delete dialog honest. */
    @Query("""
        SELECT g.* FROM course_groups g
        JOIN course_group_members m ON m.groupId = g.id
        WHERE m.courseId = :courseId AND g.isDeleted = 0
        ORDER BY g.updatedAt DESC
    """)
    suspend fun getGroupsContainingCourse(courseId: Long): List<CourseGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: CourseGroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<CourseGroupMemberEntity>)

    @Query("DELETE FROM course_group_members WHERE groupId = :groupId")
    suspend fun deleteMembers(groupId: Long)

    @Query("UPDATE course_groups SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE course_groups SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long = System.currentTimeMillis())

    /** Replace a group's membership wholesale (add, remove, reorder). */
    @Transaction
    suspend fun replaceMembers(groupId: Long, courseIds: List<Long>) {
        deleteMembers(groupId)
        if (courseIds.isNotEmpty()) {
            insertMembers(courseIds.map { CourseGroupMemberEntity(groupId = groupId, courseId = it) })
        }
        touch(groupId)
    }

    @Query("UPDATE course_groups SET isDeleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())
}

@Dao
interface PlacementRunDao {
    @Query("SELECT * FROM placement_runs WHERE id = :id AND isDeleted = 0")
    suspend fun getById(id: Long): PlacementRunEntity?

    /** Runs that have not been finished - shown as resumable on Home. */
    @Query("SELECT * FROM placement_runs WHERE isDeleted = 0 AND completedAt IS NULL ORDER BY updatedAt DESC")
    fun observeIncompleteRuns(): Flow<List<PlacementRunEntity>>

    /** Latest unfinished single-course run for [courseId], reused so quick runs don't pile up. */
    @Query("""
        SELECT r.* FROM placement_runs r
        WHERE r.isDeleted = 0 AND r.completedAt IS NULL
          AND (SELECT COUNT(*) FROM placement_run_courses c WHERE c.runId = r.id) = 1
          AND EXISTS (SELECT 1 FROM placement_run_courses c2 WHERE c2.runId = r.id AND c2.courseId = :courseId)
        ORDER BY r.updatedAt DESC LIMIT 1
    """)
    suspend fun findSingleCourseRun(courseId: Long): PlacementRunEntity?

    /**
     * Unfinished runs with [courseId] and some progress. Run stops are snapshots,
     * so used to warn before changing course geometry under an active run.
     */
    @Query("""
        SELECT COUNT(*) FROM placement_runs r
        WHERE r.isDeleted = 0 AND r.completedAt IS NULL
          AND EXISTS (
            SELECT 1 FROM placement_run_courses c
            WHERE c.runId = r.id AND c.courseId = :courseId
          )
          AND EXISTS (
            SELECT 1 FROM placement_run_stops s
            WHERE s.runId = r.id AND s.state != 'pending'
          )
    """)
    suspend fun countRunsInProgressForCourse(courseId: Long): Int

    /**
     * Latest run from [groupId], finished or not. Keyed on the group rather than
     * the course set since membership can change between outings.
     */
    @Query("""
        SELECT * FROM placement_runs
        WHERE isDeleted = 0 AND groupId = :groupId
        ORDER BY updatedAt DESC LIMIT 1
    """)
    suspend fun findLatestRunForGroup(groupId: Long): PlacementRunEntity?

    /**
     * Latest run of any kind. The workspace quick-start has no setup screen, so
     * it reuses the last travel profile instead of defaulting to car.
     */
    @Query("""
        SELECT * FROM placement_runs
        WHERE isDeleted = 0
        ORDER BY updatedAt DESC LIMIT 1
    """)
    suspend fun findLatestRun(): PlacementRunEntity?

    /** Most recent unfinished run for [groupId] - what Resume picks up. */
    @Query("""
        SELECT * FROM placement_runs
        WHERE isDeleted = 0 AND completedAt IS NULL AND groupId = :groupId
        ORDER BY updatedAt DESC LIMIT 1
    """)
    suspend fun findUnfinishedRunForGroup(groupId: Long): PlacementRunEntity?

    @Query("SELECT courseId FROM placement_run_courses WHERE runId = :runId")
    suspend fun getCourseIds(runId: Long): List<Long>

    @Query("SELECT * FROM placement_run_stops WHERE runId = :runId ORDER BY stopIndex ASC")
    suspend fun getStops(runId: Long): List<PlacementRunStopEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: PlacementRunEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<PlacementRunCourseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStops(stops: List<PlacementRunStopEntity>)

    @Update
    suspend fun updateRun(run: PlacementRunEntity)

    @Update
    suspend fun updateStops(stops: List<PlacementRunStopEntity>)

    @Query("UPDATE placement_run_stops SET state = :state, stateChangedAt = :at WHERE id = :stopId")
    suspend fun updateStopState(stopId: Long, state: String, at: Long?)

    @Query("DELETE FROM placement_run_stops WHERE runId = :runId")
    suspend fun deleteStops(runId: Long)

    @Query("UPDATE placement_runs SET isDeleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())
}

/** Offline state per course as a rank, see [OfflinePackDao.observeCourseOfflineRanks]. */
data class CourseOfflineRank(
    val courseId: Long,
    /** 0 ready, 1 stale, 2 downloading, 3 queued, 4 paused, 5 failed. Not `rank`, that's reserved in SQLite. */
    val offlineRank: Int
) {
    val isUsable: Boolean get() = offlineRank <= 1
    val isInFlight: Boolean get() = offlineRank == 2 || offlineRank == 3
}

/** A pack row together with the per-style regions that hold its tiles. */
data class OfflinePackWithRegions(
    @Embedded val pack: OfflinePackEntity,
    @Relation(parentColumn = "id", entityColumn = "packId")
    val regions: List<OfflinePackRegionEntity>
)

@Dao
interface OfflinePackDao {

    @Transaction
    @Query("SELECT * FROM offline_packs ORDER BY updatedAt DESC")
    fun observeAllWithRegions(): Flow<List<OfflinePackWithRegions>>

    @Query("SELECT * FROM offline_packs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<OfflinePackEntity>>

    /** One-shot list, for the reconciler's sweep. */
    @Query("SELECT * FROM offline_packs ORDER BY updatedAt DESC")
    suspend fun observeAllSnapshot(): List<OfflinePackEntity>

    @Query("SELECT * FROM offline_packs WHERE id = :id")
    suspend fun getById(id: Long): OfflinePackEntity?

    @Transaction
    @Query("SELECT * FROM offline_packs WHERE id = :id")
    suspend fun getWithRegions(id: Long): OfflinePackWithRegions?

    @Query("SELECT * FROM offline_pack_regions WHERE packId = :packId")
    suspend fun getRegions(packId: Long): List<OfflinePackRegionEntity>

    @Query("SELECT * FROM offline_pack_regions")
    suspend fun getAllRegions(): List<OfflinePackRegionEntity>

    /** Packs covering [courseId], including deleted courses - used to find what a delete releases. */
    @Query(
        """
        SELECT p.* FROM offline_packs p
        JOIN offline_pack_courses pc ON pc.packId = p.id
        WHERE pc.courseId = :courseId
        """
    )
    suspend fun getPacksForCourse(courseId: Long): List<OfflinePackEntity>

    /**
     * Course ids a pack still covers, excluding soft-deleted courses. Without the
     * join a deleted course keeps its pack forever. Tested against real Room.
     */
    @Query(
        """
        SELECT c.id FROM composed_courses c
        JOIN offline_pack_courses pc ON pc.courseId = c.id
        WHERE pc.packId = :packId AND c.isDeleted = 0
        """
    )
    suspend fun liveCourseIds(packId: Long): List<Long>

    /** Every (packId, courseId) pair whose course is still live. */
    @Query(
        """
        SELECT pc.* FROM offline_pack_courses pc
        JOIN composed_courses c ON c.id = pc.courseId AND c.isDeleted = 0
        """
    )
    suspend fun allLiveLinks(): List<OfflinePackCourseEntity>

    /** Packs no live course needs anymore, same rule as [liveCourseIds]. */
    @Query(
        """
        SELECT id FROM offline_packs WHERE id NOT IN (
            SELECT pc.packId FROM offline_pack_courses pc
            JOIN composed_courses c ON c.id = pc.courseId AND c.isDeleted = 0
        )
        """
    )
    suspend fun findOrphanPackIds(): List<Long>

    /** A live, usable pack already covering [courseId], if any. */
    @Query(
        """
        SELECT p.* FROM offline_packs p
        JOIN offline_pack_courses pc ON pc.packId = p.id
        WHERE pc.courseId = :courseId AND p.status IN ('READY', 'STALE')
        LIMIT 1
        """
    )
    suspend fun findUsablePackForCourse(courseId: Long): OfflinePackEntity?

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM offline_packs")
    fun observeTotalBytes(): Flow<Long>

    /**
     * Best offline rank per course, 0 is ready. Kept narrow on purpose, Home
     * stuttered during downloads when it observed full region rows.
     * Must match [com.coursemapper.offline.OfflineMapRepository.rank].
     */
    @Query(
        """
        SELECT pc.courseId AS courseId, MIN(
            CASE p.status
                WHEN 'READY' THEN 0
                WHEN 'STALE' THEN 1
                WHEN 'DOWNLOADING' THEN 2
                WHEN 'QUEUED' THEN 3
                WHEN 'PAUSED' THEN 4
                ELSE 5
            END
        ) AS offlineRank
        FROM offline_pack_courses pc
        JOIN offline_packs p ON p.id = pc.packId
        GROUP BY pc.courseId
        """
    )
    fun observeCourseOfflineRanks(): Flow<List<CourseOfflineRank>>

    @Insert
    suspend fun insertPack(pack: OfflinePackEntity): Long

    @Update
    suspend fun updatePack(pack: OfflinePackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegion(region: OfflinePackRegionEntity): Long

    @Update
    suspend fun updateRegion(region: OfflinePackRegionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCourseLinks(links: List<OfflinePackCourseEntity>)

    @Query("DELETE FROM offline_pack_courses WHERE courseId = :courseId")
    suspend fun deleteCourseLinks(courseId: Long)

    @Query("UPDATE offline_pack_regions SET maplibreRegionId = :rid WHERE id = :id")
    suspend fun setMaplibreRegionId(id: Long, rid: Long)

    /** All status writes go through here so `updatedAt` is always set. */
    @Query(
        """
        UPDATE offline_packs
        SET status = :status, failureReason = :reason, failureMessage = :message,
            updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun setStatus(
        id: Long,
        status: OfflinePackStatus,
        reason: OfflineFailureReason?,
        message: String?,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE offline_packs
        SET status = 'READY', downloadedAt = :now, updatedAt = :now,
            sizeBytes = :sizeBytes, completedResourceCount = :completed,
            requiredResourceCount = :required,
            failureReason = NULL, failureMessage = NULL
        WHERE id = :id
        """
    )
    suspend fun markReady(
        id: Long,
        sizeBytes: Long,
        completed: Long,
        required: Long,
        now: Long = System.currentTimeMillis()
    )

    /** Throttled progress checkpoint - see [OfflinePackEntity]. */
    @Query(
        """
        UPDATE offline_packs
        SET sizeBytes = :sizeBytes, completedResourceCount = :completed,
            requiredResourceCount = :required, updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun checkpointProgress(
        id: Long,
        sizeBytes: Long,
        completed: Long,
        required: Long,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE offline_packs SET userChoice = :choice, updatedAt = :now WHERE id = :id")
    suspend fun setUserChoice(
        id: Long,
        choice: OfflinePackChoice,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE offline_pack_regions SET status = :status, sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun setRegionStatus(id: Long, status: OfflinePackStatus, sizeBytes: Long)

    /** Packs complete before [before] - candidates for STALE. */
    @Query("SELECT * FROM offline_packs WHERE status = 'READY' AND downloadedAt IS NOT NULL AND downloadedAt < :before")
    suspend fun findReadyOlderThan(before: Long): List<OfflinePackEntity>

    /** Cascades to regions and course links via their foreign keys. */
    @Query("DELETE FROM offline_packs WHERE id = :id")
    suspend fun deletePack(id: Long)
}

@Dao
interface RouteNetworkDao {
    @Query("SELECT * FROM route_networks WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<RouteNetworkEntity>>

    @Query("SELECT * FROM route_networks WHERE id = :id AND isDeleted = 0")
    suspend fun getById(id: Long): RouteNetworkEntity?

    @Query("SELECT * FROM route_segments WHERE networkId = :networkId AND isDeleted = 0 ORDER BY orderIndex ASC")
    suspend fun getSegments(networkId: Long): List<RouteSegmentEntity>

    @Query("SELECT * FROM route_junctions WHERE networkId = :networkId")
    suspend fun getJunctions(networkId: Long): List<RouteJunctionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNetwork(network: RouteNetworkEntity): Long

    @Update
    suspend fun updateNetwork(network: RouteNetworkEntity)

    @Query("UPDATE route_networks SET isDeleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDeleteNetwork(id: Long, now: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: RouteSegmentEntity): Long

    @Update
    suspend fun updateSegment(segment: RouteSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJunction(junction: RouteJunctionEntity): Long
}

@Dao
interface RouteVariantDao {
    @Query("SELECT * FROM route_variants WHERE networkId = :networkId AND isDeleted = 0 ORDER BY createdAt ASC")
    fun observeByNetwork(networkId: Long): Flow<List<RouteVariantEntity>>

    @Query("SELECT * FROM route_variants WHERE networkId = :networkId AND isDeleted = 0 ORDER BY createdAt ASC")
    suspend fun getByNetwork(networkId: Long): List<RouteVariantEntity>

    @Query("SELECT * FROM route_variants WHERE id = :id AND isDeleted = 0")
    suspend fun getById(id: Long): RouteVariantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(variant: RouteVariantEntity): Long

    @Update
    suspend fun update(variant: RouteVariantEntity)

    @Query("UPDATE route_variants SET isDeleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())
}
