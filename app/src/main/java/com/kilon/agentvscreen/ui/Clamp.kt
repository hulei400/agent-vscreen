package com.kilon.agentvscreen.ui

/**
 * 1.4.2: the overlay's and full screen's placement maths clamps with these, never with coerceIn.
 *
 * coerceIn throws when its range is upside down (min > max), and placement ranges turn upside down exactly in the
 * moments nobody tests: a split-screen or landscape window shorter than the card, a picture not laid out yet
 * (width 0, so "0 .. width - 1" is "0 .. -1"), a rotation mid-drag. One throw there ended the app. These clamp to
 * the range between the two bounds whichever way round they come, and treat NaN as the low bound.
 */
internal fun Float.clampIn(a: Float, b: Float): Float {
    val lo = minOf(a, b); val hi = maxOf(a, b)
    return if (isNaN()) lo else coerceIn(lo, hi)
}

internal fun Int.clampIn(a: Int, b: Int): Int = coerceIn(minOf(a, b), maxOf(a, b))

internal fun Long.clampIn(a: Long, b: Long): Long = coerceIn(minOf(a, b), maxOf(a, b))
