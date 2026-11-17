package com.droiddeck.launcher.input

import org.junit.Assert.*
import org.junit.Test

class InputEditorMenuTest {
    private val w = 2400f
    private val h = 1080f
    private val bw = 1400f
    private val bh = 120f
    private val margin = 20f

    private fun covers(at: Pair<Float, Float>, b: SurfaceBounds) =
        at.first < b.right * w && at.first + bw > b.left * w && at.second < b.bottom * h && at.second + bh > b.top * h

    @Test fun theMenuDocksOnTheEdgeFartherFromTheSelection() {
        val low = SurfaceBounds(.4f, .7f, .55f, .9f)
        val high = SurfaceBounds(.4f, .05f, .55f, .25f)
        assertEquals(margin, dockSurfaceMenu(w, h, bw, bh, margin, listOf(low), low, top = false).second, 0f)
        assertEquals(h - bh - margin, dockSurfaceMenu(w, h, bw, bh, margin, listOf(high), high, top = true).second, 0f)
        for (selection in listOf(low, high)) assertFalse(covers(dockSurfaceMenu(w, h, bw, bh, margin, listOf(selection), selection, true), selection))
    }

    @Test fun withoutASelectionTheMenuKeepsItsEdgeAndCentres() {
        val top = dockSurfaceMenu(w, h, bw, bh, margin, emptyList(), null, top = true)
        assertEquals((w - bw) / 2 to margin, top)
        assertEquals(h - bh - margin, dockSurfaceMenu(w, h, bw, bh, margin, emptyList(), null, top = false).second, 0f)
        assertTrue(menuOnTop(top.second, bh, h))
    }

    @Test fun alongItsEdgeTheMenuMovesOffOtherControls() {
        val centre = SurfaceBounds(.45f, 0f, .55f, .1f)
        val at = dockSurfaceMenu(w, h, 800f, bh, margin, listOf(centre), null, top = true)
        assertTrue(at.first + 800f <= centre.left * w || at.first >= centre.right * w)
        assertEquals(margin, at.second, 0f)
    }

    @Test fun aSelectionThatFillsAnEdgePushesTheMenuToTheOtherOne() {
        val tall = SurfaceBounds(.3f, 0f, .7f, .5f)
        val at = dockSurfaceMenu(w, h, bw, bh, margin, listOf(tall), tall, top = true)
        assertEquals(h - bh - margin, at.second, 0f)
    }

    @Test fun slidersStepByFive() {
        assertEquals(65, stepPercent(63f, 20..100, 5))
        assertEquals(60, stepPercent(62.4f, 20..100, 5))
        assertEquals(20, stepPercent(3f, 20..100, 5))
        assertEquals(100, stepPercent(140f, 20..100, 5))
        val range = selectionSizeRange(118)
        assertEquals(40..250, range)
        assertEquals(120, stepPercent(118f, range, 5))
        assertEquals(35..250, selectionSizeRange(37))
        assertEquals(40..255, selectionSizeRange(253))
        for (raw in 0..300) assertEquals(0, stepPercent(raw.toFloat(), selectionSizeRange(37), 5) % 5)
    }
}
