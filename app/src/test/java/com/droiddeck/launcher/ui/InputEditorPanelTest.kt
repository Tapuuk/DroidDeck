package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w914dp-h411dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputEditorPanelTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private var dismissed = 0

    @Before fun reset() {
        context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("input-surfaces", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("controller", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun open(): SavedInputLayout {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.OVERLAY }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog(InputProfile.GAME, { dismissed++ }, {}, role = InputRole.PRIMARY, layoutId = layout.id,
            bindingsId = library.profile(InputProfile.GAME).binding(InputRole.PRIMARY)) } }
        return layout
    }

    private fun canvas() = compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot
    private fun panel() = compose.onNodeWithTag("input-editor-panel").fetchSemanticsNode().boundsInRoot
    private fun rect(e: InputSurfaceElement) = canvas().let { c -> Rect(e.x * c.width, e.y * c.height, (e.x + e.width) * c.width, (e.y + e.height) * c.height) }
    private fun tap(e: InputSurfaceElement) = compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(rect(e).center) }
    private fun menu() = compose.onNodeWithTag("input-editor-tools").performClick()
    private fun value(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun slide(tag: String, to: Float) = compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(to) }
    private fun SavedInputLayout.element(id: String) = elements.first { it.id == id }
    private fun deleteBadge(layout: SavedInputLayout, e: InputSurfaceElement) {
        val badge = editorBadge(context, layout, listOf(e.id), canvas())
        compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(Offset(badge.first, badge.second)) }
    }

    @Test fun theOpenMenuDocksOnTheEdgeFartherFromTheSelection() {
        val layout = open()
        val visible = layout.elements.filterNot { it.id in layout.style.removed }
        val high = visible.minBy { it.y }
        val low = visible.maxBy { it.y + it.height }
        assertEquals(40f * context.resources.displayMetrics.density, panel().width, 1f)
        tap(high)
        menu()
        val middle = canvas().center.y
        assertTrue(panel().top > middle)
        assertFalse(panel().overlaps(rect(high)))
        menu()
        tap(low)
        menu()
        assertTrue(panel().bottom < middle)
        assertFalse(panel().overlaps(rect(low)))
        assertEquals(48f * context.resources.displayMetrics.density, panel().height, 1f)
        compose.onNodeWithTag("input-editor-size").performClick()
        compose.onNodeWithTag("input-editor-scale").assertIsDisplayed()
        assertFalse(panel().overlaps(compose.onNodeWithTag("input-editor-scale").fetchSemanticsNode().boundsInRoot))
        compose.onNode(hasTestTag("input-selection-size") and hasAnyAncestor(hasTestTag("input-editor-panel"))).assertDoesNotExist()
    }

    @Test fun pillSlidersStayVisibleStepByFiveAndUndoOneDragAtATime() {
        val layout = open()
        tap(layout.element("legacy:start"))
        compose.onNodeWithTag("input-selection-size").assertIsDisplayed()
        compose.onNodeWithTag("input-selection-opacity").assertIsDisplayed()
        slide("input-selection-opacity", 63f)
        assertEquals("65%", value("input-selection-opacity"))
        slide("input-selection-size", 118f)
        assertEquals("120%", value("input-selection-size"))
        compose.onNodeWithTag("input-selection-size").assertIsDisplayed()
        compose.onNodeWithTag("input-selection-opacity").assertIsDisplayed()
        val pill = compose.onNodeWithTag("input-selection-pill").fetchSemanticsNode().boundsInRoot
        assertFalse(pill.overlaps(rect(layout.element("legacy:start"))))
        compose.onNodeWithTag("input-selection-opacity").performTouchInput {
            swipe(Offset(width * 0.42f, centerY), Offset(width * 0.62f, centerY), 300)
        }
        val dragged = value("input-selection-opacity")
        assertNotEquals("65%", dragged)
        assertEquals(0, dragged.removeSuffix("%").toInt() % 5)
        menu()
        compose.onNodeWithTag("input-editor-undo").performClick()
        assertEquals("65%", value("input-selection-opacity"))
        assertEquals("120%", value("input-selection-size"))
    }

    @Test fun saveWaitsForAChangeAndUndoRedoFollowHistory() {
        val layout = open()
        menu()
        compose.onNodeWithTag("input-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("input-editor-undo").assertIsNotEnabled()
        compose.onNodeWithTag("input-editor-redo").assertIsNotEnabled()
        menu()
        tap(layout.element("legacy:start"))
        slide("input-selection-opacity", 50f)
        menu()
        compose.onNodeWithTag("input-editor-save").assertIsEnabled()
        compose.onNodeWithTag("input-editor-undo").assertIsEnabled()
        compose.onNodeWithTag("input-editor-redo").assertIsNotEnabled()
        compose.onNodeWithTag("input-editor-undo").performClick()
        compose.onNodeWithTag("input-editor-redo").assertIsEnabled()
        compose.onNodeWithTag("input-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("input-editor-redo").performClick()
        compose.onNodeWithTag("input-editor-save").assertIsEnabled()
        compose.onNodeWithTag("input-editor-save").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_saved)).assertExists()
        assertEquals(1, dismissed)
    }

    @Test fun closingWithChangesAsksBeforeDiscarding() {
        val layout = open()
        tap(layout.element("legacy:start"))
        slide("input-selection-opacity", 50f)
        menu()
        compose.onNodeWithTag("input-editor-close").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_discard_title)).assertIsDisplayed()
        compose.onNodeWithText("You have 1 unsaved change to this layout.").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.input_editor_keep_editing)).performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_discard_title)).assertDoesNotExist()
        assertEquals(0, dismissed)
        compose.onNodeWithTag("input-editor-close").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_discard_confirm)).performClick()
        assertEquals(1, dismissed)
    }

    @Test fun deletingShowsAToastWhoseUndoBringsTheControlBack() {
        val layout = open()
        val start = layout.element("legacy:start")
        tap(start)
        deleteBadge(layout, start)
        compose.onNodeWithTag("input-selection-pill").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.input_editor_deleted, start.name)).assertIsDisplayed()
        menu()
        compose.onNodeWithTag("input-editor-save").assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.input_editor_undo)).performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_deleted, start.name)).assertDoesNotExist()
        compose.onNodeWithTag("input-editor-save").assertIsNotEnabled()
    }

    @Test fun theHintShowsWhileNothingIsSelectedThenHides() {
        compose.mainClock.autoAdvance = false
        open()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(context.getString(R.string.input_editor_tap_hint)).assertExists()
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithText(context.getString(R.string.input_editor_tap_hint)).assertDoesNotExist()
    }
}

/** Where the editor draws the delete badge for [selection] on a canvas the size of [canvas]. */
internal fun editorBadge(context: Context, layout: SavedInputLayout, selection: List<String>, canvas: Rect): Pair<Float, Float> =
    InputSurfaceEditorView(context).run {
        style = layout.style.copy(removed = emptySet())
        elements = layout.elements.filterNot { it.id in layout.style.removed }
        measure(android.view.View.MeasureSpec.makeMeasureSpec(canvas.width.toInt(), android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(canvas.height.toInt(), android.view.View.MeasureSpec.EXACTLY))
        layout(0, 0, canvas.width.toInt(), canvas.height.toInt())
        this.selection = selection
        badgeCenter()!!
    }
