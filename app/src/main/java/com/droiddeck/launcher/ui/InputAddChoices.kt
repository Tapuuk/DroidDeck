package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*

/** One controller input the editor can add: a control of [kind] that sends only [action], labelled [name]. */
internal data class InputAddChoice(
    val key: String, val name: String, val kind: InputElementKind, val control: InputControl, val action: InputAction,
    val stock: List<String>, val icon: Int = 0,
) {
    val pill get() = kind == InputElementKind.TRIGGER || control == InputControl.LB || control == InputControl.RB
}

/** A stock group the editor can add as one set: [members] placed like [arrangement], in a unit square that spans [size] of the surface height. */
internal data class InputAddGroup(
    val key: String, val name: String, val group: InputControlGroup, val members: List<InputAddChoice>, val arrangement: List<SurfaceBounds>,
    val size: Float,
)

private val BUTTONS = listOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y,
    InputControl.UP, InputControl.DOWN, InputControl.LEFT, InputControl.RIGHT)
private val CONTROLS = listOf(InputControl.LS, InputControl.RS, InputControl.LB, InputControl.RB, InputControl.LT, InputControl.RT,
    InputControl.L3, InputControl.R3, InputControl.L4, InputControl.R4, InputControl.L5, InputControl.R5, InputControl.SELECT, InputControl.START, InputControl.GUIDE, InputControl.QAM)
private val SHOULDERS = setOf(InputControl.LB, InputControl.RB, InputControl.LT, InputControl.RT)
// Linux key codes, which InputButtonIcons draws as the same arrows the stock D-pad shows.
private val ARROWS = mapOf(InputControl.UP to 103, InputControl.DOWN to 108, InputControl.LEFT to 105, InputControl.RIGHT to 106)

private fun controlChoice(control: InputControl) = InputAddChoice("control-${control.id}",
    if (control in SHOULDERS) control.id.uppercase() else control.label, InputElementKind.from(control), control,
    InputAction.Native(control), listOf("legacy:${control.id}"), ARROWS[control] ?: 0)

internal fun inputAddChoices(context: Context, trackpads: Boolean): List<InputAddChoice> =
    (BUTTONS + CONTROLS).map(::controlChoice) +
        InputAddChoice("control-keyboard", context.getString(R.string.input_add_keyboard), InputElementKind.BUTTON, InputControl.A,
            InputAction.Command(InputCommand.KEYBOARD), listOf("builtin:keyboard"), InputButtonIcons.KEYBOARD) +
        if (trackpads) listOf(InputControl.LEFT_PAD, InputControl.RIGHT_PAD).map(::controlChoice) else emptyList()

private fun cross(order: List<InputControl>) = order.zip(listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f)).map { (control, d) ->
    control to SurfaceBounds(.34f + d.first * .3f, .34f + d.second * .3f, .66f + d.first * .3f, .66f + d.second * .3f)
}

private fun shoulders(outer: InputControl, inner: InputControl, outerLeft: Boolean): List<Pair<InputControl, SurfaceBounds>> {
    val left = SurfaceBounds(0f, .37f, .47f, .63f)
    val right = SurfaceBounds(.53f, .37f, 1f, .63f)
    return listOf(outer to if (outerLeft) left else right, inner to if (outerLeft) right else left)
}

internal fun inputAddGroups(context: Context): List<InputAddGroup> = InputControlGroup.entries.filter { it.controls.size > 1 }.map { group ->
    val (placed, size) = when (group) {
        InputControlGroup.DPAD -> cross(listOf(InputControl.UP, InputControl.RIGHT, InputControl.DOWN, InputControl.LEFT)) to .4f
        InputControlGroup.FACE -> cross(listOf(InputControl.Y, InputControl.B, InputControl.A, InputControl.X)) to .4f
        InputControlGroup.LEFT_SHOULDER -> shoulders(InputControl.LT, InputControl.LB, outerLeft = true) to .6f
        else -> shoulders(InputControl.RT, InputControl.RB, outerLeft = false) to .6f
    }
    InputAddGroup("group-" + group.name.lowercase().replace('_', '-'), context.getString(group.label()), group,
        placed.map { controlChoice(it.first) }, placed.map { it.second }, size)
}

/** The control [choice] adds, drawn as the stock control so it shows its real glyph, in [bounds] of a square preview. */
internal fun InputAddChoice.preview(bounds: SurfaceBounds? = null): InputSurfaceElement {
    val b = bounds ?: when {
        pill -> SurfaceBounds(0f, .25f, 1f, .75f)
        kind == InputElementKind.STICK -> SurfaceBounds(0f, 0f, 1f, 1f)
        kind == InputElementKind.TRACKPAD -> SurfaceBounds(.04f, .04f, .96f, .96f)
        else -> SurfaceBounds(.12f, .12f, .88f, .88f)
    }
    return InputSurfaceElement(control, b.left, b.top, b.width, b.height, id = stock.first(), kind = kind, name = name,
        icon = if (control in ARROWS) 0 else icon, buttonShape = if (pill) InputButtonShape.PILL else InputButtonShape.CIRCLE)
}

