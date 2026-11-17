package com.droiddeck.launcher.ui

import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.input.*
import com.droiddeck.launcher.session.SessionState
import kotlinx.coroutines.delay

internal data class SurfaceSnapshot(val elements: List<InputSurfaceElement>, val actions: Map<String, InputAction>,
    val clicks: Map<String, InputAction>, val scale: Float, val freeScale: Float, val removed: Set<String>)

private enum class SliderEdit { SCALE, SIZE, OPACITY }

private class ScaleStep(val base: List<InputSurfaceElement>, val baseFactor: Float, val result: List<InputSurfaceElement>, val factor: Float)

internal class GamepadScaler(private val context: android.content.Context, private val width: Int, private val height: Int) {
    private fun generator(scale: Float) = LegacyGamepadLayout(context, width, height, scale = scale)
    val ids = generator(1f).elements().map { it.id }.toSet()
    fun rescale(stored: List<InputSurfaceElement>, from: Float, to: Float) = generator(to).rescale(stored, generator(from))
    fun reset(stored: List<InputSurfaceElement>) = stock(stored).let { placed -> stored.map { placed[it.id] ?: it } }
    fun stock(stored: List<InputSurfaceElement>) = generator(1f).regenerate(stored).associateBy { it.id }
    val geometry by lazy { generator(1f).elements().associateBy { it.id } }
}

private fun isStock(id: String) = id.startsWith("legacy:") || id.startsWith("builtin:")

