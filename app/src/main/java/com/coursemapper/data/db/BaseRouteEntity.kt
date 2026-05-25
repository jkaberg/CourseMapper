package com.coursemapper.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A recorded or imported route. Points live in [RoutePointEntity], soft-deleted
 * via [isDeleted] so old data survives cleanup.
 */
@Entity(tableName = "base_routes")
data class BaseRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isApproved: Boolean = false,
    val isDeleted: Boolean = false,
    /** Cumulative distance in metres of the approved polyline. */
    val distanceMetres: Double = 0.0,
    /** Source: "recorded" | "gpx_import" */
    val source: String = "recorded"
)
