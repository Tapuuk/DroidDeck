package com.droiddeck.launcher.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import com.droiddeck.launcher.input.InputButtonShape
import com.droiddeck.launcher.input.InputControl
import com.droiddeck.launcher.input.InputElementKind
import com.droiddeck.launcher.input.InputSurfaceElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "xhdpi")
class InputSelectionOverlayTest {
    private val w = 1000f
    private val h = 500f
    private val a = InputSurfaceElement(InputControl.A, .4f, .3f, .1f, .2f, "one", buttonShape = InputButtonShape.CIRCLE)
    private val b = InputSurfaceElement(InputControl.B, .7f, .3f, .1f, .2f, "two", buttonShape = InputButtonShape.CIRCLE)

    private fun editor(vararg elements: InputSurfaceElement): InputSurfaceEditorView {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        return InputSurfaceEditorView(activity).apply {
            this.elements = elements.toList()
            snap = false
            measure(View.MeasureSpec.makeMeasureSpec(w.toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h.toInt(), View.MeasureSpec.EXACTLY))
            layout(0, 0, w.toInt(), h.toInt())
        }
    }

    private val InputSurfaceEditorView.density get() = resources.displayMetrics.density

    private fun InputSurfaceEditorView.touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try { onTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun handlesGrabWithin44dpAndTheBadgeKeepsItsOwnCorner() {
        val view = editor(a)
        assertEquals(-1, view.handleAt(450f, 200f))
        view.selection = listOf("one")
        val d = view.density
        val diagonal = (50f + 3 * d) * .70710677f
        val tlX = 450f - diagonal; val tlY = 200f - diagonal
        assertEquals(0, view.handleAt(tlX - 15.5f * d, tlY - 15.5f * d))
        assertEquals(-1, view.handleAt(tlX - 16f * d, tlY - 16f * d))
        assertEquals(1, view.handleAt(450f - diagonal - 20 * d, 200f + diagonal))
        assertEquals(2, view.handleAt(450f + diagonal, 200f + diagonal + 21 * d))
        assertEquals(-1, view.handleAt(450f + diagonal, 200f - diagonal))
        assertEquals(-1, view.handleAt(450f, 200f))

        val (bx, by) = view.badgeCenter()!!
        assertEquals(450f + diagonal, bx, .01f)
        assertEquals(200f - diagonal, by, .01f)
        assertTrue(view.hitsBadge(bx + 21 * d, by))
        assertTrue(view.hitsBadge(bx - 15 * d, by + 15 * d))
        assertFalse(view.hitsBadge(bx, by - 23 * d))
        assertEquals(-1, view.handleAt(bx, by))
    }

    @Test fun theBadgeSitsOnTheOutlineCornerOfARoundedRectangle() {
        val pill = InputSurfaceElement(InputControl.LB, .2f, .4f, .2f, .2f, "pill", buttonShape = InputButtonShape.ROUNDED)
        val view = editor(pill)
        view.selection = listOf("pill")
        val d = view.density
        val corner = 23 * d
        val (bx, by) = view.badgeCenter()!!
        assertEquals(400f + 3 * d - corner * (1 - .70710677f), bx, .01f)
        assertEquals(200f - 3 * d + corner * (1 - .70710677f), by, .01f)
    }

    @Test fun theBadgeTakesTheNextCornerWhenAControlOrTheEdgeIsInTheWay() {
        val d = 2f
        val diagonal = (50f + 3 * d) * .70710677f
        val neighbour = InputSurfaceElement(InputControl.B, .48f, .2f, .06f, .12f, "two", buttonShape = InputButtonShape.CIRCLE)
        val view = editor(a, neighbour)
        assertEquals(d, view.density, 0f)
        view.selection = listOf("one")
        assertNear(450f - diagonal to 200f - diagonal, view.badgeCenter()!!)
        assertEquals(-1, view.handleAt(450f - diagonal, 200f - diagonal))
        assertEquals(2, view.handleAt(450f + diagonal, 200f - diagonal))

        val edge = InputSurfaceElement(InputControl.A, .9f, 0f, .1f, .2f, "edge", buttonShape = InputButtonShape.CIRCLE)
        val cornered = editor(edge)
        cornered.selection = listOf("edge")
        assertNear(950f - diagonal to 50f + diagonal, cornered.badgeCenter()!!)
    }

    private fun assertNear(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, .01f)
        assertEquals(expected.second, actual.second, .01f)
    }

    @Test fun draggingAHandleResizesFromTheOppositeCorner() {
        val view = editor(a)
        view.selection = listOf("one")
        val changes = mutableListOf<List<InputSurfaceElement>>()
        view.onChange = { changes += it }
        val d = view.density
        val diagonal = (50f + 3 * d) * .70710677f
        val x = 450f + diagonal + 18 * d; val y = 200f + diagonal
        view.touch(MotionEvent.ACTION_DOWN, x, y)
        view.touch(MotionEvent.ACTION_MOVE, x + 50f, y + 50f)
        view.touch(MotionEvent.ACTION_UP, x + 50f, y + 50f)
        val resized = changes.last().single()
        assertEquals(a.x, resized.x, .0001f)
        assertEquals(a.y, resized.y, .0001f)
        assertTrue(resized.width > a.width)
        assertTrue(resized.height > a.height)
    }