internal class SurfaceDraft(val layout: SavedInputLayout, val preset: SavedInputBindings, val defaults: List<InputSurfaceElement>? = null,
    private val gamepad: GamepadScaler? = null, snap: Boolean = true) {
    var elements by mutableStateOf(layout.elements)
    var actions by mutableStateOf(preset.elements)
    var clicks by mutableStateOf(preset.clicks)
    var removed by mutableStateOf(layout.style.removed)
    val visible get() = elements.filterNot { it.id in removed }
    var selection by mutableStateOf<List<String>>(emptyList())
        private set
    val selected get() = selection.singleOrNull()
    private var sizeBase by mutableStateOf<Map<String, InputSurfaceElement>?>(null)
    var toolsExpanded by mutableStateOf(false)
    var toolsAt by mutableStateOf<Pair<Float, Float>?>(null)
    var sizeOpen by mutableStateOf(false)
    /** Controls the last add() placed or restored, for the canvas pop-in. */
    var placed by mutableStateOf<List<String>>(emptyList(), neverEqualPolicy())
        private set
    var snap by mutableStateOf(snap)
    var adding by mutableStateOf(false)
    var history by mutableStateOf<List<SurfaceSnapshot>>(emptyList())
    var layoutScale by mutableFloatStateOf(layout.scale)
    private var freeScale by mutableFloatStateOf(1f)
    private var scaleStep: ScaleStep? = null
    private var sliding: SliderEdit? = null
    private val rescales get() = gamepad != null && elements.any { it.id in gamepad.ids }
    private var scaleFactor
        get() = if (rescales) layoutScale else freeScale
        set(value) { if (rescales) layoutScale = value else freeScale = value }
    val scale get() = (scaleFactor * 100).roundToInt()
    val positioned get() = (if (rescales) gamepad!!.reset(elements) else defaults?.let { elements.withPositionsOf(it) })
        ?.takeIf { it != elements || rescales && layoutScale != 1f }
    val element get() = elements.firstOrNull { it.id == selected }
    val dirty get() = elements != layout.elements || actions != preset.elements || clicks != preset.clicks || layoutScale != layout.scale ||
        removed != layout.style.removed
    var redoable by mutableStateOf<List<SurfaceSnapshot>>(emptyList())
        private set
    /** Undo steps that changed something, for the discard prompt. */
    val changeCount get() = (history + snapshot()).zipWithNext().count { (a, b) -> a != b }
    private fun snapshot() = SurfaceSnapshot(elements, actions, clicks, layoutScale, freeScale, removed)
    fun checkpoint() {
        sliding = null
        history = (history + snapshot()).takeLast(40)
        redoable = emptyList()
    }
    private fun slide(edit: SliderEdit) { if (sliding != edit) { checkpoint(); sliding = edit } }
    private fun load(it: SurfaceSnapshot) {
        sliding = null
        elements = it.elements; actions = it.actions; clicks = it.clicks; layoutScale = it.scale; freeScale = it.freeScale; removed = it.removed
        val kept = selection.filter { id -> visible.any { it.id == id } }
        select(if (kept.size > 1) visible.groupOf(kept.first()) else kept)
    }
    fun undo() {
        sliding = null
        val previous = history.lastOrNull() ?: return
        redoable = redoable + snapshot()
        history = history.dropLast(1)
        load(previous)
    }
    fun redo() {
        sliding = null
        val next = redoable.lastOrNull() ?: return
        history = (history + snapshot()).takeLast(40)
        redoable = redoable.dropLast(1)
        load(next)
    }
    fun deleteSelection() {
        if (selection.isEmpty()) return
        checkpoint()
        val (stock, added) = selection.partition(::isStock)
        removed = removed + stock
        elements = elements.filterNot { it.id in added }; actions = actions - added.toSet(); clicks = clicks - added.toSet()
        select(emptyList())
    }
    val full get() = elements.size >= 64
    val canAdd get() = !full || removed.any { id -> elements.any { it.id == id } }
    private fun hidden(choice: InputAddChoice) = choice.stock.filter { id -> id in removed && elements.any { it.id == id } }
    fun available(choice: InputAddChoice) = !full || hidden(choice).isNotEmpty()
    fun add(choice: InputAddChoice, aspect: Float) {
        val hidden = hidden(choice)
        if (hidden.isEmpty() && full) return
        checkpoint()
        if (hidden.isNotEmpty()) {
            removed = removed - hidden.toSet()
            select(hidden)
            placed = hidden
            return
        }
        val height = when (choice.kind) {
            InputElementKind.STICK, InputElementKind.DPAD -> .36f
            InputElementKind.TRACKPAD -> .5f
            else -> .16f
        }
        val pill = choice.kind == InputElementKind.TRIGGER || choice.control == InputControl.LB || choice.control == InputControl.RB
        val width = (height / aspect * if (pill) 2f else 1f).coerceIn(.03f, 1f)
        val (x, y) = freeSurfaceSpot(visible.map { SurfaceBounds(it.x, it.y, it.x + it.width, it.y + it.height) }, width, height)
        val element = InputSurfaceElement(choice.kind.control, x, y, width, height, newInputId(), choice.kind, choice.name,
            icon = choice.icon, buttonShape = if (pill) InputButtonShape.PILL else InputButtonShape.CIRCLE)
        elements = elements + element
        actions = actions + (element.id to choice.action)
        select(listOf(element.id))
        placed = listOf(element.id)
    }
    fun scaleAll(percent: Int) {
        if (percent == scale) return
        slide(SliderEdit.SCALE)
        // `scaled` clamps to the screen, so scaling an already scaled layout is lossy: reuse the pre-scale base while the elements are unchanged.
        val (base, from) = scaleStep?.takeIf { it.result == elements && it.factor == scaleFactor }?.let { it.base to it.baseFactor }
            ?: (elements to scaleFactor)
        val factor = percent / 100f
        elements = if (rescales) {
            val others = base.filterNot { it.id in gamepad!!.ids }.scaled(factor / from).associateBy { it.id }
            gamepad!!.rescale(base, from, factor).map { others[it.id] ?: it }
        } else base.scaled(factor / from)
        scaleFactor = factor
        scaleStep = ScaleStep(base, from, elements, factor)
    }
    fun select(ids: List<String>) {
        if (ids == selection) return
        sliding = null
        selection = ids; sizeBase = elements.filter { it.id in ids }.associateBy { it.id }
    }
    private val stockGeometry by lazy { defaults.orEmpty().associateBy { it.id } + gamepad?.geometry.orEmpty() }
    private fun sizeReference() = stockGeometry.takeIf { stock -> selection.isNotEmpty() && selection.all { it in stock } } ?: sizeBase
    val selectionSize get() = sizeReference()?.let { elements.sizePercent(selection, it).roundToInt() }
    fun resizeSelection(percent: Int) {
        val reference = sizeReference() ?: return
        if (percent == selectionSize) return
        slide(SliderEdit.SIZE)
        elements = elements.scaleSelection(selection, percent / elements.sizePercent(selection, reference))
    }
    val selectionOpacity get() = elements.firstOrNull { it.id in selection }?.opacity ?: 1f
    fun setSelectionOpacity(percent: Int) {
        if (elements.filter { it.id in selection }.all { (it.opacity * 100).roundToInt() == percent }) return
        slide(SliderEdit.OPACITY)
        elements = elements.map { if (it.id in selection) it.copy(opacity = percent / 100f) else it }
    }
    private val hiddenStock get() = removed.filter { id -> elements.any { it.id == id } }
    val resettable get() = positioned != null || hiddenStock.isNotEmpty()
    fun resetLayout() {
        if (!resettable) return
        checkpoint()
        positioned?.let { elements = it; scaleFactor = 1f }
        removed = removed - hiddenStock.toSet()
    }
}

