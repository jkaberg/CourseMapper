package com.coursemapper.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Makes OpenFreeMap `dark` ([STYLE_URL_DARK]) usable for navigation.
 *
 * It's a backdrop for data overlays: roads are 1.05-1.10:1 against the
 * background, motorways pure black and water the same near-black as land.
 * After every style load this repaints roads (fill carries the contrast, casing
 * is a darker edge), makes water blue, and raises the low zoom width of paths
 * and minor roads. Widths at navigation zoom are left alone, they're already
 * wider than the course line.
 *
 * Only paint and two width ramps change, the vector source is untouched so
 * offline packs are unaffected. Missing layers are skipped.
 */
internal object DarkMapStyleTuning {

    /** Style layer id -> replacement `line-color`. */
    private val LINE_COLOURS = listOf(
        "highway_path"            to MapPalette.DARK_ROAD_PATH,
        "highway_minor"           to MapPalette.DARK_ROAD_MINOR,
        "highway_major_casing"    to MapPalette.DARK_ROAD_CASING_MAJOR,
        "highway_major_inner"     to MapPalette.DARK_ROAD_MAJOR,
        "highway_major_subtle"    to MapPalette.DARK_ROAD_MINOR,
        "highway_motorway_casing" to MapPalette.DARK_ROAD_CASING_MOTORWAY,
        // Flat colour on purpose: the shipped value is a zoom interpolation
        // that resolves to #000 from z6 up, i.e. invisible at every zoom the
        // navigation camera uses.
        "highway_motorway_inner"  to MapPalette.DARK_ROAD_MOTORWAY,
        "highway_motorway_subtle" to MapPalette.DARK_ROAD_MOTORWAY_SUBTLE,
        "waterway"                to MapPalette.DARK_WATERWAY
    )

    /** Style layer id -> replacement `fill-color`. */
    private val FILL_COLOURS = listOf(
        "water" to MapPalette.DARK_WATER
    )

    /** Style layer id -> replacement `text-color`. */
    private val LABEL_COLOURS = listOf(
        "highway_name_other"    to MapPalette.DARK_ROAD_LABEL,
        "highway_name_motorway" to MapPalette.DARK_ROAD_LABEL_MOTORWAY,
        "water_name"            to MapPalette.DARK_WATER_LABEL
    )

    /** Repaint roads and water in [style]. No-op if the layers aren't there. */
    fun apply(style: Style) {
        LINE_COLOURS.forEach { (layerId, colour) ->
            (style.getLayer(layerId) as? LineLayer)
                ?.setProperties(PropertyFactory.lineColor(colour))
        }
        FILL_COLOURS.forEach { (layerId, colour) ->
            (style.getLayer(layerId) as? FillLayer)
                ?.setProperties(PropertyFactory.fillColor(colour))
        }
        LABEL_COLOURS.forEach { (layerId, colour) ->
            (style.getLayer(layerId) as? SymbolLayer)
                ?.setProperties(PropertyFactory.textColor(colour))
        }
        // The shipped water label is black text inside a mid-grey halo, which
        // on this background reads as a grey smudge; invert it to pale-on-dark.
        (style.getLayer("water_name") as? SymbolLayer)?.setProperties(
            PropertyFactory.textHaloColor(MapPalette.DARK_WATER_LABEL_HALO),
            PropertyFactory.textHaloWidth(1.2f)
        )
        applyLowZoomWidthFloors(style)
    }

    /**
     * Raise the z13 end of the path (1.0 -> 2.2 dp) and minor road (1.8 -> 3.2 dp)
     * width ramps, converging back by z20. Expressions are built per call so a
     * reload never reuses one bound to a dead layer.
     */
    private fun applyLowZoomWidthFloors(style: Style) {
        (style.getLayer("highway_path") as? LineLayer)?.setProperties(
            PropertyFactory.lineWidth(
                Expression.interpolate(
                    Expression.exponential(1.2f), Expression.zoom(),
                    Expression.stop(13f, 2.2f),
                    Expression.stop(20f, 10f)
                )
            )
        )
        (style.getLayer("highway_minor") as? LineLayer)?.setProperties(
            PropertyFactory.lineWidth(
                Expression.interpolate(
                    Expression.exponential(1.55f), Expression.zoom(),
                    Expression.stop(13f, 3.2f),
                    Expression.stop(20f, 20f)
                )
            )
        )
    }
}
