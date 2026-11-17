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
import androidx.compose.foundation.layout.heightIn
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
)

private val BUTTONS = listOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y)
private val CONTROLS = listOf(InputControl.LS, InputControl.RS, InputControl.LB, InputControl.RB, InputControl.LT, InputControl.RT,
    InputControl.L3, InputControl.R3, InputControl.L4, InputControl.R4, InputControl.L5, InputControl.R5, InputControl.SELECT, InputControl.START, InputControl.GUIDE, InputControl.QAM)
private val DPAD_IDS = listOf(InputControl.UP, InputControl.RIGHT, InputControl.DOWN, InputControl.LEFT).map { "legacy:${it.id}" }

private fun controlChoice(control: InputControl) = InputAddChoice("control-${control.id}", control.label, InputElementKind.from(control), control,
    InputAction.Native(control), listOf("legacy:${control.id}"))

internal fun inputAddChoices(context: Context, trackpads: Boolean): List<InputAddChoice> =
    BUTTONS.map(::controlChoice) +
        InputAddChoice("control-dpad", context.getString(R.string.input_group_dpad), InputElementKind.DPAD, InputElementKind.DPAD.control,
            InputAction.Native(), DPAD_IDS) +
        CONTROLS.map(::controlChoice) +
        InputAddChoice("control-keyboard", context.getString(R.string.input_add_keyboard), InputElementKind.BUTTON, InputControl.A,
            InputAction.Command(InputCommand.KEYBOARD), listOf("builtin:keyboard"), InputButtonIcons.KEYBOARD) +
        if (trackpads) listOf(InputControl.LEFT_PAD, InputControl.RIGHT_PAD).map(::controlChoice) else emptyList()

/** The control [choice] adds, drawn as the stock control so it shows its real glyph, fitted into a square preview. */
internal fun InputAddChoice.preview(): InputSurfaceElement {
    val pill = kind == InputElementKind.TRIGGER || control == InputControl.LB || control == InputControl.RB
    val inset = when {
        pill -> floatArrayOf(0f, .25f, 1f, .5f)
        kind == InputElementKind.STICK || kind == InputElementKind.DPAD -> floatArrayOf(0f, 0f, 1f, 1f)
        kind == InputElementKind.TRACKPAD -> floatArrayOf(.04f, .04f, .92f, .92f)
        else -> floatArrayOf(.12f, .12f, .76f, .76f)
    }
    return InputSurfaceElement(control, inset[0], inset[1], inset[2], inset[3], id = if (kind == InputElementKind.DPAD) "preview:dpad" else stock.first(),
        kind = kind, name = name, icon = icon, buttonShape = if (pill) InputButtonShape.PILL else InputButtonShape.CIRCLE)
}

private val PanelShape = RoundedCornerShape(20.dp)
private val TileShape = RoundedCornerShape(14.dp)
private val TileWidth = 84.dp
private val MiniSize = 64.dp

/** The Add control panel: a grid of every control [choices] offers, each drawn like the real control in [style]. */
@Composable
internal fun AddControlPanel(choices: List<InputAddChoice>, available: (InputAddChoice) -> Boolean, style: InputSurfaceStyle,
    onAdd: (InputAddChoice) -> Unit, onClose: () -> Unit) {
    val pal = LocalPalette.current
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(shown.currentState, shown.isIdle) { if (shown.isIdle && !shown.currentState && !shown.targetState) onClose() }
    val close = { shown.targetState = false }
    val screen = LocalConfiguration.current
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val keys = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val look = remember(style) { style.copy(opacity = 100, removed = emptySet()) }
    val firstEnabled = choices.firstOrNull(available)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) { window?.setWindowAnimations(0); onDispose { } }
        AnimatedVisibility(shown, enter = fadeIn(Motion.tw(180)) + scaleIn(Motion.tw(240, easing = EditorMotion.Pop), initialScale = 0.94f),
            exit = fadeOut(Motion.tw(140)) + scaleOut(Motion.tw(140), targetScale = 0.97f), label = "add-control") {
            Column(
                Modifier.width((screen.screenWidthDp * .6f).dp).heightIn(max = (screen.screenHeightDp * .85f).dp)
                    .editorGlass(PanelShape, opacity = 0.94f).controllerBack(close).testTag("input-add-panel"),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.input_add_element), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = pal.onBackground,
                        modifier = Modifier.weight(1f))
                    EditorIconButton(Icons.Filled.Close, stringResource(R.string.common_close), Modifier.testTag("input-add-close"), size = 36.dp,
                        onClick = close)
                }
                BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                    val columns = ((maxWidth - 24.dp + 6.dp) / (TileWidth + 6.dp)).toInt().coerceAtLeast(1)
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (row in choices.chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (choice in row) AddTile(choice, available(choice), look,
                                Modifier.weight(1f).then(if (choice === firstEnabled) Modifier.focusRequester(first)
                                    .onFocusChanged { focused = it.isFocused } else Modifier)) { onAdd(choice) }
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
private fun AddTile(choice: InputAddChoice, enabled: Boolean, style: InputSurfaceStyle, modifier: Modifier, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val scale = pressScale(src)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.testTag("input-add-${choice.key}").graphicsLayer { scaleX = scale; scaleY = scale }.clip(TileShape)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else pal.onBackground.copy(alpha = 0.04f))
            .glideBorder(hot, TileShape, pal.signal, hotFillAlpha = 0f)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .hoverable(src).clickable(src, null, enabled, role = Role.Button, onClick = onClick).controllerConfirm(enabled, onClick)
            .padding(top = 8.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
    ) {
        ControlMini(remember(choice) { choice.preview() }, style)
        Text(choice.name, fontSize = 12.sp, lineHeight = 14.sp, color = pal.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

/** [element] drawn once by the real controls view into a still image, so the preview matches the canvas and takes no touches. */
@Composable
private fun ControlMini(element: InputSurfaceElement, style: InputSurfaceStyle) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { MiniSize.roundToPx() }
    val image = remember(element, style, px) {
        val view = InputSurfaceView(context, customLayout = listOf(element), surfaceStyle = style, haptics = false)
        val spec = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
        view.measure(spec, spec)
        view.layout(0, 0, px, px)
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }.asImageBitmap()
    }
    Image(image, contentDescription = null, modifier = Modifier.size(MiniSize))
}