@Suppress("DEPRECATION")
private fun displayAspect(display: Display): Float? = android.util.DisplayMetrics().also(display::getRealMetrics)
    .takeIf { it.widthPixels > 0 && it.heightPixels > 0 }?.let { (it.widthPixels.toFloat() / it.heightPixels).coerceIn(.05f, 20f) }

@Composable
fun InputSurfaceDialog(profile: String, onDismiss: () -> Unit, onChanged: () -> Unit,
    role: InputRole = InputRole.SECONDARY, layoutId: String? = null, bindingsId: String? = null) {
    val context = LocalContext.current
    val host = LocalInputActivity.current ?: context.findActivity() as? ComponentActivity
    val library = remember { InputLibraries.read(context) }
    val savedLayout = remember { library.layouts.first { it.id == (layoutId ?: library.profile(profile).parts.getValue(role).layout) } }
    val savedBindings = remember { library.preset(bindingsId ?: library.profile(profile).binding(role)) }
    val layoutUsers = library.usersOfLayout(savedLayout.id).size
    val draft = remember {
        val (display, metrics) = InputLibraries.primaryDisplay(context)
        val gamepad = savedLayout.elements.takeIf { e -> e.any { it.id.startsWith("legacy:") } }?.let {
            val (w, h) = OnScreenLayout.generatedSize(context, savedLayout, metrics.widthPixels, metrics.heightPixels) ?: (metrics.widthPixels to metrics.heightPixels)
            GamepadScaler(display, w, h)
        }
        SurfaceDraft(savedLayout, savedBindings, InputLibraries.defaultPositions(context, library, savedLayout), gamepad, ControllerPrefs.snap(context))
    }
    val manager = remember { context.getSystemService(DisplayManager::class.java) }
    var revision by remember { mutableIntStateOf(0) }
    var discard by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val toasts = remember { EditorToasts() }
    val target = remember(revision, role) {
        if (host is SessionActivity) host.inputEditorDisplay(role)
        else if (role == InputRole.PRIMARY) host?.window?.decorView?.display
        else {
            val displays = SecondScreenDisplays.available(manager, host?.window?.decorView?.display?.displayId ?: Display.DEFAULT_DISPLAY)
            manager.getDisplay(displays.firstOrNull { it.id == SessionState.secondScreenDisplay }?.id ?: displays.firstOrNull()?.id ?: -1)
        }
    }
    val remote = target != null && target.displayId != host?.window?.decorView?.display?.displayId
    fun leave() { if (draft.dirty) discard = true else onDismiss() }
    fun delete() {
        if (draft.selection.isEmpty()) return
        val name = draft.visible.selectedGroup(draft.selection)?.let { context.getString(it.label()) }
            ?: draft.elements.firstOrNull { it.id in draft.selection }?.name.orEmpty()
        draft.deleteSelection()
        val mark = draft.history
        toasts.show(EditorToast(context.getString(R.string.input_editor_deleted, name), Icons.Outlined.Delete,
            context.getString(R.string.input_editor_undo), mark) { if (draft.history === mark) draft.undo() })
    }
    LaunchedEffect(draft.history) { toasts.current?.let { if (it.mark != null && it.mark !== draft.history) toasts.hide(it) } }
    fun save() {
        runCatching {
            InputLibraries.update(context) { latest ->
                val l = latest.layouts.first { it.id == savedLayout.id }
                val p = latest.preset(savedBindings.id)
                latest.withLayout(l.copy(elements = draft.elements, aspect = target?.let(::displayAspect) ?: l.aspect, scale = draft.layoutScale,
                    style = l.style.copy(removed = draft.removed.filter { id -> draft.elements.any { it.id == id } }.toSet()))).withPreset(p.copy(elements = draft.actions.filterKeys { id -> draft.elements.any { it.id == id } },
                    clicks = draft.clicks.filterKeys { id -> draft.elements.any { it.id == id && it.hasClick } }))
            }
        }.onSuccess {
            onChanged()
            host?.let { showDetachedToast(it, context.getString(R.string.input_editor_saved)) }
            onDismiss()
        }.onFailure { failure = it.message ?: context.getString(R.string.input_save_failed) }
    }
    DisposableEffect(manager) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) { revision++ }
            override fun onDisplayRemoved(id: Int) { revision++ }
            override fun onDisplayChanged(id: Int) = Unit
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    if (remote && host != null) DisposableEffect(target!!.displayId, host) {
        val presentation = SurfaceEditorPresentation(host, target, transparent = host is SessionActivity && role == InputRole.PRIMARY) {
            SurfaceCanvas(draft, transparent = host is SessionActivity && role == InputRole.PRIMARY, onDelete = ::delete)
        }
        (host as? SessionActivity)?.inputWindowShown(target.displayId)
        runCatching { presentation.show() }.onFailure { failure = it.message }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !host.screenIsOff()) presentation.hide()
            if (event == Lifecycle.Event.ON_START) runCatching { presentation.show() }
        }
        host.lifecycle.addObserver(observer)
        onDispose {
            host.lifecycle.removeObserver(observer)
            presentation.dismiss()
            (host as? SessionActivity)?.inputWindowClosed(target.displayId)
        }
    }
    // Compose 1.6 custom dialog sizing subtracts system bars even when they are hidden.
    Dialog(onDismissRequest = ::leave, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        val editorWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(editorWindow) {
            editorWindow?.prepareInputEditor()
            editorWindow?.setWindowAnimations(0)
            if (host is SessionActivity && role == InputRole.PRIMARY) editorWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            onDispose { }
        }
        Surface(Modifier.fillMaxSize(), color = if (host is SessionActivity && role == InputRole.PRIMARY && !remote)
            androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface) {
            BackHandler { if (draft.sizeOpen) draft.sizeOpen = false else leave() }
            when {
                target == null -> SettingsPage(rememberMenuHost(), savedLayout.name, ::leave) {
                    Text(stringResource(R.string.input_display_unavailable))
                    PrimaryButton(stringResource(R.string.gesture_save_changes)) { save() }
                }
                remote -> Box(Modifier.fillMaxSize()) {
                    SettingsPage(rememberMenuHost(), stringResource(R.string.input_editor_remote), ::leave,
                        lede = if (layoutUsers > 1) stringResource(R.string.input_editor_shared, layoutUsers) else savedLayout.name) {
                        EditorMenuBar(draft, ::save, ::leave, top = true)
                        if (draft.sizeOpen) LayoutSizeCard(draft)
                        SurfaceSelection(draft, ::delete)
                    }
                    EditorToastHost(toasts, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                }
                else -> Box(Modifier.fillMaxSize()) {
                    SurfaceCanvas(draft, transparent = host is SessionActivity && role == InputRole.PRIMARY, onDelete = ::delete)
                    FloatingSurfaceTools(draft, toasts, ::save, ::leave)
                }
            }
        }
    }
    if (draft.adding) AddControlDialog(draft, target?.let(::displayAspect) ?: (16f / 9f))
    if (discard) DiscardChangesDialog(stringResource(R.string.input_editor_discard_title),
        draft.changeCount.let { if (it > 0) pluralStringResource(R.plurals.input_editor_discard_count, it, it) else stringResource(R.string.input_editor_discard_unsaved) },
        stringResource(R.string.input_editor_keep_editing), stringResource(R.string.input_editor_discard_confirm),
        onKeep = { discard = false }, onDiscard = onDismiss)
    failure?.let { AlertDialog(onDismissRequest = { failure = null }, title = { Text(stringResource(R.string.input_save_failed)) }, text = { Text(it) },
        confirmButton = { PadTextButton(onClick = { failure = null }) { Text(stringResource(R.string.common_ok)) } }) }
}

