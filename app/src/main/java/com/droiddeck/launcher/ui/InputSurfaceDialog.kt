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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    fun add(choice: InputAddChoice, aspect: Float) {
        checkpoint()
        val hidden = choice.stock.filter { id -> id in removed && elements.any { it.id == id } }
        if (hidden.isNotEmpty()) {
            removed = removed - hidden.toSet()
            select(hidden)
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
    fun save() {
        runCatching {
            InputLibraries.update(context) { latest ->
                val l = latest.layouts.first { it.id == savedLayout.id }
                val p = latest.preset(savedBindings.id)
                latest.withLayout(l.copy(elements = draft.elements, aspect = target?.let(::displayAspect) ?: l.aspect, scale = draft.layoutScale,
                    style = l.style.copy(removed = draft.removed.filter { id -> draft.elements.any { it.id == id } }.toSet()))).withPreset(p.copy(elements = draft.actions.filterKeys { id -> draft.elements.any { it.id == id } },
                    clicks = draft.clicks.filterKeys { id -> draft.elements.any { it.id == id && it.hasClick } }))
            }
        }.onSuccess { onChanged(); onDismiss() }.onFailure { failure = it.message ?: context.getString(R.string.input_save_failed) }
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
            SurfaceCanvas(draft, transparent = host is SessionActivity && role == InputRole.PRIMARY)
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
            if (host is SessionActivity && role == InputRole.PRIMARY) editorWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            onDispose { }
        }
        Surface(Modifier.fillMaxSize(), color = if (host is SessionActivity && role == InputRole.PRIMARY && !remote)
            androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface) {
            BackHandler { leave() }
            when {
                target == null -> SettingsPage(rememberMenuHost(), savedLayout.name, ::leave) {
                    Text(stringResource(R.string.input_display_unavailable))
                    PrimaryButton(stringResource(R.string.gesture_save_changes)) { save() }
                }
                remote -> SettingsPage(rememberMenuHost(), stringResource(R.string.input_editor_remote), ::leave,
                    lede = if (layoutUsers > 1) stringResource(R.string.input_editor_shared, layoutUsers) else savedLayout.name) {
                    SurfaceToolbar(draft, ::save, ::leave)
                    SurfaceSelection(draft)
                }
                else -> Box(Modifier.fillMaxSize()) {
                    SurfaceCanvas(draft, transparent = host is SessionActivity && role == InputRole.PRIMARY)
                    FloatingSurfaceTools(draft, ::save, ::leave)
                }
            }
        }
    }
    if (draft.adding) AddControlDialog(draft, target?.let(::displayAspect) ?: (16f / 9f))
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.input_editor_discard)) },
        confirmButton = { PadTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { PadTextButton(onClick = { discard = false }) { Text(stringResource(R.string.common_cancel)) } })
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
                onClick = { draft.add(choice, aspect); draft.adding = false; draft.toolsExpanded = false },
                modifier = Modifier.fillMaxWidth().testTag("input-add-${choice.key}")) { Text(choice.name, Modifier.weight(1f)) }
        } })
}

@Composable
private fun SurfaceCanvas(draft: SurfaceDraft, transparent: Boolean = false) {
    AndroidView(factory = { InputSurfaceEditorView(it) }, modifier = Modifier.fillMaxSize().testTag("input-layout-canvas"),
        update = { view ->
            view.setBackgroundColor(if (transparent) android.graphics.Color.TRANSPARENT else android.graphics.Color.rgb(10, 11, 13))
            view.style = draft.layout.style.copy(removed = emptySet()); view.elements = draft.visible; view.selection = draft.selection; view.snap = draft.snap
            view.isEnabled = !draft.adding
            view.onSelect = { draft.select(it) }
            view.onBeginEdit = draft::checkpoint
            view.onDelete = draft::deleteSelection
            view.onChange = { changed ->
                val byId = changed.associateBy { it.id }
                draft.elements = draft.elements.map { byId[it.id] ?: it }
            }

        })
}

