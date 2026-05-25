package com.coursemapper.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single GPS fix. [index] keeps recording order since Room doesn't.
 *
 * [latDeg]/[lonDeg] is the polyline, overwritten by `RouteSmoothingEngine` on
 * approval. [rawLatDeg]/[rawLonDeg] keeps the observation so smoothing can be
 * re-run, null for rows from before schema v12.
 */
@Entity(
    tableName = "route_points",
    foreignKeys = [ForeignKey(
        entity = BaseRouteEntity::class,
        parentColumns = ["id"],
        childColumns = ["routeId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("routeId")]
)
data class RoutePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val index: Int,
    val latDeg: Double,
    val lonDeg: Double,
    /** Untouched observation; null for rows predating schema v12. */
    val rawLatDeg: Double? = null,
    /** Untouched observation; null for rows predating schema v12. */
    val rawLonDeg: Double? = null,
    /** Metres above WGS-84 ellipsoid, null if unavailable. */
    val altMetres: Double?,
    /** Provider-reported accuracy radius in metres. */
    val accuracyMetres: Float?,
    val timestampMs: Long,
    /** Whether this point was kept in the smoothed/display polyline. */
    val isSmoothed: Boolean = true
)
