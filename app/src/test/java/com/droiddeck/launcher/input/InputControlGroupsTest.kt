package com.droiddeck.launcher.input

import org.junit.Assert.*
import org.junit.Test

class InputControlGroupsTest {
    private fun round(c: InputControl, x: Float, y: Float, size: Float = .06f) = InputSurfaceElement(c, x, y, size, size)
    private val layout = listOf(
        round(InputControl.UP, .10f, .50f), round(InputControl.RIGHT, .16f, .56f),
        round(InputControl.DOWN, .10f, .62f), round(InputControl.LEFT, .04f, .56f),
        round(InputControl.A, .80f, .62f), round(InputControl.B, .86f, .56f),
        round(InputControl.X, .74f, .56f), round(InputControl.Y, .80f, .50f),
        InputSurfaceElement(InputControl.LB, .05f, .05f, .12f, .06f), InputSurfaceElement(InputControl.LT, .05f, .12f, .12f, .06f),
        InputSurfaceElement(InputControl.RB, .83f, .05f, .12f, .06f), InputSurfaceElement(InputControl.RT, .83f, .12f, .12f, .06f),
        round(InputControl.LS, .25f, .40f, .2f), round(InputControl.START, .55f, .10f),
    )
    private val dpad = listOf("legacy:up", "legacy:right", "legacy:down", "legacy:left")

    @Test fun stockControlsFormTheirGroups() {
        assertEquals(dpad, layout.groupOf("legacy:right"))
        assertEquals(listOf("legacy:a", "legacy:b", "legacy:x", "legacy:y"), layout.groupOf("legacy:y"))
        assertEquals(listOf("legacy:lb", "legacy:lt"), layout.groupOf("legacy:lt"))
        assertEquals(listOf("legacy:rb", "legacy:rt"), layout.groupOf("legacy:rb"))
        assertEquals(listOf("legacy:ls"), layout.groupOf("legacy:ls"))
        assertEquals(listOf("legacy:start"), layout.groupOf("legacy:start"))
    }

    @Test fun addedControlsStayOutOfGroups() {
        val extra = InputSurfaceElement(InputControl.A, .4f, .4f, .1f, .1f, id = "custom")
        assertEquals(listOf("custom"), (layout + extra).groupOf("custom"))
        assertEquals(listOf("legacy:a", "legacy:b", "legacy:x", "legacy:y"), (layout + extra).groupOf("legacy:a"))
    }

    @Test fun firstTapSelectsTheGroupAndTheSecondTheButton() {
        val first = layout.tapSelection(emptyList(), "legacy:up")
        assertEquals(dpad, first)
        assertEquals(InputControlGroup.DPAD, layout.selectedGroup(first))
        val second = layout.tapSelection(first, "legacy:left")
        assertEquals(listOf("legacy:left"), second)
        assertNull(layout.selectedGroup(second))
        assertEquals(listOf("legacy:down"), layout.tapSelection(second, "legacy:down"))
        assertEquals(listOf("legacy:lb", "legacy:lt"), layout.tapSelection(second, "legacy:lt"))
        assertEquals(emptyList<String>(), layout.tapSelection(second, null))
    }

    @Test fun pressingASelectedGroupKeepsItSoItCanBeDragged() {
        assertEquals(dpad, layout.pressSelection(dpad, "legacy:up"))
    }

    @Test fun anAddedSetMovesAsOneUntilAMemberIsTapped() {
        val set = listOf("added-1", "added-2")
        val added = layout + set.map { InputSurfaceElement(InputControl.A, .4f, .4f, .1f, .1f, id = it) }
        assertEquals(set, added.pressSelection(set, "added-2"))
        assertEquals(listOf("added-2"), added.tapSelection(set, "added-2"))
        assertEquals(dpad, added.pressSelection(set, "legacy:up"))
    }