internal fun InputAddGroup.preview() = members.zip(arrangement) { member, b -> member.preview(b) }

/** One tile of the Add control panel. */
private class AddTileItem(val key: String, val name: String, val preview: List<InputSurfaceElement>, val enabled: Boolean, val onClick: () -> Unit)

private val PanelShape = RoundedCornerShape(20.dp)
private val TileShape = RoundedCornerShape(14.dp)
private val TileWidth = 76.dp
private val MiniSize = 64.dp

/** The Add control panel: tabs of single controls and stock groups, each tile drawn like the real controls in [style]. */
@Composable
internal fun AddControlPanel(choices: List<InputAddChoice>, groups: List<InputAddGroup>, available: (InputAddChoice) -> Boolean,
    groupAvailable: (InputAddGroup) -> Boolean, style: InputSurfaceStyle, onAdd: (InputAddChoice) -> Unit, onAddGroup: (InputAddGroup) -> Unit,
    onClose: () -> Unit) {
    val pal = LocalPalette.current
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(shown.currentState, shown.isIdle) { if (shown.isIdle && !shown.currentState && !shown.targetState) onClose() }
    val close = { shown.targetState = false }
    val screen = LocalConfiguration.current
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val keys = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val look = remember(style) { style.copy(opacity = 100, removed = emptySet()) }
    val tiles = if (tab == 0) choices.map { c -> AddTileItem(c.key, c.name, listOf(c.preview()), available(c)) { onAdd(c) } }
        else groups.map { g -> AddTileItem(g.key, g.name, g.preview(), groupAvailable(g)) { onAddGroup(g) } }
    val firstEnabled = tiles.firstOrNull { it.enabled }
    val tabs = listOf(0 to stringResource(R.string.input_add_tab_buttons), 1 to stringResource(R.string.input_add_tab_groups))
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) { window?.setWindowAnimations(0); onDispose { } }
        AnimatedVisibility(shown, enter = fadeIn(Motion.tw(180)) + scaleIn(Motion.tw(240, easing = EditorMotion.Pop), initialScale = 0.94f),
            exit = fadeOut(Motion.tw(140)) + scaleOut(Motion.tw(140), targetScale = 0.97f), label = "add-control") {
            Column(
                Modifier.width((screen.screenWidthDp * .6f).dp).height((screen.screenHeightDp * .85f).dp)
                    .editorGlass(PanelShape, opacity = 0.94f).controllerBack(close).testTag("input-add-panel"),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.input_add_element), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = pal.onBackground,
                        modifier = Modifier.weight(1f))
                    EditorIconButton(Icons.Filled.Close, stringResource(R.string.common_close), Modifier.testTag("input-add-close"), size = 36.dp,
                        onClick = close)
                }
                SegmentedTabs(tabs, tab, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    tabModifier = { Modifier.testTag(if (it == 0) "input-add-tab-buttons" else "input-add-tab-groups") }) { tab = it }
                BoxWithConstraints(Modifier.weight(1f)) {
                    val columns = ((maxWidth - 24.dp + 6.dp) / (TileWidth + 6.dp)).toInt().coerceAtLeast(1)
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (row in tiles.chunked(columns)) Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (tile in row) key(tile.key) {
                                AddTile(tile, look, Modifier.weight(1f).fillMaxHeight().then(if (tile === firstEnabled) Modifier.focusRequester(first)
                                    .onFocusChanged { focused = it.isFocused } else Modifier))
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        if (keys && firstEnabled != null) LaunchedEffect(Unit) {
            repeat(10) { withFrameNanos { }; if (focused) return@LaunchedEffect; runCatching { first.requestFocus() } }
        }
    }
}

@Composable
private fun AddTile(tile: AddTileItem, style: InputSurfaceStyle, modifier: Modifier) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && tile.enabled
    val scale = pressScale(src)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.testTag("input-add-${tile.key}").graphicsLayer { scaleX = scale; scaleY = scale }.clip(TileShape)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else pal.onBackground.copy(alpha = 0.04f))
            .glideBorder(hot, TileShape, pal.signal, hotFillAlpha = 0f)
            .graphicsLayer { alpha = if (tile.enabled) 1f else 0.4f }
            .hoverable(src).clickable(src, null, tile.enabled, role = Role.Button, onClick = tile.onClick)
            .controllerConfirm(tile.enabled, tile.onClick)
            .padding(top = 8.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
    ) {
        ControlMini(tile.preview, style)
        Text(tile.name, fontSize = 12.sp, lineHeight = 14.sp, color = pal.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

/** [elements] drawn once by the real controls view into a still image, so the preview matches the canvas and takes no touches. */
@Composable
private fun ControlMini(elements: List<InputSurfaceElement>, style: InputSurfaceStyle) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { MiniSize.roundToPx() }
    val image = remember(elements, style, px) {
        val view = InputSurfaceView(context, customLayout = elements, surfaceStyle = style, haptics = false)
        val spec = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
        view.measure(spec, spec)
        view.layout(0, 0, px, px)
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }.asImageBitmap()
    }
    Image(image, contentDescription = null, modifier = Modifier.size(MiniSize))
}
