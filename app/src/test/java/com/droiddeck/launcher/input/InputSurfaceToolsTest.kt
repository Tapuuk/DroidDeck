package com.droiddeck.launcher.input

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "xhdpi")
class InputSurfaceToolsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val density get() = context.resources.displayMetrics.density
    private val w = 1920f
    private val h = 1080f
    private val margin get() = 8 * density

    private fun bounds(e: InputSurfaceElement) = SurfaceBounds(e.x, e.y, e.x + e.width, e.y + e.height)

    private fun covers(at: Pair<Float, Float>, pw: Float, ph: Float, b: SurfaceBounds) =
        at.first < b.right * w && at.first + pw > b.left * w && at.second < b.bottom * h && at.second + ph > b.top * h

    @Test fun collapsedToolsSitInTheTopGapOfTheDefaultGamepad() {
        val pad = LegacyGamepadLayout(context, w.toInt(), h.toInt()).elements().map(::bounds)
        val pw = 152 * density; val ph = 52 * density
        assertTrue(pad.any { covers(.02f * w to .02f * h, pw, ph, it) })
        val at = placeSurfaceTools(w, h, pw, ph, margin, pad, null)
        assertTrue(pad.none { covers(at, pw, ph, it) })
        assertEquals(w / 2, at.first + pw / 2, 1f)
        assertEquals(margin, at.second, 0f)
    }

    @Test fun expandedToolsStayOffTheSelectedControl() {
        val pad = LegacyGamepadLayout(context, w.toInt(), h.toInt()).elements()
        val pw = 420 * density; val ph = 400 * density
        for (id in listOf("legacy:ls", "legacy:rs", "legacy:lt", "legacy:a", "legacy:up")) {
            val selected = bounds(pad.first { it.id == id })
            val at = placeSurfaceTools(w, h, pw, ph, margin, pad.map(::bounds), selected)
            assertFalse(id, covers(at, pw, ph, selected))
            assertEquals(id, selected.centerX * w < w / 2, at.first + pw / 2 > w / 2)
        }
    }

    @Test fun aDraggedPositionIsKeptUnlessItCoversTheSelection() {
        val selected = SurfaceBounds(.05f, .05f, .2f, .3f)
        val other = SurfaceBounds(.7f, .7f, .9f, .9f)
        val pw = 300f; val ph = 100f
        assertEquals(960f to 540f, placeSurfaceTools(w, h, pw, ph, margin, listOf(selected, other), selected, .5f to .5f))
        assertEquals(960f to 540f, placeSurfaceTools(w, h, pw, ph, margin, listOf(selected, other), null, .5f to .5f))
        assertEquals(w - pw - margin, placeSurfaceTools(w, h, pw, ph, margin, emptyList(), null, 2f to 0f).first, 0f)
        val moved = placeSurfaceTools(w, h, pw, ph, margin, listOf(selected, other), selected, .1f to .1f)
        assertFalse(covers(moved, pw, ph, selected))
        assertTrue(moved.first + pw / 2 < .5f * w && moved.second < .5f * h)
    }

    @Test fun anUnavoidableSelectionIsCoveredAsLittleAsPossible() {
        val selected = SurfaceBounds(.3f, 0f, .7f, 1f)
        val pw = w * .5f; val ph = h - 2 * margin
        val at = placeSurfaceTools(w, h, pw, ph, margin, listOf(selected), selected)
        assertTrue(at.first == margin || at.first == w - pw - margin)
    }

    @Test fun theExpandedPanelNarrowsToFitBesideACentredSelection() {
        assertEquals(840f, surfaceToolsWidth(w, margin, null, 560f, 840f), 0f)
        val centred = SurfaceBounds(.4f, .35f, .6f, .65f)
        val fitted = surfaceToolsWidth(w, margin, centred, 560f, 840f)
        assertEquals(.4f * w - 2 * margin, fitted, .01f)
        val at = placeSurfaceTools(w, h, fitted, h - 2 * margin, margin, listOf(centred), centred)
        assertFalse(covers(at, fitted, h - 2 * margin, centred))
        assertEquals(560f, surfaceToolsWidth(w, margin, SurfaceBounds(.2f, 0f, .8f, 1f), 560f, 840f), 0f)
    }

    @Test fun theDeleteBadgeSitsOffTheTopRightCornerAndFlipsInsideAtTheEdges() {
        val offset = 28 * density; val reach = 22 * density
        val inside = SurfaceBounds(400f, 300f, 600f, 500f)
        assertEquals(600f + offset to 300f - offset, deleteBadgeCenter(inside, w, h, offset, reach))
        val corner = SurfaceBounds(w - 150f, 10f, w - 10f, 150f)
        assertEquals(w - 10f - offset to 10f + offset, deleteBadgeCenter(corner, w, h, offset, reach))
        val center = deleteBadgeCenter(inside, w, h, offset, reach)
        assertTrue(hitsDeleteBadge(center.first + 21 * density, center.second, center, reach))
        assertTrue(hitsDeleteBadge(center.first - 15 * density, center.second + 15 * density, center, reach))
        assertFalse(hitsDeleteBadge(center.first, center.second - 23 * density, center, reach))
        assertTrue(offset * kotlin.math.sqrt(2f) - reach > 14 * density)
    }

    @Test fun theSelectionPillSitsBesideTheSelectionOnTheRoomierSide() {
        val pw = 480f; val ph = 240f; val gap = 24f
        val leftSide = SurfaceBounds(100f, 400f, 300f, 600f)
        val at = placeSelectionPill(w, h, pw, ph, margin, gap, leftSide, emptyList())
        assertEquals(300f + gap, at.first, 0f)
        val rightSide = SurfaceBounds(1500f, 400f, 1700f, 600f)
        assertEquals(1500f - gap - pw, placeSelectionPill(w, h, pw, ph, margin, gap, rightSide, emptyList()).first, 0f)
        val tools = SurfaceBounds(320f, 250f, 1200f, 620f)
        val avoided = placeSelectionPill(w, h, pw, ph, margin, gap, leftSide, listOf(tools))
        assertEquals(600f + gap, avoided.second, 0f)
        assertFalse(covers(avoided, pw, ph, SurfaceBounds(tools.left / w, tools.top / h, tools.right / w, tools.bottom / h)))
        assertFalse(covers(avoided, pw, ph, SurfaceBounds(leftSide.left / w, leftSide.top / h, leftSide.right / w, leftSide.bottom / h)))
    }
}
