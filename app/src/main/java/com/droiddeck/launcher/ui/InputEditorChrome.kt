package com.droiddeck.launcher.ui

import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.droiddeck.launcher.input.stepPercent
import kotlinx.coroutines.delay

internal object EditorMotion {
    val Pop = CubicBezierEasing(0.3f, 1.5f, 0.4f, 1f)
    const val TOAST_MS = 2_500L
    const val HINT_MS = 3_000L
}

internal val EditorBarHeight = 48.dp
internal val EditorHandleSize = 40.dp
private val ChipShape = RoundedCornerShape(18.dp)
private val SaveShape = RoundedCornerShape(12.dp)
private val DialogShape = RoundedCornerShape(20.dp)
private val DialogButtonShape = RoundedCornerShape(14.dp)
private val SliderShape = RoundedCornerShape(12.dp)
private val ToastShape = RoundedCornerShape(20.dp)

/** The editor's translucent panel look: surface at 78%, a faint edge and the panel shadow. */
@Composable
internal fun Modifier.editorGlass(shape: Shape): Modifier {
    val pal = LocalPalette.current
    return shadow(12.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape)
        .background(pal.surface.copy(alpha = 0.78f)).border(1.dp, pal.onBackground.copy(alpha = 0.09f), shape)
}

/** Press feedback: .94 in 70ms, back with a 160ms pop. */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(if (pressed) 0.94f else 1f, if (pressed) Motion.tw(70) else Motion.tw(160, easing = EditorMotion.Pop), label = "press").value
}

@Composable
internal fun EditorIconButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    checked: Boolean? = null, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val scale = pressScale(src)
    val on = checked == true
    val action = if (checked != null) Modifier.toggleable(checked, src, null, enabled, Role.Switch) { onClick() }
        else Modifier.clickable(src, null, enabled, role = Role.Button, onClick = onClick)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(40.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else if (on) pal.signal.copy(alpha = 0.18f) else Color.Transparent)
            .glideBorder(hot, CircleShape, pal.signal, hotFillAlpha = 0f)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .hoverable(src).then(action).controllerConfirm(enabled, onClick)
            .semantics { contentDescription = label },
    ) { Icon(icon, contentDescription = null, tint = if (on) pal.signal else pal.onBackground, modifier = Modifier.size(22.dp)) }
}

