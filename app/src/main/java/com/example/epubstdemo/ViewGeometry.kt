package com.example.epubstdemo

import kotlin.math.roundToInt

/** Geometry offered to documents (see [TypstBundle.compile]), derived from the document view's size. */
object ViewGeometry {
    /** Margin suggested to documents on every side, in points. */
    const val MARGIN_PT = 12f

    /**
     * The width in points of a view [widthPx] pixels wide. A WebView with a `device-width`
     * viewport has 1 CSS px per dp, and 1 CSS px = 0.75pt (96 vs. 72 per inch). Rounding to whole
     * dp keeps sub-pixel layout jitter from triggering recompiles.
     */
    fun widthPt(widthPx: Int, density: Float): Float = (widthPx / density).roundToInt() * 0.75f
}