@Composable
private fun AddControlDialog(draft: SurfaceDraft, aspect: Float) {
    val context = LocalContext.current
    val trackpads = draft.layout.supports(InputRole.SECONDARY) || draft.elements.any { it.kind == InputElementKind.TRACKPAD }
    AlertDialog(onDismissRequest = { draft.adding = false }, title = { Text(stringResource(R.string.input_add_element)) },
        confirmButton = {}, dismissButton = { PadTextButton(onClick = { draft.adding = false }) { Text(stringResource(R.string.common_close)) } },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            for (choice in remember(trackpads) { inputAddChoices(context, trackpads) }) PadTextButton(
                onClick = { draft.add(choice, aspect); draft.adding = false; draft.toolsExpanded = false }, enabled = draft.available(choice),
                modifier = Modifier.fillMaxWidth().testTag("input-add-${choice.key}")) { Text(choice.name, Modifier.weight(1f)) }
        } })
}

@Composable
private fun SurfaceCanvas(draft: SurfaceDraft, transparent: Boolean = false, onDelete: () -> Unit) {
    val popped = remember { arrayOf(draft.placed) }
    AndroidView(factory = { InputSurfaceEditorView(it) }, modifier = Modifier.fillMaxSize().testTag("input-layout-canvas"),
        update = { view ->
            view.setBackgroundColor(if (transparent) android.graphics.Color.TRANSPARENT else android.graphics.Color.rgb(10, 11, 13))
            view.style = draft.layout.style.copy(removed = emptySet()); view.elements = draft.visible; view.selection = draft.selection; view.snap = draft.snap
            view.isEnabled = !draft.adding
            view.sizeReadout = { draft.selectionSize }
            if (draft.placed !== popped[0]) { popped[0] = draft.placed; view.popIn(draft.placed) }
            view.onSelect = { draft.select(it) }
            view.onBeginEdit = draft::checkpoint
            view.onDelete = onDelete
            view.onChange = { changed ->
                val byId = changed.associateBy { it.id }
                draft.elements = draft.elements.map { byId[it.id] ?: it }
            }
        })
}

