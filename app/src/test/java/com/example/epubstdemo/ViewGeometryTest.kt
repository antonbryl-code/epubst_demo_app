package com.example.epubstdemo

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewGeometryTest {
    @Test
    fun convertsPixelsToPoints() {
        // 1080 px at 2.625x is 411.43 dp, rounded to 411 dp = 411 CSS px = 308.25 pt.
        assertEquals(308.25f, ViewGeometry.widthPt(1080, 2.625f), 0f)
        assertEquals(300f, ViewGeometry.widthPt(400, 1f), 0f)
    }

    @Test
    fun ignoresSubDpChanges() {
        // 1079 px is 411.05 dp, still 411 dp.
        assertEquals(ViewGeometry.widthPt(1080, 2.625f), ViewGeometry.widthPt(1079, 2.625f), 0f)
    }
}