@Composable
private fun FloatingSurfaceTools(draft: SurfaceDraft, onSave: () -> Unit, onLeave: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenWidth = with(density) { maxWidth.toPx() }
        val screenHeight = with(density) { maxHeight.toPx() }
        val margin = with(density) { 8.dp.toPx() }
        var headerHeight by remember { mutableIntStateOf(0) }
        var contentHeight by remember { mutableIntStateOf(0) }
        var dragging by remember { mutableStateOf(false) }
        val badge = with(density) { 50.dp.toPx() }
        val selected = draft.visible.boundsOf(draft.selection)?.let {
            SurfaceBounds(it.left, (it.top - badge / screenHeight).coerceAtLeast(0f), (it.right + badge / screenWidth).coerceAtMost(1f), it.bottom)
        }
        val expandedWidth = with(density) {
            surfaceToolsWidth(screenWidth, margin, selected, minOf(280.dp, maxWidth - 16.dp).toPx(), minOf(420.dp, maxWidth - 16.dp).toPx()).toDp()
        }
        val targetWidth = with(density) { (if (draft.toolsExpanded) expandedWidth else 152.dp).toPx() }
        val targetHeight = (headerHeight.toFloat().coerceAtLeast(with(density) { 52.dp.toPx() }) + if (draft.toolsExpanded) contentHeight else 0)
            .coerceAtMost(screenHeight - 2 * margin)
        val bounds = draft.visible.map { SurfaceBounds(it.x, it.y, it.x + it.width, it.y + it.height) }
        val (x, y) = placeSurfaceTools(screenWidth, screenHeight, targetWidth, targetHeight, margin, bounds,
            if (dragging) null else selected, draft.toolsAt)
        val position by rememberUpdatedState(Offset(x, y))
        val panelWidth by animateDpAsState(if (draft.toolsExpanded) expandedWidth else 152.dp, label = "input-tools-width")
        Surface(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(panelWidth).clipToBounds()
            .heightIn(max = maxHeight - 16.dp).testTag("input-editor-panel"),
            shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp, shadowElevation = 10.dp) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("input-editor-tools").onSizeChanged { headerHeight = it.height }
                    .pointerInput(screenWidth, screenHeight, targetWidth, targetHeight) {
                        var dragPosition = Offset.Zero
                        detectDragGestures(onDragStart = { dragPosition = position; dragging = true },
                            onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { change, delta ->
                            change.consume()
                            dragPosition = Offset((dragPosition.x + delta.x).coerceIn(margin, (screenWidth - targetWidth - margin).coerceAtLeast(margin)),
                                (dragPosition.y + delta.y).coerceIn(margin, (screenHeight - targetHeight - margin).coerceAtLeast(margin)))
                            draft.toolsAt = dragPosition.x / screenWidth to dragPosition.y / screenHeight
                        }
                    }
                    .clickable { draft.toolsExpanded = !draft.toolsExpanded }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("⠿", color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(if (draft.toolsExpanded) R.string.input_hide_tools else R.string.input_tools), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                AnimatedVisibility(draft.toolsExpanded, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()) {
                    Column(Modifier.requiredWidth(expandedWidth).onSizeChanged { contentHeight = it.height }.verticalScroll(rememberScrollState())) {
                        HorizontalDivider()
                        SurfaceToolbar(draft, onSave, onLeave)
                    }
                }
            }
        }
        if (selected != null) {
            var pillHeight by remember { mutableIntStateOf(0) }
            val pillWidth = 240.dp
            val (px, py) = placeSelectionPill(screenWidth, screenHeight, with(density) { pillWidth.toPx() },
                pillHeight.toFloat().takeIf { it > 0f } ?: with(density) { 120.dp.toPx() }, margin, with(density) { 12.dp.toPx() },
                SurfaceBounds(selected.left * screenWidth, selected.top * screenHeight, selected.right * screenWidth, selected.bottom * screenHeight),
                listOf(SurfaceBounds(x, y, x + targetWidth, y + targetHeight)))
            Surface(Modifier.offset { IntOffset(px.roundToInt(), py.roundToInt()) }.width(pillWidth).onSizeChanged { pillHeight = it.height }
                .testTag("input-selection-pill"), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp, shadowElevation = 10.dp) {
                Column(Modifier.padding(vertical = 4.dp)) { SelectionSliders(draft) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SurfaceToolbar(draft: SurfaceDraft, onSave: () -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SecondaryButton(stringResource(R.string.common_cancel), compact = true, onClick = onLeave)
        SecondaryButton(stringResource(R.string.input_editor_add), enabled = draft.elements.size < 64, compact = true) { draft.adding = true }
        SecondaryButton(stringResource(R.string.input_editor_undo), enabled = draft.history.isNotEmpty(), compact = true) { draft.undo() }
        SecondaryButton(stringResource(R.string.input_editor_redo), enabled = draft.redoable.isNotEmpty(), compact = true) { draft.redo() }
        if (draft.defaults != null) SecondaryButton(stringResource(R.string.input_reset_layout), enabled = draft.resettable, compact = true) { draft.resetLayout() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(draft.snap, { draft.snap = it; ControllerPrefs.setSnap(context, it) })
            Text(stringResource(R.string.ctrl_editor_snap))
        }
        PrimaryButton(stringResource(R.string.gesture_save_changes), compact = true, onClick = onSave)
    }
    if (draft.elements.isNotEmpty()) SliderRow(stringResource(R.string.ctrl_size), null, draft.scale, 80..125, step = 5,
        format = { "$it%" }, modifier = Modifier.testTag("input-editor-scale")) { draft.scaleAll(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SurfaceSelection(draft: SurfaceDraft) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val group = draft.visible.selectedGroup(draft.selection)
        val e = draft.element
        when {
            group != null -> {
                Text(stringResource(group.label()), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.input_group_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecondaryButton(stringResource(R.string.input_delete_element), compact = true, modifier = Modifier.testTag("input-remove-selection")) { draft.deleteSelection() }
            }
            e != null -> {
                Text(e.name, style = MaterialTheme.typography.titleMedium)
                SecondaryButton(stringResource(R.string.input_delete_element), compact = true, modifier = Modifier.testTag("input-remove-selection")) { draft.deleteSelection() }
            }
            else -> Text(stringResource(R.string.input_editor_hint))
        }
        if (group != null || e != null) SelectionSliders(draft)
    }
}

@Composable
private fun SelectionSliders(draft: SurfaceDraft) {
    draft.selectionSize?.let { size ->
        val range = minOf(SELECTION_SIZES.first, size / 5 * 5)..maxOf(SELECTION_SIZES.last, (size + 4) / 5 * 5)
        SliderRow(stringResource(R.string.input_selection_size), null, size, range, step = 5,
            format = { "$it%" }, modifier = Modifier.testTag("input-selection-size")) { draft.resizeSelection(it) }
    }
    SliderRow(stringResource(R.string.ctrl_opacity), null, (draft.selectionOpacity * 100).roundToInt().coerceIn(20, 100), 20..100, step = 5,
        format = { "$it%" }, modifier = Modifier.testTag("input-selection-opacity")) { draft.setSelectionOpacity(it) }
}

private val SELECTION_SIZES = 40..250

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