private val PillWidth = 240.dp
/** How far the selection's handles and delete badge reach past its controls. */
private val SelectionReach = 24.dp

@Composable
private fun FloatingSurfaceTools(draft: SurfaceDraft, toasts: EditorToasts, onSave: () -> Unit, onLeave: () -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        val density = LocalDensity.current
        val screenWidth = with(density) { maxWidth.toPx() }
        val screenHeight = with(density) { maxHeight.toPx() }
        val margin = with(density) { 8.dp.toPx() }
        val handle = with(density) { EditorHandleSize.toPx() }
        val barHeight = with(density) { EditorBarHeight.toPx() }
        var dragging by remember { mutableStateOf(false) }
        val reach = with(density) { SelectionReach.toPx() }
        val selected = draft.visible.boundsOf(draft.selection)?.let {
            SurfaceBounds((it.left - reach / screenWidth).coerceAtLeast(0f), (it.top - reach / screenHeight).coerceAtLeast(0f),
                (it.right + reach / screenWidth).coerceAtMost(1f), (it.bottom + reach / screenHeight).coerceAtMost(1f))
        }
        val bounds = draft.visible.map { SurfaceBounds(it.x, it.y, it.x + it.width, it.y + it.height) }
        val (hx, hy) = placeSurfaceTools(screenWidth, screenHeight, handle, handle, margin, bounds, if (dragging) null else selected, draft.toolsAt)
        val handleAt by rememberUpdatedState(Offset(hx, hy))
        val open = draft.toolsExpanded
        var barSize by remember { mutableStateOf(IntSize.Zero) }
        var chip by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        val (bx, by) = dockSurfaceMenu(screenWidth, screenHeight, barSize.width.toFloat(), barHeight, margin, bounds, selected,
            menuOnTop(hy, handle, screenHeight))
        val top = menuOnTop(by, barHeight, screenHeight)
        val barAt = remember { Animatable(Offset(bx, by), Offset.VectorConverter) }
        var settled by remember { mutableStateOf(false) }
        LaunchedEffect(bx, by, open, barSize.width) {
            val target = Offset(bx, by)
            if (open && settled) barAt.animateTo(target, Motion.tw(260))
            else { barAt.snapTo(target); settled = open && barSize.width > 0 }
        }
        val menu = if (open) SurfaceBounds(bx, by, bx + barSize.width, by + barHeight) else SurfaceBounds(hx, hy, hx + handle, hy + handle)
        val handleFocus = remember { FocusRequester() }
        val collapseFocus = remember { FocusRequester() }
        var menuFocused by remember { mutableStateOf(false) }
        var moveFocus by remember { mutableStateOf(false) }
        fun toggle() { moveFocus = menuFocused; draft.sizeOpen = false; draft.toolsExpanded = !open }
        var ready by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { withFrameNanos { }; delay(Motion.ms(120).toLong()); ready = true }
        LaunchedEffect(open) {
            if (moveFocus) { withFrameNanos { }; runCatching { (if (open) collapseFocus else handleFocus).requestFocus() } }
            moveFocus = false
        }
        val rise = with(density) { 16.dp.roundToPx() }
        AnimatedVisibility(!open && ready, Modifier.offset { IntOffset(hx.roundToInt(), hy.roundToInt()) }.testTag("input-editor-panel"),
            enter = fadeIn(Motion.tw(160)) + scaleIn(Motion.tw(160, easing = EditorMotion.Pop), initialScale = 0.8f),
            exit = fadeOut(Motion.tw(120)), label = "editor-handle") {
            Box(Modifier.editorGlass(CircleShape)) {
                EditorIconButton(if (hy + handle / 2 < screenHeight / 2) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    stringResource(R.string.input_editor_open_menu),
                    Modifier.testTag("input-editor-tools").focusRequester(handleFocus).onFocusChanged { menuFocused = it.isFocused }
                        .pointerInput(screenWidth, screenHeight) {
                            var dragPosition = Offset.Zero
                            detectDragGestures(onDragStart = { dragPosition = handleAt; dragging = true },
                                onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { change, delta ->
                                change.consume()
                                dragPosition = Offset((dragPosition.x + delta.x).coerceIn(margin, (screenWidth - handle - margin).coerceAtLeast(margin)),
                                    (dragPosition.y + delta.y).coerceIn(margin, (screenHeight - handle - margin).coerceAtLeast(margin)))
                                draft.toolsAt = dragPosition.x / screenWidth to dragPosition.y / screenHeight
                            }
                        }, onClick = ::toggle)
            }
        }
        AnimatedVisibility(open, Modifier.offset { IntOffset(barAt.value.x.roundToInt(), barAt.value.y.roundToInt()) }.testTag("input-editor-panel"),
            enter = fadeIn(Motion.tw(260)) + slideInVertically(Motion.tw(260)) { if (top) -rise else rise },
            exit = fadeOut(Motion.tw(180)), label = "editor-menu") {
            EditorMenuBar(draft, onSave, onLeave, top, Modifier.widthIn(max = maxWidth - 16.dp).onSizeChanged { barSize = it },
                collapse = ::toggle, collapseModifier = Modifier.focusRequester(collapseFocus).onFocusChanged { menuFocused = it.isFocused },
                onChipPlaced = { chip = it.translate(-origin) })
        }
        var cardSize by remember { mutableStateOf(IntSize.Zero) }
        val cardGap = with(density) { 8.dp.toPx() }
        val cardX = ((chip?.center?.x ?: (bx + barSize.width / 2)) - cardSize.width / 2)
            .coerceIn(margin, (screenWidth - cardSize.width - margin).coerceAtLeast(margin))
        val cardY = if (top) by + barHeight + cardGap else by - cardGap - cardSize.height
        val card = SurfaceBounds(cardX, cardY, cardX + cardSize.width, cardY + cardSize.height).takeIf { open && draft.sizeOpen }
        AnimatedVisibility(open && draft.sizeOpen, Modifier.offset { IntOffset(cardX.roundToInt(), cardY.roundToInt()) }.onSizeChanged { cardSize = it },
            enter = fadeIn(Motion.tw(160)) + scaleIn(Motion.tw(160, easing = EditorMotion.Pop), initialScale = 0.94f),
            exit = fadeOut(Motion.tw(120)), label = "layout-size") { LayoutSizeCard(draft) }
        var hint by remember { mutableStateOf(false) }
        val idle = draft.selection.isEmpty()
        LaunchedEffect(idle) {
            hint = idle
            if (idle) { delay(EditorMotion.HINT_MS); hint = false }
        }
        var hintSize by remember { mutableStateOf(IntSize.Zero) }
        val gap = with(density) { 10.dp.toPx() }
        val hintBelow = if (open) top else hy + handle / 2 < screenHeight / 2
        val hintX = (menu.centerX - hintSize.width / 2).coerceIn(margin, (screenWidth - hintSize.width - margin).coerceAtLeast(margin))
        val hintY = if (hintBelow) menu.bottom + gap else menu.top - gap - hintSize.height
        EditorHint(stringResource(R.string.input_editor_tap_hint), hint && !draft.adding,
            Modifier.offset { IntOffset(hintX.roundToInt(), hintY.roundToInt()) }.onSizeChanged { hintSize = it }.testTag("input-editor-hint"))
        if (selected != null) {
            val pillWidth = with(density) { PillWidth.toPx() }
            var pillHeight by remember { mutableIntStateOf(0) }
            var sliding by remember { mutableStateOf(false) }
            val sel = SurfaceBounds(selected.left * screenWidth, selected.top * screenHeight, selected.right * screenWidth, selected.bottom * screenHeight)
            val others = draft.visible.filter { it.id !in draft.selection }
                .map { SurfaceBounds(it.x * screenWidth, it.y * screenHeight, (it.x + it.width) * screenWidth, (it.y + it.height) * screenHeight) }
            val placed = placeSelectionPill(screenWidth, screenHeight, pillWidth, pillHeight.toFloat().takeIf { it > 0f } ?: with(density) { 88.dp.toPx() },
                margin, with(density) { 4.dp.toPx() }, sel, listOfNotNull(menu, card) + others)
            val held = remember { arrayOf(placed) }
            if (!sliding) held[0] = placed
            val (px, py) = held[0]
            val shift = with(density) { 16.dp.toPx() }
            val (dx, dy) = when {
                px + pillWidth <= sel.left -> -shift to 0f
                px >= sel.right -> shift to 0f
                py >= sel.bottom -> 0f to shift
                else -> 0f to -shift
            }
            key(draft.selection) {
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) { appear.animateTo(1f, Motion.tw(260, 120)) }
                Column(Modifier.offset { IntOffset(px.roundToInt(), py.roundToInt()) }.width(PillWidth).onSizeChanged { pillHeight = it.height }
                    .graphicsLayer { alpha = appear.value; translationX = (1 - appear.value) * dx; translationY = (1 - appear.value) * dy }
                    .testTag("input-selection-pill").editorGlass(RoundedCornerShape(20.dp)).padding(4.dp)) {
                    SelectionSliders(draft) { sliding = it }
                }
            }
        }
        EditorToastHost(toasts, Modifier.align(Alignment.BottomCenter).padding(bottom = if (open && !top) EditorBarHeight + 16.dp else 14.dp))
    }
}

