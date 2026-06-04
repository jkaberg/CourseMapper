package com.coursemapper.map

/** Map callbacks to the owning screen, translated from native events by [CourseMapView]. */
data class MapInteraction(
    /** Called when the user taps a point on the map. Coordinates are WGS-84. */
    val onMapTap: ((lat: Double, lon: Double) -> Unit)? = null,

    /** User started a pan or zoom, breaks follow mode. */
    val onUserGesture: (() -> Unit)? = null,
)
