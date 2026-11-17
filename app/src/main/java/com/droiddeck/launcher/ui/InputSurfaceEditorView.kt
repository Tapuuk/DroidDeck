package com.droiddeck.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.droiddeck.launcher.input.InputControl
import com.droiddeck.launcher.input.InputElementKind
import com.droiddeck.launcher.input.InputSurfaceElement
import com.droiddeck.launcher.input.InputSurfaceStyle
import com.droiddeck.launcher.input.SurfaceBounds
import com.droiddeck.launcher.input.boundsOf
import com.droiddeck.launcher.input.moveSelection
import com.droiddeck.launcher.input.pressSelection
import com.droiddeck.launcher.input.resizeSelection
import com.droiddeck.launcher.input.snapSurfaceElement
import com.droiddeck.launcher.input.tapSelection
import kotlin.math.abs
import kotlin.math.max

internal class InputSurfaceEditorView(context: Context) : FrameLayout(context) {
    var elements: List<InputSurfaceElement> = emptyList()
        set(value) { field = value; preview.updateCustomLayout(value); invalidate() }
    var style = InputSurfaceStyle()
        set(value) { field = value; preview.updateStyle(value) }
    var snap = true
        set(value) { field = value; if (!value) clearGuides() }
    private var guideX: Float? = null
    private var guideY: Float? = null
    var selection: List<String> = emptyList()
        set(value) { field = value; invalidate() }
    var onSelect: (List<String>) -> Unit = {}
    var onBeginEdit: () -> Unit = {}
    var onChange: (List<InputSurfaceElement>) -> Unit = {}
    private val preview = InputSurfaceView(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 159, 255); strokeWidth = 2 * resources.displayMetrics.density }
    private val radius = 14 * resources.displayMetrics.density
    private var original: List<InputSurfaceElement>? = null
    private var before: List<String> = emptyList()
    private var hit: String? = null
    private var keep = false
    private var downX = 0f
    private var downY = 0f
    private var corner = 0
    private var dragging = false

    init {
        setBackgroundColor(Color.rgb(10, 11, 13))
        addView(preview, LayoutParams(-1, -1))
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        paint.style = Paint.Style.STROKE
        guideX?.let { canvas.drawLine(width * it, 0f, width * it, height.toFloat(), paint) }
        guideY?.let { canvas.drawLine(0f, height * it, width.toFloat(), height * it, paint) }
        val e = elements.boundsOf(selection) ?: return
        val l = width * e.left; val t = height * e.top; val r = width * e.right; val b = height * e.bottom
        paint.style = Paint.Style.STROKE; canvas.drawRect(l, t, r, b, paint)
        paint.style = Paint.Style.FILL
        for (x in listOf(l, r)) for (y in listOf(t, b)) canvas.drawCircle(x, y, radius / 3, paint)
    }

    private fun clearGuides() { guideX = null; guideY = null; invalidate() }

    override fun onInterceptTouchEvent(event: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || width == 0 || height == 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearGuides()
                downX = event.x; downY = event.y; dragging = false; corner = 0
                before = selection
                elements.boundsOf(selection)?.let { b ->
                    val horizontal = if (abs(downX - width * b.left) < radius) 1 else if (abs(downX - width * b.right) < radius) 2 else 0
                    val vertical = if (abs(downY - height * b.top) < radius) 4 else if (abs(downY - height * b.bottom) < radius) 8 else 0
                    if (horizontal != 0 && vertical != 0) corner = horizontal or vertical
                }
                hit = if (corner != 0) null else elements.asReversed().firstOrNull {
                    downX / width in it.x..(it.x + it.width) && downY / height in it.y..(it.y + it.height)
                }?.id
                val inside = hit == null && elements.boundsOf(selection)?.let { b ->
                    downX / width in b.left..b.right && downY / height in b.top..b.bottom } == true
                if (corner == 0 && !inside) {
                    val pressed = elements.pressSelection(before, hit)
                    if (pressed != selection) { selection = pressed; onSelect(pressed) }
                }
                keep = inside
                original = elements.takeIf { selection.isNotEmpty() }
            }
            MotionEvent.ACTION_MOVE -> {
                val start = original ?: return true
                if (!dragging && abs(event.x - downX) + abs(event.y - downY) > ViewConfiguration.get(context).scaledTouchSlop) {
                    dragging = true; onBeginEdit()
                }
                if (!dragging) return true
                val dx = (event.x - downX) / width; val dy = (event.y - downY) / height
                val b = start.boundsOf(selection) ?: return true
                val moved = if (corner == 0) start.moveSelection(selection, dx, dy).boundsOf(selection)!!
                    else SurfaceBounds(if (corner and 1 != 0) b.left + dx else b.left, if (corner and 4 != 0) b.top + dy else b.top,
                        if (corner and 2 != 0) b.right + dx else b.right, if (corner and 8 != 0) b.bottom + dy else b.bottom)
                val others = start.filter { it.id !in selection }
                val box = InputSurfaceElement(InputControl.A, moved.left, moved.top, max(moved.width, .03f), max(moved.height, .03f), id = "selection")
                val aligned = if (snap && (corner == 0 || selection.none { id -> start.any { it.id == id && it.kind == InputElementKind.STICK } }))
                    snapSurfaceElement(box, others, 9 * resources.displayMetrics.density / width, 9 * resources.displayMetrics.density / height, corner) else null
                if (aligned != null && (aligned.x != null && aligned.x != guideX || aligned.y != null && aligned.y != guideY)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                guideX = aligned?.x; guideY = aligned?.y
                val target = aligned?.element?.let { SurfaceBounds(it.x, it.y, it.x + it.width, it.y + it.height) } ?: moved
                val next = if (corner == 0) start.moveSelection(selection, target.left - b.left, target.top - b.top)
                    else start.resizeSelection(selection, corner, target)
                onChange(next.filter { it.id in selection })
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && corner == 0 && !keep) {
                    val tapped = elements.tapSelection(before, hit)
                    if (tapped != selection) { selection = tapped; onSelect(tapped) }
                }
                original = null; dragging = false; clearGuides()
            }
            MotionEvent.ACTION_CANCEL -> { original = null; dragging = false; clearGuides() }
        }
        return true
    }
}