@Composable
private fun EditorMenuBar(draft: SurfaceDraft, onSave: () -> Unit, onLeave: () -> Unit, top: Boolean, modifier: Modifier = Modifier,
    collapse: (() -> Unit)? = null, collapseModifier: Modifier = Modifier, onChipPlaced: (androidx.compose.ui.geometry.Rect) -> Unit = {}) {
    val context = LocalContext.current
    val sizeLabel = stringResource(R.string.input_editor_layout_size)
    Row(modifier.height(EditorBarHeight).editorGlass(RoundedCornerShape(24.dp)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (collapse != null) EditorIconButton(if (top) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, stringResource(R.string.input_editor_hide_menu),
            collapseModifier.testTag("input-editor-tools"), onClick = collapse)
        EditorIconButton(Icons.Filled.Close, stringResource(R.string.common_close), Modifier.testTag("input-editor-close"), onClick = onLeave)
        EditorIconButton(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.input_editor_undo), Modifier.testTag("input-editor-undo"),
            enabled = draft.history.isNotEmpty()) { draft.undo() }
        EditorIconButton(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.input_editor_redo), Modifier.testTag("input-editor-redo"),
            enabled = draft.redoable.isNotEmpty()) { draft.redo() }
        EditorBarDivider()
        EditorChip(stringResource(R.string.input_editor_add), enabled = draft.canAdd, active = draft.adding) { draft.adding = true }
        if (draft.defaults != null) EditorIconButton(Icons.Filled.RestartAlt, stringResource(R.string.input_reset_layout), Modifier.testTag("input-editor-reset"),
            enabled = draft.resettable) { draft.resetLayout() }
        EditorIconButton(Icons.Filled.GridOn, stringResource(R.string.ctrl_editor_snap), Modifier.testTag("input-editor-snap"), checked = draft.snap) {
            draft.snap = !draft.snap
            ControllerPrefs.setSnap(context, draft.snap)
        }
        if (draft.elements.isNotEmpty()) EditorChip("${draft.scale}%", Modifier.semantics { contentDescription = sizeLabel }.testTag("input-editor-size")
            .onGloballyPositioned { onChipPlaced(it.boundsInRoot()) }, active = draft.sizeOpen,
            trailing = if (top) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess) { draft.sizeOpen = !draft.sizeOpen }
        EditorSaveButton(stringResource(R.string.input_editor_save), draft.dirty, Modifier.padding(start = 4.dp).testTag("input-editor-save"), onSave)
    }
}

