package com.coursemapper.domain.model

import org.json.JSONObject

/*
 * A pack is one downloadable area covering one or more courses - an event's
 * courses overlap almost entirely, so per-course packs would download the same
 * tiles several times. Each pack has one MapLibre region per [MapStyleVariant].
 */

/** Pack (and region) lifecycle. [PAUSED] isn't [FAILED], leaving wifi doesn't corrupt anything. */
enum class OfflinePackStatus {
    /** Accepted, waiting for the worker (network constraint, queue position). */
    QUEUED,

    /** Actively downloading; live progress comes from the download registry. */
    DOWNLOADING,

    /** Stopped part-way and resumable - network lost, or the user paused it. */
    PAUSED,

    /** Every resource is on disk. */
    READY,

    /** Terminal for this attempt; [OfflineMapPack.failureReason] says why. */
    FAILED,

    /** Complete, but old enough that the underlying map data has likely moved. */
    STALE;

    val isUsable: Boolean get() = this == READY || this == STALE
    val isInFlight: Boolean get() = this == QUEUED || this == DOWNLOADING
}

/** Why a pack stopped, separate from the message so the UI can decide on retry. */
enum class OfflineFailureReason {
    /** Connection dropped or the tile server was unreachable. Retry is sensible. */
    NETWORK,

    /** MapLibre's tile-count ceiling was hit. Retrying unchanged will fail again. */
    TILE_LIMIT,

    /** Not enough free space on the device. */
    DISK,

    /** The route had no usable geometry to plan a region from. */
    NO_GEOMETRY,

    /** The MapLibre region backing this pack is gone. */
    MISSING_TILES,

    UNKNOWN;

    /** True when simply trying again could plausibly succeed. */
    val isRetryable: Boolean get() = this != TILE_LIMIT && this != NO_GEOMETRY
}

/** Whether the user has been asked about this pack, and what they said. */
enum class OfflinePackChoice {
    /** Never offered, or offered and dismissed without an answer. */
    UNSET,

    /** The user asked for this pack. */
    ACCEPTED,

    /** User said no. No more prompts, can still be started from the gate or Map Storage. */
    DECLINED
}

/** How the app decides whether to download a pack for a newly created course. */
enum class OfflineDownloadPolicy {
    /** Offer it once per creation batch and remember the answer. Default. */
    ASK,

    /** Download without asking, at course-creation time. */
    ALWAYS,

    /** Never offer; downloads only ever start from an explicit user action. */
    NEVER;

    companion object {
        fun fromKey(key: String?): OfflineDownloadPolicy =
            entries.firstOrNull { it.name == key } ?: ASK
    }
}

/** Basemap styles, one region each so a theme change offline doesn't blank the map. */
enum class MapStyleVariant(val key: String) {
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String): MapStyleVariant =
            entries.firstOrNull { it.key == key } ?: LIGHT
    }
}

/**
 * Zoom window for every pack.
 *
 * [MIN_ZOOM] is far out since browse mode fits whole runs and follow mode goes
 * down to [com.coursemapper.domain.NavigationZoomPolicy.MIN_ZOOM] at speed. Cheap.
 * [MAX_ZOOM] is the vector tile ceiling, beyond that tiles overzoom.
 */
object OfflineZoom {
    const val MIN_ZOOM = 5.0
    const val MAX_ZOOM = 16.0
}

/** Rectangular area. */
data class GeoBounds(
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double
) {
    /** True if this fully contains [other]. */
    fun contains(other: GeoBounds): Boolean =
        south <= other.south && north >= other.north &&
        west <= other.west && east >= other.east

    /** True if this overlaps [other] at all. */
    fun overlaps(other: GeoBounds): Boolean =
        south < other.north && north > other.south &&
        west < other.east && east > other.west

    /** The smallest bounds containing both this and [other]. */
    fun union(other: GeoBounds): GeoBounds = GeoBounds(
        south = minOf(south, other.south),
        north = maxOf(north, other.north),
        west = minOf(west, other.west),
        east = maxOf(east, other.east)
    )

    fun toJson(): String =
        """{"south":$south,"north":$north,"west":$west,"east":$east}"""

    companion object {
        /** Parse [json], or null if it is malformed or missing a side. */
        fun fromJsonOrNull(json: String): GeoBounds? = try {
            val o = JSONObject(json)
            GeoBounds(
                south = o.getDouble("south"),
                north = o.getDouble("north"),
                west = o.getDouble("west"),
                east = o.getDouble("east")
            )
        } catch (_: Exception) {
            null
        }

        /** The union of [all], or null when empty. */
        fun unionOf(all: List<GeoBounds>): GeoBounds? =
            all.reduceOrNull { acc, b -> acc.union(b) }
    }
}

/** A pack. [estimatedBytes] is what the prompt showed, [sizeBytes] is what's on disk. */
data class OfflineMapPack(
    val id: Long,
    val label: String,
    val bounds: GeoBounds,
    val status: OfflinePackStatus,
    val failureReason: OfflineFailureReason?,
    val failureMessage: String?,
    val userChoice: OfflinePackChoice,
    val estimatedBytes: Long,
    val sizeBytes: Long,
    val createdAt: Long,
    val downloadedAt: Long?,
    val updatedAt: Long,
    /** Courses this pack covers, excluding deleted ones. */
    val courseIds: List<Long>
) {
    /** Bytes to report in the UI - measured once we have them, else the estimate. */
    val displayBytes: Long get() = if (sizeBytes > 0) sizeBytes else estimatedBytes
}

/**
 * Live progress, kept in memory by [com.coursemapper.offline.OfflineDownloadRegistry]
 * rather than Room since MapLibre reports per tile.
 */
data class OfflinePackProgress(
    val packId: Long,
    val completedResources: Long,
    val requiredResources: Long,
    val isRequiredCountPrecise: Boolean,
    val completedBytes: Long
) {
    /**
     * 0..1, or null while unknown. `requiredResourceCount` grows until MapLibre
     * says it's precise, so an early fraction would go backwards.
     */
    val fraction: Float?
        get() = when {
            !isRequiredCountPrecise -> null
            requiredResources <= 0L -> null
            else -> (completedResources.toDouble() / requiredResources).coerceIn(0.0, 1.0).toFloat()
        }
}
