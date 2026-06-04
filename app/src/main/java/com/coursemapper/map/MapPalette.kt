package com.coursemapper.map

/**
 * Hex colours used on the map. Named by meaning, each one notes the colour it
 * resolves to.
 */
object MapPalette {
    /** Blue (#2962FF) - trunk course route polyline and current-position dot. */
    const val ROUTE = "#2962FF"

    /** Teal (#00897B) - variant / sub-course route polyline. */
    const val ROUTE_VARIANT = "#00897B"

    /** Green (#00C853) - approach line from current position to next target. */
    const val APPROACH = "#00C853"

    /** Blue (#2962FF) - heading / breadcrumb trail (same as [ROUTE]). */
    const val HEADING = "#2962FF"

    /** Orange (#FF6D00) - distance / checkpoint marker dots and labels. */
    const val MARKER = "#FF6D00"

    /** Red (#D32F2F) - active placement-stop target highlight circle. */
    const val ACTIVE_TARGET = "#D32F2F"

    /** Deep purple (#7C4DFF) - branch segment polyline. */
    const val BRANCH = "#7C4DFF"

    /** Amber (#FFB300) - junction split/rejoin pin dots. */
    const val JUNCTION = "#FFB300"

    /**
     * Colours dealt to courses shown together, in order. Same list everywhere so
     * a course keeps its colour across screens. Also the ribbon colours, so they
     * need to be distinct from each other.
     */
    val COURSE_CYCLE = listOf(ROUTE, ROUTE_VARIANT, BRANCH, JUNCTION)

    /** Grey (#78909C) - shared corridor at overview zoom, where the chunks would be noise. */
    const val SHARED_CORRIDOR = "#78909C"

    /** Dark grey (#9E9E9E) - marker in "done / passed" state. */
    const val MARKER_DONE = "#9E9E9E"

    /** Orange (#FF6D00) - trunk slice preview between junction pins. */
    const val BRANCH_HIGHLIGHT = "#FF6D00"

    /** Green (#4CAF50) - placement stop completed in the current ride. */
    const val COMPLETED_STOP = "#4CAF50"

    /** Grey (#9E9E9E) - placement stop skipped in the current ride. */
    const val SKIPPED_STOP = "#9E9E9E"

    /** Red (#F44336) - disconnection gap indicator in the variant builder. */
    const val DISCONNECTION = "#F44336"

    // dark basemap roads, see DarkMapStyleTuning. Minor ~2.2:1, major ~3.4:1,
    // motorway ~4.8:1 against the background, casings stay dark.

    /** Cool grey (#4A4F57) - dark-mode footpaths, tracks, and trails. */
    const val DARK_ROAD_PATH = "#4A4F57"

    /** Cool grey (#454A52) - dark-mode residential / minor roads. */
    const val DARK_ROAD_MINOR = "#454A52"

    /** Light cool grey (#61666F) - dark-mode primary / secondary roads. */
    const val DARK_ROAD_MAJOR = "#61666F"

    /** Bright cool grey (#787F89) - dark-mode motorways. */
    const val DARK_ROAD_MOTORWAY = "#787F89"

    /** Mid cool grey (#565C65) - dark-mode motorways at low zoom. */
    const val DARK_ROAD_MOTORWAY_SUBTLE = "#565C65"

    /** Dark cool grey (#2B2F36) - dark-mode major-road casing. */
    const val DARK_ROAD_CASING_MAJOR = "#2B2F36"

    /** Dark cool grey (#343941) - dark-mode motorway casing. */
    const val DARK_ROAD_CASING_MOTORWAY = "#343941"

    /** Pale grey (#B6BAC1) - dark-mode street-name labels. */
    const val DARK_ROAD_LABEL = "#B6BAC1"

    /** Near-white (#C8CCD3) - dark-mode motorway-name labels. */
    const val DARK_ROAD_LABEL_MOTORWAY = "#C8CCD3"

    // dark basemap water, blue since lakes and coast are good landmarks

    /** Dark slate blue (#24405A) - dark-mode water bodies (~1.8:1 on land). */
    const val DARK_WATER = "#24405A"

    /** Slate blue (#35597A) - dark-mode rivers and streams; brighter than
     *  [DARK_WATER] because waterways are thin lines, not fills. */
    const val DARK_WATERWAY = "#35597A"

    /** Pale blue (#93B4D1) - dark-mode water-body labels. */
    const val DARK_WATER_LABEL = "#93B4D1"

    /** Deep navy (#101A24) - halo behind [DARK_WATER_LABEL]. */
    const val DARK_WATER_LABEL_HALO = "#101A24"
}