@Composable
private fun LayoutSizeCard(draft: SurfaceDraft) {
    val focus = remember { FocusRequester() }
    val keys = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    Box(Modifier.width(280.dp).editorGlass(RoundedCornerShape(16.dp)).controllerBack { draft.sizeOpen = false }.padding(4.dp)) {
        PillSlider(stringResource(R.string.input_editor_layout_size), draft.scale, 80..125, 5,
            Modifier.focusRequester(focus).testTag("input-editor-scale"), labelWidth = 84.dp) { draft.scaleAll(it) }
    }
    LaunchedEffect(Unit) { if (keys) { withFrameNanos { }; runCatching { focus.requestFocus() } } }
}

@Composable
private fun SurfaceSelection(draft: SurfaceDraft, onDelete: () -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val group = draft.visible.selectedGroup(draft.selection)
        val e = draft.element
        when {
            group != null -> {
                Text(stringResource(group.label()), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.input_group_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecondaryButton(stringResource(R.string.input_delete_element), compact = true, modifier = Modifier.testTag("input-remove-selection"), onClick = onDelete)
            }
            e != null -> {
                Text(e.name, style = MaterialTheme.typography.titleMedium)
                SecondaryButton(stringResource(R.string.input_delete_element), compact = true, modifier = Modifier.testTag("input-remove-selection"), onClick = onDelete)
            }
            else -> Text(stringResource(R.string.input_editor_tap_hint))
        }
        if (group != null || e != null) SelectionSliders(draft)
    }
}

