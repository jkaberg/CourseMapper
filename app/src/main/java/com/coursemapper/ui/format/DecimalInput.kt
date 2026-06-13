package com.coursemapper.ui.format

/**
 * Parse a typed distance. Accepts both `.` and `,` since `KeyboardType.Decimal`
 * gives a comma on Norwegian and German phones. Null if it isn't a plain number.
 */
fun String.toDistanceInputOrNull(): Double? =
    trim().replace(',', '.').toDoubleOrNull()