    @Test fun overlapFollowsTheDrawnShapes() {
        val circle = { cx: Float, cy: Float, r: Float -> OutlineShape().set(cx - r, cy - r, cx + r, cy + r, r) }
        assertTrue(circle(0f, 0f, 50f).overlaps(circle(90f, 0f, 50f)))
        assertFalse(circle(0f, 0f, 50f).overlaps(circle(100f, 0f, 50f)))
        // Boxes overlap at the corners but the circles do not.
        assertFalse(circle(0f, 0f, 50f).overlaps(circle(75f, 75f, 50f)))
        assertTrue(circle(0f, 0f, 50f).overlaps(circle(65f, 65f, 50f)))
        val square = { l: Float, t: Float -> OutlineShape().set(l, t, l + 100f, t + 100f, 0f) }
        assertFalse(square(0f, 0f).overlaps(square(100f, 0f)))
        assertTrue(square(0f, 0f).overlaps(square(99f, 99f)))
        val pill = OutlineShape().set(0f, 0f, 200f, 60f, 30f)
        assertFalse(pill.overlaps(circle(220f, 80f, 20f)))
        assertTrue(pill.overlaps(circle(150f, 80f, 25f)))
    }

    @Test fun movingOntoAnotherControlFlagsItUntilTheDragEnds() {
        val view = editor(a, b)
        view.selection = listOf("one")
        view.onChange = { changed -> val byId = changed.associateBy { it.id }; view.elements = view.elements.map { byId[it.id] ?: it } }
        view.touch(MotionEvent.ACTION_DOWN, 450f, 200f)
        view.touch(MotionEvent.ACTION_MOVE, 550f, 200f)
        assertEquals(0, view.overlapping)
        view.touch(MotionEvent.ACTION_MOVE, 700f, 200f)
        assertEquals(1, view.overlapping)
        view.touch(MotionEvent.ACTION_UP, 700f, 200f)
        assertEquals(0, view.overlapping)
    }

    @Test fun outlinesFollowHowEachControlIsDrawn() {
        val d = 2f
        val stick = InputSurfaceElement(InputControl.LS, .1f, .2f, .3f, .2f, buttonShape = InputButtonShape.CIRCLE)
        val classic = OutlineShape().ofControl(stick, width = w, height = h, density = d)
        assertEquals(400f - 50f, classic.centerX, .01f)
        assertEquals(50f, classic.corner, .01f)
        assertTrue(classic.circle)
        val panel = OutlineShape().ofControl(stick.copy(id = "added"), width = w, height = h, density = d)
        assertEquals(100f, panel.left, .01f); assertEquals(400f, panel.right, .01f); assertEquals(40f, panel.corner, .01f)
        val pad = InputSurfaceElement(InputControl.UP, .1f, .1f, .2f, .2f, kind = InputElementKind.DPAD)
        assertTrue(OutlineShape().ofControl(pad, w, h, d).circle)
        val bumper = InputSurfaceElement(InputControl.LB, .1f, .1f, .2f, .1f, buttonShape = InputButtonShape.PILL)
        assertEquals(25f, OutlineShape().ofControl(bumper, w, h, d).corner, .01f)
        assertEquals(25f, OutlineShape().ofControl(bumper.copy(id = "added"), w, h, d).corner, .01f)
    }

    @Test fun onlyTheSelectedControlGetsEditingUi() {
        val view = editor(a, b)
        val drawn = RecordingCanvas()
        view.drawOverlay(drawn)
        assertEquals(0, drawn.calls)

        view.selection = listOf("one")
        val selected = RecordingCanvas()
        view.drawOverlay(selected)
        assertTrue(selected.calls > 0)
        assertTrue(selected.outlines.isNotEmpty())
        for ((cx, cy) in selected.outlines) {
            assertEquals(450f, cx, 1f)
            assertEquals(200f, cy, 1f)
        }

        view.selection = emptyList()
        val cleared = RecordingCanvas()
        view.drawOverlay(cleared)
        assertEquals(0, cleared.calls)
    }

    private class RecordingCanvas : Canvas() {
        var calls = 0
        val outlines = mutableListOf<Pair<Float, Float>>()
        override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) {
            calls++; outlines += (left + right) / 2f to (top + bottom) / 2f
        }
        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) { calls++ }
        override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) { calls++ }
        override fun drawPath(path: Path, paint: Paint) { calls++ }
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) { calls++ }
        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) { calls++ }
        override fun drawBitmap(bitmap: android.graphics.Bitmap, left: Float, top: Float, paint: Paint?) { calls++ }
    }
}