@Composable
private fun SelectionSliders(draft: SurfaceDraft, onSliding: (Boolean) -> Unit = {}) {
    draft.selectionSize?.let { size ->
        PillSlider(stringResource(R.string.input_selection_size), size, selectionSizeRange(size), 5, Modifier.testTag("input-selection-size"),
            onSliding = onSliding) { draft.resizeSelection(it) }
    }
    PillSlider(stringResource(R.string.ctrl_opacity), (draft.selectionOpacity * 100).roundToInt().coerceIn(20, 100), 20..100, 5,
        Modifier.testTag("input-selection-opacity"), onSliding = onSliding) { draft.setSelectionOpacity(it) }
}

private fun InputControlGroup.label() = when (this) {
    InputControlGroup.DPAD -> R.string.input_group_dpad
    InputControlGroup.FACE -> R.string.input_group_face
    InputControlGroup.LEFT_SHOULDER -> R.string.input_group_left_shoulder
    InputControlGroup.RIGHT_SHOULDER -> R.string.input_group_right_shoulder
    InputControlGroup.LEFT_STICK -> R.string.input_group_left_stick
    InputControlGroup.RIGHT_STICK -> R.string.input_group_right_stick
}

private class SurfaceEditorPresentation(private val host: ComponentActivity, display: Display, private val transparent: Boolean, private val content: @Composable () -> Unit) : Presentation(host, display) {
    private var view: ComposeView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window?.prepareInputEditor()
        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(host); it.setViewTreeViewModelStoreOwner(host)
            it.setViewTreeSavedStateRegistryOwner(host); it.setViewTreeOnBackPressedDispatcherOwner(host)
        }
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        view = ComposeView(context).also { it.setContent { DroidDeckTheme {
            Surface(Modifier.fillMaxSize(), color = if (transparent) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface) { content() }
        } }; setContentView(it) }
    }
    override fun dismiss() { view?.disposeComposition(); view = null; super.dismiss() }
}

@Suppress("DEPRECATION")
internal fun Window.prepareInputEditor() {
    addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
    setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
}