    @Test fun movingAGroupKeepsItsShapeAndStopsAtTheEdge() {
        val moved = layout.moveSelection(dpad, -.5f, .1f)
        val before = layout.boundsOf(dpad)!!
        val after = moved.boundsOf(dpad)!!
        assertEquals(0f, after.left, 1e-5f)
        assertEquals(before.top + .1f, after.top, 1e-5f)
        assertEquals(before.width, after.width, 1e-5f)
        assertEquals(layout.filter { it.id !in dpad }, moved.filter { it.id !in dpad })
    }

    @Test fun cornerResizeStretchesButtonsFreely() {
        val ids = listOf("legacy:lb", "legacy:lt")
        val b = layout.boundsOf(ids)!!
        val resized = layout.resizeSelection(ids, 2 or 8, SurfaceBounds(b.left, b.top, b.left + b.width * 2f, b.bottom))
        val lb = resized.first { it.id == "legacy:lb" }
        assertEquals(.24f, lb.width, 1e-5f)
        assertEquals(.06f, lb.height, 1e-5f)
    }

    @Test fun sticksKeepTheirProportionsWhenResized() {
        val ids = listOf("legacy:ls")
        val b = layout.boundsOf(ids)!!
        val stick = layout.resizeSelection(ids, 2 or 8, SurfaceBounds(b.left, b.top, b.right + .1f, b.bottom)).first { it.id == "legacy:ls" }
        assertEquals(.3f, stick.width, 1e-5f)
        assertEquals(.3f, stick.height, 1e-5f)
        assertEquals(b.left, stick.x, 1e-5f)
    }

    @Test fun resizingFromTheTopLeftKeepsTheOppositeCorner() {
        val ids = listOf("legacy:a", "legacy:b", "legacy:x", "legacy:y")
        val b = layout.boundsOf(ids)!!
        val after = layout.resizeSelection(ids, 1 or 4, SurfaceBounds(b.left - .1f, b.top - .05f, b.right, b.bottom)).boundsOf(ids)!!
        assertEquals(b.right, after.right, 1e-5f)
        assertEquals(b.bottom, after.bottom, 1e-5f)
        assertEquals(b.left - .1f, after.left, 1e-5f)
    }

    @Test fun shrinkingStopsAtTheMinimumButtonSize() {
        val ids = listOf("legacy:a", "legacy:b", "legacy:x", "legacy:y")
        val b = layout.boundsOf(ids)!!
        val after = layout.resizeSelection(ids, 2 or 8, SurfaceBounds(b.left, b.top, b.left + .001f, b.top + .001f))
        assertTrue(after.filter { it.id in ids }.all { it.width >= .03f - 1e-5f && it.height >= .03f - 1e-5f })
    }

    @Test fun sizeSliderScalesAboutTheCentreAndReportsThePercent() {
        val reference = layout.associateBy { it.id }
        val before = layout.boundsOf(dpad)!!
        assertEquals(100f, layout.sizePercent(dpad, reference), 1e-3f)
        val bigger = layout.scaleSelection(dpad, 1.5f)
        val after = bigger.boundsOf(dpad)!!
        assertEquals(150f, bigger.sizePercent(dpad, reference), 1e-2f)
        assertEquals(before.centerY, after.centerY, 1e-5f)
        assertTrue(after.left >= 0f)
        val up = bigger.first { it.id == "legacy:up" }
        assertEquals(.09f, up.width, 1e-5f)
    }

    @Test fun spreadingAGroupOutDoesNotChangeItsSize() {
        val reference = layout.associateBy { it.id }
        val spread = layout.moveSelection(listOf("legacy:up"), 0f, -.1f)
        assertEquals(100f, spread.sizePercent(dpad, reference), 1e-3f)
    }

    @Test fun scalingNeverLeavesTheScreen() {
        val after = layout.scaleSelection(dpad, 20f).boundsOf(dpad)!!
        assertTrue(after.left >= 0f && after.top >= 0f && after.right <= 1.00001f && after.bottom <= 1.00001f)
    }
}