@Composable
internal fun EditorChip(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, active: Boolean = false,
    trailing: ImageVector? = null, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val scale = pressScale(src)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.height(36.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(ChipShape)
            .background(when { hot -> pal.signal.copy(alpha = 0.14f); active -> pal.signal.copy(alpha = 0.18f); else -> pal.onBackground.copy(alpha = 0.06f) })
            .glideBorder(hot, ChipShape, pal.signal, if (active) pal.signal else pal.line2, hotFillAlpha = 0f)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .hoverable(src).clickable(src, null, enabled, role = Role.Button, onClick = onClick).controllerConfirm(enabled, onClick)
            .padding(start = 12.dp, end = if (trailing != null) 8.dp else 12.dp),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.onBackground, maxLines = 1, softWrap = false,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
        if (trailing != null) Icon(trailing, contentDescription = null, tint = pal.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

@Composable
internal fun EditorSaveButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val scale = pressScale(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.height(36.dp).graphicsLayer {
            scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f
            shadowElevation = if (enabled) 6.dp.toPx() else 0f; shape = SaveShape; clip = false
            ambientShadowColor = pal.signal; spotShadowColor = pal.signal
        }.clip(SaveShape).background(Brush.verticalGradient(listOf(pal.primary, pal.primary2)))
            .glideBorder(hot, SaveShape, pal.onBackground, hotFillAlpha = 0f)
            .hoverable(src).clickable(src, null, enabled, role = Role.Button, onClick = onClick).controllerConfirm(enabled, onClick)
            .padding(horizontal = 16.dp),
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.onPrimary, maxLines = 1) }
}

@Composable
internal fun EditorBarDivider() {
    Box(Modifier.padding(horizontal = 6.dp).size(1.dp, 24.dp).background(LocalPalette.current.line2))
}

/** One horizontal stepped slider row: label, track, value. Drags report [onSliding] so a caller can hold still while it moves. */
@Composable
internal fun PillSlider(label: String, value: Int, range: IntRange, step: Int, modifier: Modifier = Modifier, labelWidth: Dp = 60.dp,
    onSliding: (Boolean) -> Unit = {}, onChange: (Int) -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    var sliding by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onChange)
    val slidingChanged by rememberUpdatedState(onSliding)
    val span = (range.last - range.first).coerceAtLeast(1)
    val fraction = ((value - range.first).toFloat() / span).coerceIn(0f, 1f)
    val text = "$value%"
    var trackPx by remember { mutableIntStateOf(0) }
    val inset = with(LocalDensity.current) { 10.dp.toPx() }
    fun fromX(x: Float) {
        val width = trackPx - 2 * inset
        if (width > 0) change(stepPercent(range.first + ((x - inset) / width).coerceIn(0f, 1f) * span, range, step))
    }
    fun slide(on: Boolean) { if (sliding != on) { sliding = on; slidingChanged(on) } }
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().height(40.dp).clip(SliderShape)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, SliderShape, pal.signal, hotFillAlpha = 0f)
            .hoverable(src).focusable(interactionSource = src)
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                val left = key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                if (!left && key.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) false
                else {
                    if (key.action == KeyEvent.ACTION_DOWN) {
                        val next = stepPercent((value + if (left) -step else step).toFloat(), range, step)
                        if (next != value) change(next)
                    }
                    true
                }
            }
            .semantics {
                contentDescription = label
                stateDescription = text
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat())
                setProgress { next -> change(stepPercent(next, range, step)); true }
            }
            .padding(horizontal = 10.dp),
    ) {
        Text(label, fontSize = 13.sp, color = pal.onSurfaceVariant, maxLines = 1, softWrap = false, modifier = Modifier.width(labelWidth))
        Canvas(
            Modifier.weight(1f).height(32.dp).onSizeChanged { trackPx = it.width }
                .pointerInput(range, step) { detectTapGestures { fromX(it.x) } }
                .pointerInput(range, step) {
                    detectHorizontalDragGestures(onDragStart = { slide(true); fromX(it.x) }, onDragEnd = { slide(false) }, onDragCancel = { slide(false) }) { c, _ ->
                        fromX(c.position.x); c.consume()
                    }
                },
        ) {
            val start = Offset(inset, size.height / 2f)
            val end = Offset(size.width - inset, start.y)
            val thumb = Offset(start.x + (end.x - start.x) * fraction, start.y)
            drawLine(pal.line2, start, end, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            if (fraction > 0f) drawLine(pal.signal, start, thumb, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            if (hot || sliding) drawCircle(pal.signal.copy(alpha = 0.14f), radius = 14.dp.toPx(), center = thumb)
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = 10.dp.toPx(), center = thumb + Offset(0f, 1.dp.toPx()))
            drawCircle(Color(0xFFF2F4F7), radius = 10.dp.toPx(), center = thumb)
        }
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.onBackground, maxLines = 1, textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), modifier = Modifier.width(40.dp))
    }
}

@Composable
internal fun EditorHint(text: String, visible: Boolean, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    AnimatedVisibility(visible, modifier, enter = fadeIn(Motion.tw(220)), exit = fadeOut(Motion.tw(180)), label = "editor-hint") {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.height(30.dp).editorGlass(RoundedCornerShape(15.dp)).padding(start = 10.dp, end = 14.dp),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = pal.signal, modifier = Modifier.size(16.dp))
            Text(text, fontSize = 13.sp, color = pal.onBackground, maxLines = 1)
        }
    }
}

internal class EditorToast(val text: String, val icon: ImageVector? = null, val action: String? = null, val mark: Any? = null,
    val onAction: (() -> Unit)? = null)

internal class EditorToasts {
    var current by mutableStateOf<EditorToast?>(null)
        private set
    fun show(toast: EditorToast) { current = toast }
    fun hide(toast: EditorToast? = current) { if (current === toast) current = null }
}

@Composable
internal fun EditorToastHost(toasts: EditorToasts, modifier: Modifier = Modifier) {
    val toast = toasts.current
    val last = remember { arrayOfNulls<EditorToast>(1) }
    if (toast != null) last[0] = toast
    LaunchedEffect(toast) { if (toast != null) { delay(EditorMotion.TOAST_MS); toasts.hide(toast) } }
    val rise = with(LocalDensity.current) { 12.dp.roundToPx() }
    AnimatedVisibility(toast != null, modifier, enter = fadeIn(Motion.tw(220)) + slideInVertically(Motion.tw(220)) { rise },
        exit = fadeOut(Motion.tw(180)) + slideOutVertically(Motion.tw(180)) { rise }, label = "editor-toast") {
        last[0]?.let { ToastCard(it) { it.onAction?.invoke(); toasts.hide(it) } }
    }
}

