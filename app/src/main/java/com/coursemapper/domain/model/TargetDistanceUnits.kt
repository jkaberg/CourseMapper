package com.coursemapper.domain.model

/**
 * Target distances travel as whole centimetres (a `Long` nav argument, and a
 * half marathon is 21 097.5 m). Keep the conversion in one place, the preview
 * and the repository once disagreed by a factor of 1000.
 */

/** Centimetres in a metre.  Named so the conversions below cannot be mistyped. */
private const val CENTIMETRES_PER_METRE = 100.0

/** Centimetres to metres. Zero or negative means no target and gives 0.0. */
fun Long.targetCentimetresToMetres(): Double =
    if (this <= 0L) 0.0 else this / CENTIMETRES_PER_METRE

/** A target distance in metres, as the centimetres the pipeline stores. */
fun Double.targetMetresToCentimetres(): Long =
    if (this <= 0.0) 0L else (this * CENTIMETRES_PER_METRE).toLong()
