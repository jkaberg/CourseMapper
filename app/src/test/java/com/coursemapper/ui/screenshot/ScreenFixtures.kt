package com.coursemapper.ui.screenshot

import com.coursemapper.domain.model.ComposedCourse
import com.coursemapper.domain.model.CourseGroup
import com.coursemapper.domain.model.CourseStatus
import com.coursemapper.domain.model.DistanceMarker
import com.coursemapper.domain.model.GeoBounds
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.MarkerPresetSnapshot
import com.coursemapper.domain.model.MarkerRule
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.OfflineFailureReason
import com.coursemapper.domain.model.OfflineMapPack
import com.coursemapper.domain.model.OfflinePackChoice
import com.coursemapper.domain.model.OfflinePackProgress
import com.coursemapper.domain.model.OfflinePackStatus
import com.coursemapper.domain.model.RunSign
import com.coursemapper.map.MapPalette
import com.coursemapper.offline.PackWithProgress
import com.coursemapper.ui.home.GroupRowInfo
import com.coursemapper.ui.run.PackingStop

/**
 * Fixed fixtures for screenshots. Constant timestamps (no "2 minutes ago"),
 * and the same Trondheim event as the GPX fixtures.
 */
object ScreenFixtures {

    /** 2026-03-01T09:00:00Z. Any fixed instant; only stability matters. */
    const val FIXED_TIME = 1_772_355_600_000L

    val presetSnapshot = MarkerPresetSnapshot(
        sourcePresetId = 1L,
        rules = listOf(MarkerRule(MarkerType.DISTANCE, intervalMetres = 1_000.0))
    )

    fun course(
        id: Long,
        name: String,
        totalMetres: Double,
        lapCount: Int = 1,
        isDraft: Boolean = false
    ) = ComposedCourse(
        id = id,
        baseRouteId = id,
        name = name,
        notes = "",
        createdAt = FIXED_TIME,
        updatedAt = FIXED_TIME,
        lapCount = lapCount,
        finalLapDistanceMetres = totalMetres / lapCount,
        totalDistanceMetres = totalMetres,
        presetSnapshot = presetSnapshot,
        isDraft = isDraft
    )

    /** The four-race event, in the order the palette deals colours. */
    val marathon = course(1L, "Marathon", 42_195.0)
    val halfMarathon = course(2L, "Half Marathon", 21_097.5)
    val tenKm = course(3L, "10 km", 10_000.0)
    val fiveKm = course(4L, "5 km", 5_000.0, isDraft = true)

    val allCourses = listOf(marathon, halfMarathon, tenKm, fiveKm)

    /** One of every status, so a badge change cannot hide in an unused branch. */
    val courseStatuses = mapOf(
        marathon.id to CourseStatus.OFFLINE_READY,
        halfMarathon.id to CourseStatus.PUBLISHED,
        tenKm.id to CourseStatus.NEEDS_FIELD_PREP,
        fiveKm.id to CourseStatus.DRAFT
    )

    val trondheimEvent = CourseGroup(
        id = 10L,
        name = "Trondheim Marathon",
        createdAt = FIXED_TIME,
        updatedAt = FIXED_TIME,
        courseIds = allCourses.map { it.id }
    )

    val eventRowInfo = GroupRowInfo(
        memberNames = allCourses.map { it.name },
        memberColorsHex = MapPalette.COURSE_CYCLE,
        worstStatus = CourseStatus.DRAFT,
        worstCourseName = fiveKm.name
    )

    fun marker(index: Int, metres: Double, type: MarkerType = MarkerType.DISTANCE) =
        DistanceMarker(
            id = index.toLong(),
            courseId = marathon.id,
            sequenceIndex = index,
            lat = 63.4305 + index * 0.001,
            lon = 10.3951 + index * 0.001,
            cumulativeDistanceMetres = metres,
            type = type,
            label = if (type == MarkerType.DISTANCE) "${(metres / 1000).toInt()} km" else type.name,
            isManuallyMoved = index == 3
        )

    val markers = listOf(
        marker(1, 0.0, MarkerType.START),
        marker(2, 1_000.0),
        marker(3, 2_000.0),
        marker(4, 3_000.0),
        marker(5, 5_000.0, MarkerType.FINISH)
    )

    val presets = listOf(
        MarkerPreset(
            id = 1L,
            name = "Every kilometre",
            rules = listOf(
                MarkerRule(MarkerType.START),
                MarkerRule(MarkerType.DISTANCE, intervalMetres = 1_000.0),
                MarkerRule(MarkerType.FINISH)
            ),
            createdAt = FIXED_TIME,
            updatedAt = FIXED_TIME
        ),
        MarkerPreset(
            id = 2L,
            name = "Water stations only",
            rules = listOf(
                MarkerRule(
                    MarkerType.CUSTOM_DISTANCES,
                    customDistancesMetres = listOf(5_000.0, 10_000.0, 21_097.5)
                )
            ),
            createdAt = FIXED_TIME,
            updatedAt = FIXED_TIME
        )
    )

    private val trondheimBounds = GeoBounds(
        south = 63.38, north = 63.46, west = 10.31, east = 10.48
    )

    fun pack(
        id: Long,
        label: String,
        status: OfflinePackStatus,
        sizeBytes: Long = 48L * 1024 * 1024,
        failureReason: OfflineFailureReason? = null,
        failureMessage: String? = null
    ) = OfflineMapPack(
        id = id,
        label = label,
        bounds = trondheimBounds,
        status = status,
        failureReason = failureReason,
        failureMessage = failureMessage,
        userChoice = OfflinePackChoice.ACCEPTED,
        estimatedBytes = 52L * 1024 * 1024,
        sizeBytes = sizeBytes,
        createdAt = FIXED_TIME,
        downloadedAt = if (status == OfflinePackStatus.READY) FIXED_TIME else null,
        updatedAt = FIXED_TIME,
        courseIds = listOf(marathon.id)
    )

    /** One pack per status the screen draws. */
    val packs = listOf(
        PackWithProgress(pack(1L, "Trondheim Marathon", OfflinePackStatus.READY), null),
        PackWithProgress(
            pack(2L, "Half Marathon", OfflinePackStatus.DOWNLOADING, sizeBytes = 0),
            OfflinePackProgress(
                packId = 2L,
                completedResources = 4_120,
                requiredResources = 9_400,
                isRequiredCountPrecise = true,
                completedBytes = 21L * 1024 * 1024
            )
        ),
        PackWithProgress(pack(3L, "10 km", OfflinePackStatus.PAUSED, sizeBytes = 12L * 1024 * 1024), null),
        PackWithProgress(
            pack(
                4L, "5 km", OfflinePackStatus.FAILED, sizeBytes = 0,
                failureReason = OfflineFailureReason.NETWORK,
                failureMessage = "Connection lost after 12 MB"
            ),
            null
        )
    )

    val packingStops = listOf(
        PackingStop(
            visitNumber = 1,
            signs = listOf(
                RunSign("Marathon", "5 km"),
                RunSign("Half Marathon", "5 km"),
                RunSign("10 km", "5 km")
            )
        ),
        PackingStop(
            visitNumber = 2,
            signs = listOf(RunSign("Marathon", "10 km"), RunSign("Half Marathon", "10 km"))
        ),
        PackingStop(visitNumber = 3, signs = listOf(RunSign("Marathon", "15 km")))
    )
}