@Composable
private fun ToastCard(toast: EditorToast, onAction: () -> Unit) {
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.height(40.dp).shadow(12.dp, ToastShape, ambientColor = Color.Black, spotColor = Color.Black).clip(ToastShape)
            .background(pal.surfaceVariant.copy(alpha = 0.94f)).border(1.dp, pal.line2, ToastShape)
            .padding(start = 12.dp, end = if (toast.action != null) 6.dp else 16.dp),
    ) {
        if (toast.icon != null) Icon(toast.icon, contentDescription = null, tint = pal.onBackground, modifier = Modifier.size(16.dp))
        else Box(Modifier.size(22.dp).clip(CircleShape).background(pal.good.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = pal.good, modifier = Modifier.size(14.dp))
        }
        Text(toast.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.onBackground, maxLines = 1)
        if (toast.action != null) {
            Box(Modifier.size(1.dp, 20.dp).background(pal.line2))
            ToastAction(toast.action, onAction)
        }
    }
}

@Composable
private fun ToastAction(text: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(15.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.height(30.dp).clip(shape).background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, shape, pal.signal, hotFillAlpha = 0f)
            .hoverable(src).clickable(src, null, role = Role.Button, onClick = onClick).controllerConfirm(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.signal, maxLines = 1) }
}

/** Shows [text] as a toast on [host]'s own window, so it outlives the editor dialog that closes right after saving. */
internal fun showDetachedToast(host: ComponentActivity, text: String) {
    val root = host.window?.decorView as? ViewGroup ?: return
    val view = ComposeView(host)
    view.setViewTreeLifecycleOwner(host); view.setViewTreeViewModelStoreOwner(host)
    view.setViewTreeSavedStateRegistryOwner(host); view.setViewTreeOnBackPressedDispatcherOwner(host)
    view.setContent {
        DroidDeckTheme {
            val toasts = remember { EditorToasts().apply { show(EditorToast(text)) } }
            EditorToastHost(toasts)
            LaunchedEffect(Unit) {
                delay(EditorMotion.TOAST_MS + 400)
                view.post { (view.parent as? ViewGroup)?.removeView(view) }
            }
        }
    }
    val bottom = (24 * host.resources.displayMetrics.density).toInt()
    root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = bottom })
}

@Composable
internal fun DiscardChangesDialog(title: String, body: String, keep: String, discard: String, onKeep: () -> Unit, onDiscard: () -> Unit) {
    val pal = LocalPalette.current
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(shown.currentState, shown.isIdle) { if (shown.isIdle && !shown.currentState && !shown.targetState) onKeep() }
    val close = { shown.targetState = false }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = close) {
        AnimatedVisibility(shown, enter = fadeIn(Motion.tw(180)) + scaleIn(Motion.tw(240, easing = EditorMotion.Pop), initialScale = 0.94f),
            exit = fadeOut(Motion.tw(140)) + scaleOut(Motion.tw(140), targetScale = 0.97f), label = "discard") {
            Column(
                Modifier.widthIn(max = 384.dp).fillMaxWidth().shadow(24.dp, DialogShape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(DialogShape).background(pal.surface).border(1.dp, pal.line, DialogShape).controllerBack(close).padding(24.dp),
            ) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = pal.onBackground)
                Spacer(Modifier.height(8.dp))
                Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = pal.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    DialogButton(keep, destructive = false, Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused }, onClick = close)
                    DialogButton(discard, destructive = true, onClick = onDiscard)
                }
            }
        }
        LaunchedEffect(Unit) {
            repeat(10) { withFrameNanos { }; if (focused) return@LaunchedEffect; runCatching { focus.requestFocus() } }
        }
    }
}

@Composable
private fun DialogButton(text: String, destructive: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val scale = pressScale(src)
    val fill = when {
        destructive -> if (hot) pal.error.copy(alpha = 0.85f) else pal.error
        hot -> pal.signal.copy(alpha = 0.14f)
        else -> pal.onBackground.copy(alpha = 0.06f)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(DialogButtonShape).background(fill)
            .glideBorder(hot, DialogButtonShape, if (destructive) pal.onBackground else pal.signal, if (destructive) Color.Transparent else pal.line2, hotFillAlpha = 0f)
            .hoverable(src).clickable(src, null, role = Role.Button, onClick = onClick).controllerConfirm(onClick = onClick)
            .padding(horizontal = 20.dp),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (destructive) Color(0xFF2A0707) else pal.onBackground, maxLines = 1) }
}
