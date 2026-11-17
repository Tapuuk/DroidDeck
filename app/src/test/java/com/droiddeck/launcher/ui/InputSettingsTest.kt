package com.droiddeck.launcher.ui

import android.content.Context
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.*
import com.droiddeck.launcher.input.*
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SessionService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1000dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputSettingsTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        context.getSharedPreferences("input-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("input-surfaces", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("controller", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun commandSymbolsAreSelectableForAnyCustomButton() {
        val element = mutableStateOf(InputSurfaceElement(InputControl.A, .1f, .1f, .2f, .2f, name = "Custom button"))
        compose.setContent { DroidDeckTheme {
            androidx.compose.foundation.layout.Column { InputElementProperties(element.value) { element.value = it } }
        } }
        var current = context.getString(R.string.input_button_text)
        for (code in listOf(InputButtonIcons.MENU, InputButtonIcons.KEYBOARD, InputButtonIcons.STEAM, InputButtonIcons.QUICK_ACCESS)) {
            compose.onNodeWithText(current).performClick()
            current = InputButtonIcons.label(context, code)
            compose.onNodeWithText(current).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(code, element.value.icon) }
        }
    }

    @Test fun steamAndDesktopQuickSettingsOpenTheSameInputLibrary() {
        val mode = mutableStateOf(SessionService.MODE_STEAM)
        var opened = 0
        compose.setContent { DroidDeckTheme {
            ModeSheetRows(ModeSettings(mode = mode.value, resolution = "auto", hdr = false, hdrReason = null,
                touchMode = "auto", suspendPolicy = "auto", oscMode = "auto", directAudio = null, mic = null),
                ModeSettingsActions(onResolution = {}, onHdr = {}, onTouch = {}, onSuspendPolicy = {}, onOsc = {},
                    onInputSettings = { opened++ }, onDirectAudio = {}, onMic = {}, onDismiss = {}), SheetTab.CONTROLS)
        } }
        compose.onNodeWithTag("input-profile-expand").performClick()
        compose.onNodeWithText(context.getString(R.string.ctrl_edit)).performClick()
        compose.runOnIdle { assertEquals(1, opened); mode.value = SessionService.MODE_DESKTOP }
        compose.onNodeWithText(context.getString(R.string.ctrl_edit)).performClick()
        compose.runOnIdle { assertEquals(2, opened) }
    }

    @Test fun quickMenuCombinesPartsWithoutChangingTheSavedProfile() {
        val original = InputLibraries.read(context)
        compose.setContent { DroidDeckTheme {
            androidx.compose.foundation.layout.Column { InputQuickSettings(rememberMenuHost(), desktop = true) }
        } }
        compose.onNodeWithTag("setting-input-profile").assertExists()
        compose.onNodeWithTag("setting-input-part-PHYSICAL").assertDoesNotExist()
        compose.onNodeWithTag("input-profile-expand").performClick()
        compose.onNodeWithTag("setting-input-part-PHYSICAL").assertExists()
        compose.onNodeWithTag("setting-input-part-PRIMARY").assertExists()
        compose.onNodeWithTag("setting-input-part-SECONDARY").performClick()
        compose.onNodeWithText("Deck grips + trackpads").performScrollTo().performClick()
        compose.runOnIdle {
            val changed = InputLibraries.read(context)
            assertEquals(InputStockLayouts.DECK, changed.activePart(true, InputRole.SECONDARY).bindings)
            assertEquals(original.profiles, changed.profiles)
            assertEquals(original.compileActive(false), changed.compileActive(false))
        }
        compose.onNodeWithText(context.getString(R.string.input_save_combination)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Reading")
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context)
            assertEquals("Reading", saved.profile(saved.activeId(true)).name)
            assertTrue(saved.selection(true).parts.isEmpty())
            assertEquals(original.profile("desktop"), saved.profile("desktop"))
        }
    }

    @Test fun actionDraftSurvivesRouteAndTypeChanges() {
        var saved: InputAction? = null
        compose.setContent { DroidDeckTheme { InputActionEditor(InputControl.RS, InputAction.Scroll(27f, true), "Right stick", {}, { saved = it }) } }
        compose.onNodeWithText(context.getString(R.string.input_route_controller), substring = false).performClick()
        compose.onNodeWithText(context.getString(R.string.input_disabled), substring = false).performClick()
        compose.onNodeWithText(context.getString(R.string.input_route_local), substring = false).performClick()
        compose.onNodeWithText(context.getString(R.string.input_scroll_speed, 27)).assertExists()
        compose.onNodeWithTag("setting-action-type").performClick()
        compose.onNodeWithText(context.getString(R.string.input_mouse_click)).performClick()
        compose.onNodeWithTag("setting-action-type").performClick()
        compose.onNodeWithText(context.getString(R.string.input_scroll)).performClick()
        compose.onNodeWithTag("input-action-save").performClick()
        compose.runOnIdle { assertEquals(InputAction.Scroll(27f, true), saved) }
    }

    @Test fun bindingEditorPersistsDisabledWithoutChangingTouchControls() {
        var changed = false
        compose.setContent { DroidDeckTheme { InputSettingsPage({}, { changed = true }) } }
        compose.onNodeWithTag("input-profile-game").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-physical").performScrollTo().performClick()
        compose.onNodeWithTag("input-binding-a").performScrollTo().performClick()
        compose.onNodeWithText("Disabled", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertFalse(changed) }
        compose.onNodeWithTag("input-action-save").performClick()
        compose.runOnIdle {
            assertTrue(changed)
            val profile = InputProfiles.read(context, InputProfile.GAME)
            assertEquals(InputAction.Disabled, profile.action(InputRole.PHYSICAL, InputControl.A))
            assertEquals(InputAction.Native(), profile.action(InputRole.SECONDARY, InputControl.A))
        }
    }

    @Test fun addingAControllerButtonFromTheEditorSavesIt() {
        val library = InputLibraries.read(context)
        val layout = library.layout(InputProfile.GAME, InputRole.SECONDARY)!!
        compose.setContent { DroidDeckTheme { InputSurfaceDialog(InputProfile.GAME, {}, {}, role = InputRole.PRIMARY, layoutId = layout.id,
            bindingsId = library.profile(InputProfile.GAME).binding(InputRole.SECONDARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-control-right-pad").assertExists()
        compose.onNodeWithTag("input-add-control-b").performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_properties)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performScrollTo().performClick()
        compose.runOnIdle {
            val updated = InputLibraries.read(context)
                val added = updated.layout(InputProfile.GAME, InputRole.SECONDARY)!!.elements.single { e -> layout.elements.none { it.id == e.id } }
            assertEquals(InputControl.B.label, added.name)
            assertEquals(InputAction.Native(InputControl.B), updated.compile(InputProfile.GAME).elementAction(InputRole.SECONDARY, added.id, added.control))
        }
    }

    @Test fun editorToolsStayOffTheDefaultLayoutAndTheSelectedControl() {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.OVERLAY }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog(InputProfile.GAME, {}, {}, role = InputRole.PRIMARY, layoutId = layout.id,
            bindingsId = library.profile(InputProfile.GAME).binding(InputRole.PRIMARY)) } }
        val canvas = compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot
        fun rect(e: InputSurfaceElement) = androidx.compose.ui.geometry.Rect(canvas.left + e.x * canvas.width, canvas.top + e.y * canvas.height,
            canvas.left + (e.x + e.width) * canvas.width, canvas.top + (e.y + e.height) * canvas.height)
        fun panel() = compose.onNodeWithTag("input-editor-panel").fetchSemanticsNode().boundsInRoot
        for (e in layout.elements.filterNot { it.id in layout.style.removed }) assertFalse(e.id, panel().overlaps(rect(e)))
        for (id in listOf("legacy:ls", "legacy:rs", "legacy:a", "legacy:up")) {
            val target = rect(layout.elements.first { it.id == id })
            compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(target.center - canvas.topLeft) }
            if (compose.onAllNodesWithText(context.getString(R.string.input_hide_tools)).fetchSemanticsNodes().isEmpty())
                compose.onNodeWithTag("input-editor-tools").performClick()
            compose.waitForIdle()
            assertFalse(id, panel().overlaps(target))
        }
    }

    @Test fun theSelectedControlCarriesItsDeleteBadgeAndSliders() {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.OVERLAY }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog(InputProfile.GAME, {}, {}, role = InputRole.PRIMARY, layoutId = layout.id,
            bindingsId = library.profile(InputProfile.GAME).binding(InputRole.PRIMARY)) } }
        val canvas = compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot
        val start = layout.elements.first { it.id == "legacy:start" }
        val target = androidx.compose.ui.geometry.Rect(start.x * canvas.width, start.y * canvas.height,
            (start.x + start.width) * canvas.width, (start.y + start.height) * canvas.height)
        compose.onNodeWithTag("input-selection-pill").assertDoesNotExist()
        compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(target.center) }
        compose.onNodeWithTag("input-selection-size").assertExists()
        compose.onNode(hasTestTag("input-selection-size") and hasAnyAncestor(hasTestTag("input-editor-panel"))).assertDoesNotExist()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_redo)).assertExists()
        compose.onNodeWithTag("input-remove-selection").assertDoesNotExist()
        compose.onNodeWithTag("input-editor-tools").performClick()
        val density = context.resources.displayMetrics.density
        val badge = deleteBadgeCenter(SurfaceBounds(target.left, target.top, target.right, target.bottom), canvas.width, canvas.height, 28 * density, 22 * density)
        compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(androidx.compose.ui.geometry.Offset(badge.first - 14 * density, badge.second + 14 * density)) }
        compose.onNodeWithTag("input-selection-pill").assertDoesNotExist()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("legacy:start"), InputLibraries.read(context).layouts.first { it.id == layout.id }.style.removed) }
    }

    @Test fun aNewProfileIsIndependentAndDoesNotBecomeActive() {
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithText(context.getString(R.string.input_new_profile)).performScrollTo().performClick()
        compose.onNodeWithTag("input-name").performTextInput("Minecraft")
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performClick()
        compose.runOnIdle {
            val library = InputLibraries.read(context)
            val created = library.profiles.single { it.name == "Minecraft" }
            for (role in InputRole.entries) assertNotEquals(library.profile(InputProfile.GAME).binding(role), created.binding(role))
            assertNotEquals(library.layout(InputProfile.GAME, InputRole.SECONDARY)!!.id, library.layout(created.id, InputRole.SECONDARY)!!.id)
            assertEquals(InputProfiles.AUTO, library.selected)
        }
    }

    @Test fun openingAProfileDoesNotActivateItAndBackKeepsItsContext() {
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithTag("input-profile-desktop").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-physical").performScrollTo().performClick()
        compose.onNodeWithTag("input-binding-a").performScrollTo().performClick()
        compose.onNodeWithTag("input-action-save").performClick()
        compose.onNodeWithTag("input-binding-a").assertExists()
        compose.onNodeWithText(context.getString(R.string.mode_back)).performClick()
        compose.onNodeWithTag("input-part-secondary").assertExists()
        compose.runOnIdle { assertEquals(InputProfiles.AUTO, InputLibraries.read(context).selected) }
    }

    @Test fun profileCreationCanExplicitlyShareOnlyPhysicalControls() {
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithText(context.getString(R.string.input_new_profile)).performScrollTo().performClick()
        compose.onNodeWithTag("input-name").performTextInput("Shared controls")
        compose.onNodeWithTag("input-share-physical").performClick()
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performClick()
        compose.runOnIdle {
            val library = InputLibraries.read(context)
            val created = library.profiles.single { it.name == "Shared controls" }
            assertEquals(library.profile("game").binding(InputRole.PHYSICAL), created.binding(InputRole.PHYSICAL))
            assertNotEquals(library.profile("game").binding(InputRole.SECONDARY), created.binding(InputRole.SECONDARY))
        }
    }

    @Test fun hidingEditorToolsPreservesPlacedElements() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.SECONDARY)!!
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.SECONDARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performClick()
        compose.onNodeWithTag("input-add-control-a").performScrollTo().performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_hide_tools)).performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).assertDoesNotExist()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performClick()
        compose.runOnIdle { assertEquals(layout.elements.size + 1, InputLibraries.read(context).layout("game", InputRole.SECONDARY)!!.elements.size) }
    }

    @Test fun localToolsCanMoveAndExpandWithoutMovingTheCanvas() {
        val library = InputLibraries.read(context)
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = library.layout("game", InputRole.SECONDARY)!!.id, bindingsId = library.profile("game").binding(InputRole.SECONDARY)) } }
        compose.runOnIdle {
            val window = ShadowDialog.getLatestDialog().window!!
            assertEquals(android.view.WindowManager.LayoutParams.MATCH_PARENT, window.attributes.width)
            assertEquals(android.view.WindowManager.LayoutParams.MATCH_PARENT, window.attributes.height)
        }
        val canvas = compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot
        val before = compose.onNodeWithTag("input-editor-tools").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("input-editor-tools").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(250f, 150f), 500) }
        val after = compose.onNodeWithTag("input-editor-tools").fetchSemanticsNode().boundsInRoot
        assertTrue(after.left > before.left + 100f)
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).assertIsDisplayed()
        assertEquals(canvas, compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).assertDoesNotExist()
    }

    @Test fun deckPresetOffersItsRealElementsAndTheCommonEditor() {
        InputLibraries.update(context) { it.useLayout("game", InputRole.SECONDARY, InputLibraries.DECK, shared = true) }
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithTag("input-profile-game").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-secondary").performScrollTo().performClick()
        compose.onNodeWithTag("input-element-legacy:l4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("input-binding-a").assertDoesNotExist()
    }

    @Test fun mouseTrackpadPresetIsEditableLikeAnyOtherLayout() {
        InputLibraries.update(context) { it.useLayout("game", InputRole.SECONDARY, InputLibraries.TRACKPAD, shared = true) }
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithTag("input-profile-game").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-secondary").performScrollTo().performClick()
        compose.onNodeWithTag("input-element-legacy:right-pad").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("input-surface-secondary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("input-binding-a").assertDoesNotExist()
    }

    @Test fun primaryScreenOffersItsActualElementsAndTheCommonEditor() {
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithTag("input-profile-game").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-primary").performScrollTo().performClick()
        compose.onNodeWithTag("input-element-legacy:a").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("input-binding-a").assertDoesNotExist()
        compose.onNodeWithTag("input-surface-primary").performScrollTo().performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).assertIsDisplayed()
        compose.onNodeWithTag("input-layout-canvas").assertExists()
    }

    @Test fun deviceSettingsAreInlineBelowProfiles() {
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithTag("input-more").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.ctrl_dead_zone)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("input-profile-game").performScrollTo().assertIsDisplayed()
    }

    @Test fun migratedPadClicksRemainEditableWithoutChangingMovement() {
        compose.setContent { DroidDeckTheme { InputSettingsPage({}) } }
        compose.onNodeWithTag("input-profile-desktop").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-secondary").performScrollTo().performClick()
        compose.onNodeWithTag("input-click-legacy:right-pad").performScrollTo().performClick()
        compose.onNodeWithText("Disabled", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("input-action-save").performClick()
        compose.runOnIdle {
            val library = InputLibraries.read(context)
            val pad = library.layout("desktop", InputRole.SECONDARY)!!.elements.first { it.control == InputControl.RIGHT_PAD }
            val profile = library.compile("desktop")
            assertEquals(InputAction.Disabled, profile.elementClickAction(InputRole.SECONDARY, pad))
            assertEquals(InputAction.Pointer(), profile.elementAction(InputRole.SECONDARY, pad.id, pad.control))
        }
    }

    @Test @Config(sdk = [34]) fun secondarySettingsCanOpenAnActivityDialogAndCloseCleanly() {
        val displayId = ShadowDisplayManager.addDisplay("w640dp-h480dp", ReflectionHelpers.getStaticField<Int>(Display::class.java, "TYPE_EXTERNAL"))
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        val open = mutableStateOf(true)
        try {
            compose.setContent { DroidDeckTheme {
                val host = androidx.compose.ui.platform.LocalContext.current.findActivity() as androidx.activity.ComponentActivity
                if (open.value) InputDisplayContent(host, display, { open.value = false }) { InputSettingsPage({ open.value = false }) }
            } }
            compose.onNodeWithText(context.getString(R.string.input_new_profile)).performScrollTo().performClick()
            compose.onNodeWithTag("input-name").assertExists()
            compose.runOnIdle {
                assertTrue(ShadowDialog.getShownDialogs().filterIsInstance<Presentation>().any { it.isShowing && it.display.displayId == displayId })
                val dialog = ShadowDialog.getShownDialogs().last { it !is Presentation && it.isShowing }
                assertEquals(Display.DEFAULT_DISPLAY, dialog.window?.decorView?.display?.displayId)
                open.value = false
            }
            compose.runOnIdle { assertTrue(ShadowDialog.getShownDialogs().none { it.isShowing }) }
        } finally { ShadowDisplayManager.removeDisplay(displayId) }
    }

    @Test @Config(sdk = [34]) fun unavailableDisplayDismissesSettingsWithoutCrashing() {
        val displayId = ShadowDisplayManager.addDisplay("w640dp-h480dp", ReflectionHelpers.getStaticField<Int>(Display::class.java, "TYPE_EXTERNAL"))
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        ShadowDisplayManager.removeDisplay(displayId)
        val open = mutableStateOf(true)
        compose.setContent { DroidDeckTheme {
            val host = androidx.compose.ui.platform.LocalContext.current.findActivity() as androidx.activity.ComponentActivity
            if (open.value) InputDisplayContent(host, display, { open.value = false }) { InputSettingsPage({}) }
        } }
        compose.runOnIdle {
            assertFalse(open.value)
            assertTrue(ShadowDialog.getShownDialogs().none { it.isShowing })
        }
    }

    @Test @Config(sdk = [34]) fun mainEditorOpenedFromSecondarySettingsUsesAnActivityWindow() {
        val displayId = ShadowDisplayManager.addDisplay("w640dp-h480dp", ReflectionHelpers.getStaticField<Int>(Display::class.java, "TYPE_EXTERNAL"))
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        val open = mutableStateOf(true)
        try {
            compose.setContent { DroidDeckTheme {
                val host = androidx.compose.ui.platform.LocalContext.current.findActivity() as androidx.activity.ComponentActivity
                if (open.value) InputDisplayContent(host, display, { open.value = false }) {
                    InputSurfaceDialog("game", { open.value = false }, {}, role = InputRole.PRIMARY)
                }
            } }
            compose.onNodeWithTag("input-layout-canvas").assertExists()
            compose.runOnIdle {
                assertTrue(ShadowDialog.getShownDialogs().none { it is Presentation && it.display.displayId == Display.DEFAULT_DISPLAY })
                val editor = ShadowDialog.getShownDialogs().last { it !is Presentation && it.isShowing }
                assertEquals(Display.DEFAULT_DISPLAY, editor.window?.decorView?.display?.displayId)
                open.value = false
            }
            compose.runOnIdle { assertTrue(ShadowDialog.getShownDialogs().none { it.isShowing }) }
        } finally { ShadowDisplayManager.removeDisplay(displayId) }
    }

    @Test @Config(sdk = [34]) fun auxiliaryEditorUsesThatDisplayAndSurvivesItsRemoval() {
        val displayId = ShadowDisplayManager.addDisplay("w640dp-h480dp", ReflectionHelpers.getStaticField<Int>(Display::class.java, "TYPE_EXTERNAL"))
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        assertTrue(SecondScreenDisplays.available(context.getSystemService(DisplayManager::class.java)).any { it.id == displayId })
        val previous = SessionState.secondScreenDisplay
        SessionState.secondScreenDisplay = displayId
        val open = mutableStateOf(true)
        try {
            compose.setContent { DroidDeckTheme { if (open.value) InputSurfaceDialog("game", { open.value = false }, {}) } }
            compose.onNodeWithText(context.getString(R.string.input_editor_remote)).assertExists()
            compose.onAllNodesWithText(context.getString(R.string.input_editor_add)).assertCountEquals(1)
            compose.onNodeWithText(context.getString(R.string.input_hide_tools)).assertDoesNotExist()
            assertEquals(0f, compose.onNodeWithTag("input-layout-canvas").fetchSemanticsNode().boundsInRoot.top, 1f)
            compose.runOnIdle {
                assertTrue(ShadowDialog.getShownDialogs().filterIsInstance<Presentation>().any { it.isShowing && it.display.displayId == displayId })
            }
            compose.onNodeWithTag("input-layout-canvas").performTouchInput { click(percentOffset(.25f, .25f)) }
            compose.onNodeWithTag("input-remove-selection").performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(ShadowDialog.getShownDialogs().any { it !is Presentation && it.isShowing && it.window?.decorView?.display?.displayId == Display.DEFAULT_DISPLAY })
                ShadowDisplayManager.removeDisplay(displayId)
            }
            compose.onNodeWithText(context.getString(R.string.input_display_unavailable)).assertExists()
            compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performScrollTo().performClick()
            compose.runOnIdle {
                assertFalse(open.value)
                assertEquals(setOf("legacy:left-pad"), InputLibraries.read(context).layout("game", InputRole.SECONDARY)!!.style.removed)
            }
        } finally {
            SessionState.secondScreenDisplay = previous
            if (display.isValid) ShadowDisplayManager.removeDisplay(displayId)
        }
    }

    @Test fun elementPropertiesEditOpacityLabelsAndOfferImages() {
        val element = mutableStateOf(InputSurfaceElement(InputControl.A, .1f, .1f, .2f, .2f, "custom", name = "Custom button"))
        val action = mutableStateOf<InputAction?>(null)
        compose.setContent { DroidDeckTheme {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                InputElementProperties(element.value, action.value) { element.value = it }
            }
        } }
        compose.onNodeWithTag("setting-element-opacity").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(60f) }
        compose.runOnIdle { assertEquals(.6f, element.value.opacity, .001f) }
        compose.onNodeWithTag("setting-element-label").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(element.value.showLabel) }
        compose.onNodeWithText(context.getString(R.string.input_button_text)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.screen_key_image)).assertExists()
        compose.runOnIdle { element.value = element.value.copy(icon = InputButtonIcons.MENU) }
        compose.onNodeWithTag("setting-element-label").assertDoesNotExist()
        compose.runOnIdle { element.value = InputSurfaceElement(InputControl.LEFT_PAD, .1f, .1f, .4f, .4f, "pad", InputElementKind.TRACKPAD, "Pad") }
        compose.onNodeWithTag("setting-element-label").assertDoesNotExist()
        compose.onNodeWithTag("setting-element-opacity").assertExists()
        for (kind in listOf(InputElementKind.DPAD, InputElementKind.STICK)) {
            compose.runOnIdle { element.value = InputSurfaceElement(kind.control, .1f, .1f, .4f, .4f, "imported:0", kind, "WASD"); action.value = InputAction.Directions(17, 32, 31, 30) }
            compose.onNodeWithTag("setting-element-label").performScrollTo().performClick()
            compose.runOnIdle { assertFalse(element.value.showLabel); action.value = InputAction.Native() }
            compose.onNodeWithTag("setting-element-label").assertDoesNotExist()
            compose.runOnIdle { action.value = InputAction.Directions(0, 0, 0, 0, listOf(16, 18, 44, 46)) }
            if (kind == InputElementKind.STICK) compose.onNodeWithTag("setting-element-label").assertExists()
            else compose.onNodeWithTag("setting-element-label").assertDoesNotExist()
        }
        compose.runOnIdle { element.value = InputSurfaceElement(InputControl.LS, .1f, .1f, .4f, .4f, "legacy:left-stick", InputElementKind.STICK, "Move") }
        compose.onNodeWithTag("setting-element-label").assertExists()
    }

    @Test fun editorScalesEveryControlAndRestoresStockPositions() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.SECONDARY)!!
        assertEquals(InputLibraries.DECK, layout.id)
        val round = mutableStateOf(0)
        compose.setContent { DroidDeckTheme { androidx.compose.runtime.key(round.value) { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.SECONDARY)) } } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_reset_layout)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("input-editor-scale").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(80f) }
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performScrollTo().performClick()
        val scaled = InputLibraries.read(context).layouts.first { it.id == layout.id }
        assertEquals(layout.elements.scaled(.8f), scaled.elements)
        compose.runOnIdle { round.value = 1 }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_reset_layout)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(layout.elements, InputLibraries.read(context).layouts.first { it.id == layout.id }.elements) }
    }

    @Test fun editorSnapChoiceIsRemembered() {
        com.droiddeck.launcher.input.ControllerPrefs.setSnap(context, true)
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.SECONDARY)!!
        val round = mutableStateOf(0)
        compose.setContent { DroidDeckTheme { androidx.compose.runtime.key(round.value) { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.SECONDARY)) } } }
        val snap = isToggleable() and hasAnySibling(hasText(context.getString(R.string.ctrl_editor_snap)))
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNode(snap).performScrollTo().assertIsOn().performClick()
        compose.runOnIdle { assertFalse(com.droiddeck.launcher.input.ControllerPrefs.snap(context)) }
        compose.runOnIdle { round.value = 1 }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNode(snap).performScrollTo().assertIsOff()
        com.droiddeck.launcher.input.ControllerPrefs.setSnap(context, true)
    }

    @Test fun editorAddsRemovedButtonsBackWhereTheyWere() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.PRIMARY)!!
        val removed = setOf("builtin:keyboard", "legacy:guide")
        InputLibraries.update(context) { it.withLayout(layout.copy(style = layout.style.copy(removed = removed))) }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.PRIMARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performScrollTo().performClick()
        compose.onNodeWithTag("input-add-control-guide").performScrollTo().performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performScrollTo().performClick()
        compose.onNodeWithTag("input-add-control-keyboard").performScrollTo().performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performScrollTo().performClick()
        compose.runOnIdle {
            val after = InputLibraries.read(context).layout("game", InputRole.PRIMARY)!!
            assertEquals(layout.elements, after.elements)
            assertEquals(emptySet<String>(), after.style.removed)
        }
    }

    @Test fun layoutsCanBeImportedAndExported() {
        val source = java.io.File(context.cacheDir, "jump.icp").apply {
            writeText("""{"name":"Jump keys","elements":[{"type":"BUTTON","shape":"CIRCLE","bindings":["KEY_SPACE"],"scale":1.0,"x":0.8,"y":0.7,"text":"Jump"}]}""")
        }
        val target = java.io.File(context.cacheDir, "out.icp").apply { delete() }
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                options: androidx.core.app.ActivityOptionsCompat?) {
                dispatchResult(requestCode, android.net.Uri.fromFile(if (input is String) target else source))
            }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent { androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
            DroidDeckTheme { InputSettingsPage({}) }
        } }
        compose.onNodeWithTag("input-profile-game").performScrollTo().performClick()
        compose.onNodeWithTag("input-part-primary").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.input_saved_layouts)).performScrollTo().performClick()
        compose.onNodeWithTag("layout-manage-${InputLibraries.OVERLAY}").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.screen_key_layout_export)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.common_close)).performClick()
        compose.onNodeWithTag("input-layout-import").performScrollTo().performClick()
        compose.waitUntil(5_000) { InputLibraries.read(context).layout("game", InputRole.PRIMARY)!!.name == "Jump keys" }
        val imported = InputLibraries.read(context).layout("game", InputRole.PRIMARY)!!
        assertEquals(InputLayoutScreen.MAIN, imported.screen)
        assertEquals(InputAction.Keys(listOf(57)), InputLibraries.read(context).compile("game").elementAction(InputRole.PRIMARY, imported.elements.single().id, InputControl.A))
        compose.onNodeWithText(context.getString(R.string.input_saved_layouts)).performScrollTo().performClick()
        compose.onNodeWithTag("layout-manage-${imported.id}").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.screen_key_layout_export)).performClick()
        compose.waitUntil(5_000) { target.isFile && target.length() > 0 }
        assertEquals(source.readText().let { org.json.JSONObject(it).getString("name") }, org.json.JSONObject(target.readText()).getString("name"))
        assertEquals("KEY_SPACE", org.json.JSONObject(target.readText()).getJSONArray("elements").getJSONObject(0).getJSONArray("bindings").getString(0))
    }

    @Test fun undoAfterAddingBackOnlyReversesTheNextScaleChange() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.PRIMARY)!!
        InputLibraries.update(context) { it.withLayout(layout.copy(style = layout.style.copy(removed = setOf("builtin:keyboard", "legacy:guide")))) }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.PRIMARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        val slider = compose.onNodeWithTag("input-editor-scale").performScrollTo()
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(110f) }
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performScrollTo().performClick()
        compose.onNodeWithTag("input-add-control-guide").performScrollTo().performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-scale").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(120f) }
        compose.onNodeWithText(context.getString(R.string.input_editor_undo)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performScrollTo().performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == layout.id }
            assertEquals(1.1f, saved.scale)
            assertEquals(setOf("builtin:keyboard"), saved.style.removed)
        }
    }

    @Test fun scaleAfterAddingBackKeepsItsMeaningAndUndoesOneStep() {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.DECK }
        val hidden = layout.elements.first().id
        InputLibraries.update(context) { it.withLayout(layout.copy(style = layout.style.copy(removed = setOf(hidden)))) }
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.SECONDARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        val percent = androidx.compose.ui.semantics.SemanticsProperties.StateDescription
        compose.onNodeWithTag("input-editor-scale").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(110f) }
        compose.onNodeWithText(context.getString(R.string.input_editor_add)).performScrollTo().performClick()
        compose.onNodeWithTag("input-add-control-left-pad").performScrollTo().performClick()
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-scale").performScrollTo().assert(SemanticsMatcher.expectValue(percent, "110%"))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(120f) }
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performScrollTo().performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == layout.id }
            assertEquals(layout.elements.scaled(1.2f), saved.elements)
            assertEquals(emptySet<String>(), saved.style.removed)
        }
        compose.onNodeWithText(context.getString(R.string.input_editor_undo)).performScrollTo().performClick()
        compose.onNodeWithTag("input-editor-scale").performScrollTo().assert(SemanticsMatcher.expectValue(percent, "110%"))
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performScrollTo().performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == layout.id }
            assertEquals(layout.elements.scaled(1.1f), saved.elements)
            assertEquals(emptySet<String>(), saved.style.removed)
        }
    }

    private fun deckDraft(): Pair<SurfaceDraft, String> {
        val library = InputLibraries.read(context)
        val layout = library.layouts.first { it.id == InputLibraries.DECK }
        val draft = SurfaceDraft(layout, library.preset(library.profile("game").binding(InputRole.SECONDARY)))
        val id = "legacy:l4"
        draft.select(listOf(id))
        return draft to id
    }

    private fun overlayDraft(): SurfaceDraft {
        val library = InputLibraries.read(context)
        return SurfaceDraft(library.layouts.first { it.id == InputLibraries.OVERLAY }, library.preset(library.profile("game").binding(InputRole.PRIMARY)))
    }

    private fun choice(key: String) = inputAddChoices(context, trackpads = true).first { it.key == key }

    private fun SurfaceDraft.addOne(choice: InputAddChoice): InputSurfaceElement {
        val before = elements.map { it.id }.toSet()
        add(choice, 16f / 9f)
        return elements.single { it.id !in before }.also { assertEquals(listOf(it.id), selection) }
    }

    private fun bounds(e: InputSurfaceElement) = SurfaceBounds(e.x, e.y, e.x + e.width, e.y + e.height)

    private fun SurfaceBounds.overlaps(o: SurfaceBounds) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    @Test fun addingAControllerInputCreatesOneBoundAndLabelledControl() {
        val draft = overlayDraft()
        val cases = listOf(
            Triple(choice("control-a"), InputAction.Native(InputControl.A), "A"),
            Triple(choice("control-lt"), InputAction.Native(InputControl.LT), InputControl.LT.label),
            Triple(choice("control-ls"), InputAction.Native(InputControl.LS), InputControl.LS.label),
            Triple(choice("control-dpad"), InputAction.Native(), context.getString(R.string.input_group_dpad)),
            Triple(choice("control-guide"), InputAction.Native(InputControl.GUIDE), InputControl.GUIDE.label),
            Triple(choice("control-right-pad"), InputAction.Native(InputControl.RIGHT_PAD), InputControl.RIGHT_PAD.label),
        )
        for ((choice, action, name) in cases) {
            val added = draft.addOne(choice)
            assertEquals(choice.key, name, added.name)
            assertEquals(choice.key, choice.kind, added.kind)
            assertEquals(choice.key, added.kind.control, added.control)
            assertEquals(choice.key, action, draft.actions[added.id])
            assertTrue(choice.key, action.validFor(added))
            assertEquals(choice.key, action, draft.preset.copy(elements = draft.actions).action(added))
        }
        assertEquals(listOf("a", "b", "x", "y", "dpad", "ls", "rs", "lb", "rb", "lt", "rt", "l3", "r3", "select", "start", "guide", "qam", "keyboard",
            "left-pad", "right-pad"), inputAddChoices(context, trackpads = true).map { it.key.removePrefix("control-") })
        assertTrue(inputAddChoices(context, trackpads = false).none { it.kind == InputElementKind.TRACKPAD })
    }

    @Test fun anAddedControlLandsInFreeSpaceAndDuplicatesAreAllowed() {
        val draft = overlayDraft()
        val first = draft.addOne(choice("control-a"))
        val second = draft.addOne(choice("control-a"))
        assertTrue(draft.visible.any { it.id == "legacy:a" })
        assertEquals(draft.actions[first.id], draft.actions[second.id])
        assertFalse(bounds(first).overlaps(bounds(second)))
        for (other in draft.visible.filter { it.id != first.id && it.id != second.id }) assertFalse(other.id, bounds(first).overlaps(bounds(other)))
    }

    @Test fun addingADeletedStockInputRestoresItInsteadOfAddingAnother() {
        val draft = overlayDraft()
        val original = draft.elements
        val guide = original.first { it.id == "legacy:guide" }
        draft.select(listOf(guide.id)); draft.deleteSelection()
        assertTrue(draft.visible.none { it.id == guide.id })
        draft.add(choice("control-guide"), 16f / 9f)
        assertEquals(original, draft.elements)
        assertEquals(listOf(guide.id), draft.selection)
        assertTrue(guide.id !in draft.removed)
        val dpad = original.filter { it.id in listOf("legacy:up", "legacy:right", "legacy:down", "legacy:left") }.map { it.id }
        draft.select(dpad); draft.deleteSelection()
        draft.add(choice("control-dpad"), 16f / 9f)
        assertEquals(original, draft.elements)
        assertTrue(dpad.none { it in draft.removed })
    }

    @Test fun theKeyboardButtonCanBeRestoredOrAddedAgain() {
        val draft = overlayDraft()
        val original = draft.elements
        assertTrue(original.any { it.id == "builtin:keyboard" })
        val keyboard = choice("control-keyboard")
        draft.select(listOf("builtin:keyboard")); draft.deleteSelection()
        assertTrue(draft.visible.none { it.id == "builtin:keyboard" })
        draft.add(keyboard, 16f / 9f)
        assertEquals(original, draft.elements)
        assertEquals(listOf("builtin:keyboard"), draft.selection)
        assertTrue(draft.visible.any { it.id == "builtin:keyboard" })
        val another = draft.addOne(keyboard)
        assertEquals(InputAction.Command(InputCommand.KEYBOARD), draft.actions[another.id])
        assertEquals(InputButtonIcons.KEYBOARD, another.icon)
        assertEquals(context.getString(R.string.input_add_keyboard), another.name)
        assertTrue(InputAction.Command(InputCommand.KEYBOARD).validFor(another))
    }

    @Test fun deletingAndUndoingBringsBackStockAndAddedControls() {
        val draft = overlayDraft()
        val added = draft.addOne(choice("control-x"))
        draft.deleteSelection()
        assertTrue(draft.elements.none { it.id == added.id })
        assertNull(draft.actions[added.id])
        draft.undo()
        assertEquals(added, draft.elements.single { it.id == added.id })
        assertEquals(InputAction.Native(InputControl.X), draft.actions[added.id])
        draft.select(listOf("legacy:b")); draft.deleteSelection()
        assertTrue("legacy:b" in draft.removed)
        assertTrue(draft.elements.any { it.id == "legacy:b" })
        draft.undo()
        assertFalse("legacy:b" in draft.removed)
    }

    private fun SurfaceDraft.state() = listOf(elements, actions, clicks, removed, layoutScale, scale, selectionSize, selectionOpacity)

    @Test fun redoRestoresEveryUndoneStepExactly() {
        val (draft, id) = deckDraft()
        val states = mutableListOf(draft.state())
        draft.select(listOf("legacy:r4")); draft.deleteSelection(); draft.select(listOf(id)); states += draft.state()
        draft.resizeSelection(120); states += draft.state()
        draft.checkpoint(); draft.actions = draft.actions + (id to InputAction.Keys(listOf(57))); states += draft.state()
        draft.setSelectionOpacity(60); states += draft.state()
        draft.scaleAll(110); draft.scaleAll(115); states += draft.state()
        for (i in states.indices.reversed().drop(1)) { draft.undo(); assertEquals(states[i], draft.state()) }
        assertTrue(draft.history.isEmpty())
        for (i in 1 until states.size) { draft.redo(); assertEquals(states[i], draft.state()) }
        assertTrue(draft.redoable.isEmpty())
    }

    @Test fun aNewEditAfterUndoClearsRedo() {
        val (draft, id) = deckDraft()
        draft.resizeSelection(120)
        draft.undo()
        assertEquals(1, draft.redoable.size)
        draft.setSelectionOpacity(50)
        assertTrue(draft.redoable.isEmpty())
        draft.redo()
        assertEquals(.5f, draft.selectionOpacity, .001f)
        assertEquals(100, draft.selectionSize)
        draft.undo(); draft.undo()
        assertEquals(id, draft.selected)
    }

    @Test fun redoAcrossAScaleSliderStepStartsAFreshSliderSession() {
        val (draft, _) = deckDraft()
        val start = draft.elements
        draft.scaleAll(110); draft.scaleAll(120)
        val scaled = draft.elements
        draft.undo()
        assertEquals(100, draft.scale)
        assertEquals(start, draft.elements)
        draft.redo()
        assertEquals(120, draft.scale)
        assertEquals(scaled, draft.elements)
        draft.scaleAll(115)
        assertTrue(draft.redoable.isEmpty())
        draft.undo()
        assertEquals(120, draft.scale)
        assertEquals(scaled, draft.elements)
        draft.undo()
        assertEquals(100, draft.scale)
        assertEquals(start, draft.elements)
    }

    @Test fun resizeAfterAnActionChangeIsItsOwnUndoStep() {
        val (draft, id) = deckDraft()
        val jump = InputAction.Keys(listOf(57))
        draft.resizeSelection(120)
        draft.checkpoint(); draft.actions = draft.actions + (id to jump)
        draft.resizeSelection(140)
        assertEquals(140, draft.selectionSize)
        draft.undo()
        assertEquals(120, draft.selectionSize)
        assertEquals(jump, draft.actions[id])
        draft.undo()
        assertEquals(120, draft.selectionSize)
        assertNotEquals(jump, draft.actions[id])
        draft.undo()
        assertEquals(100, draft.selectionSize)
    }

    @Test fun opacityAfterAnActionChangeIsItsOwnUndoStep() {
        val (draft, id) = deckDraft()
        val jump = InputAction.Keys(listOf(57))
        val opacity = draft.selectionOpacity
        assertNotEquals(.7f, opacity, .001f)
        draft.setSelectionOpacity(80)
        draft.setSelectionOpacity(70)
        draft.checkpoint(); draft.actions = draft.actions + (id to jump)
        draft.setSelectionOpacity(50)
        draft.undo()
        assertEquals(.7f, draft.selectionOpacity, .001f)
        assertEquals(jump, draft.actions[id])
        draft.undo()
        assertEquals(.7f, draft.selectionOpacity, .001f)
        assertNotEquals(jump, draft.actions[id])
        draft.undo()
        assertEquals(opacity, draft.selectionOpacity, .001f)
    }

    @Test fun buttonSizeRegeneratesTheGamepadAndUndoesAsOneStep() {
        val library = InputLibraries.read(context)
        val layout = library.layout("game", InputRole.PRIMARY)!!
        val (_, metrics) = InputLibraries.primaryDisplay(context)
        assertNotNull(OnScreenLayout.generatedSize(context, layout, metrics.widthPixels, metrics.heightPixels))
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = layout.id, bindingsId = library.profile("game").binding(InputRole.PRIMARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        val slider = compose.onNodeWithTag("input-editor-scale").performScrollTo()
        slider.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "100%"))
        for (value in listOf(110f, 125f)) slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(value) }
        compose.onNodeWithText(context.getString(R.string.input_editor_undo)).performScrollTo().performClick()
        slider.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "100%"))
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(125f) }
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performScrollTo().performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == layout.id }
            assertEquals(1.25f, saved.scale)
            assertNotNull(OnScreenLayout.generatedSize(context, saved, metrics.widthPixels, metrics.heightPixels))
            assertNotEquals(layout.elements.first { it.id == "legacy:a" }.y, saved.elements.first { it.id == "legacy:a" }.y)
        }
    }

    @Test @Config(qualifiers = "w833dp-h468dp-land-369dpi")
    fun buttonSizeKeepsMovedGamepadGroupsInShape() {
        val face = setOf("legacy:a", "legacy:b", "legacy:x", "legacy:y")
        val original = InputLibraries.read(context)
        val gamepad = original.layout("game", InputRole.PRIMARY)!!
        val moved = gamepad.copy(elements = gamepad.elements.map { if (it.id in face) it.copy(x = it.x + .012f, y = it.y + .017f) else it })
        InputLibraries.update(context) { it.withLayout(moved) }
        val (_, metrics) = InputLibraries.primaryDisplay(context)
        assertNull(OnScreenLayout.generatedSize(context, moved, metrics.widthPixels, metrics.heightPixels))
        val library = InputLibraries.read(context)
        compose.setContent { DroidDeckTheme { InputSurfaceDialog("game", {}, {}, role = InputRole.PRIMARY,
            layoutId = moved.id, bindingsId = library.profile("game").binding(InputRole.PRIMARY)) } }
        compose.onNodeWithTag("input-editor-tools").performClick()
        compose.onNodeWithTag("input-editor-scale").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(125f) }
        compose.onNodeWithText(context.getString(R.string.gesture_save_changes)).performScrollTo().performClick()
        compose.runOnIdle {
            val saved = InputLibraries.read(context).layouts.first { it.id == moved.id }
            assertEquals(1.25f, saved.scale)
            val w = metrics.widthPixels; val h = metrics.heightPixels
            fun centre(id: String) = saved.elements.first { it.id == id }.let { (it.x + it.width / 2) * w to (it.y + it.height / 2) * h }
            assertEquals(centre("legacy:y").first, centre("legacy:a").first, 1f)
            assertEquals(centre("legacy:x").second, centre("legacy:b").second, 1f)
            assertTrue(centre("legacy:y").second < centre("legacy:x").second && centre("legacy:x").second < centre("legacy:a").second)
            assertEquals(centre("legacy:a").second - centre("legacy:y").second, centre("legacy:b").first - centre("legacy:x").first, 1f)
            assertEquals(centre("legacy:up").first, centre("legacy:down").first, 1f)
            assertEquals(centre("legacy:left").second, centre("legacy:right").second, 1f)
            val before = moved.elements.first { it.id == "legacy:a" }
            assertTrue(saved.elements.first { it.id == "legacy:a" }.width > before.width * 1.2f)
        }
    }
}
