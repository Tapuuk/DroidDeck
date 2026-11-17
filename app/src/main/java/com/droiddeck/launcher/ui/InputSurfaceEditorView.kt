package com.droiddeck.launcher.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import com.droiddeck.launcher.input.InputControl
import com.droiddeck.launcher.input.InputElementKind
import com.droiddeck.launcher.input.InputSurfaceElement
import com.droiddeck.launcher.input.InputSurfaceStyle
import com.droiddeck.launcher.input.SurfaceBounds
import com.droiddeck.launcher.input.boundsOf
import com.droiddeck.launcher.input.deleteBadgeCenter
import com.droiddeck.launcher.input.moveSelection
import com.droiddeck.launcher.input.pressSelection
import com.droiddeck.launcher.input.resizeSelection
import com.droiddeck.launcher.input.sizePercent
import com.droiddeck.launcher.input.snapSurfaceElement
import com.droiddeck.launcher.input.tapSelection
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

internal class InputSurfaceEditorView(context: Context) : FrameLayout(context) {
    var elements: List<InputSurfaceElement> = emptyList()
        set(value) { field = value; preview.updateCustomLayout(value); popPending(); refreshGeometry() }
    var style = InputSurfaceStyle()
        set(value) { field = value; preview.updateStyle(value); refreshGeometry() }
    var snap = true
        set(value) { field = value; if (!value) clearGuides() }
    var selection: List<String> = emptyList()
        set(value) {
            val fresh = value != field
            field = value
            if (fresh && value.isNotEmpty()) selectionIn.animate(0f, SELECT_MS, SELECT_MS.toLong(), LINEAR) else if (value.isEmpty()) selectionIn.snap(SELECT_MS)
            refreshGeometry()
        }
    var onSelect: (List<String>) -> Unit = {}
    var onBeginEdit: () -> Unit = {}
    var onChange: (List<InputSurfaceElement>) -> Unit = {}
    var onDelete: () -> Unit = {}
    /** The percent the readout bubble shows while a corner is dragged. Without it the readout is relative to the size when the drag began. */
    var sizeReadout: (() -> Int?)? = null

    private val preview = InputSurfaceView(context)
    private val density = resources.displayMetrics.density
    private val badgeRadius = 16 * density
    private val badgeReach = 22 * density
    private val badgeOffset = 28 * density
    private val handleRadius = 12 * density
    private val handleReach = 22 * density

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * density; pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; pathEffect = DashPathEffect(floatArrayOf(6 * density, 5 * density), 0f)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics); color = ON_SIGNAL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); fontFeatureSettings = "tnum"
    }
    private val ghostPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val trash = Path().apply {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(10f, 11f); lineTo(10f, 17f); moveTo(14f, 11f); lineTo(14f, 17f)
        moveTo(6f, 7f); lineTo(7f, 19.5f); quadTo(7.1f, 21f, 8.5f, 21f); lineTo(15.5f, 21f); quadTo(16.9f, 21f, 17f, 19.5f); lineTo(18f, 7f)
        moveTo(9f, 7f); lineTo(9f, 4.5f); lineTo(15f, 4.5f); lineTo(15f, 7f)
    }
    private val caret = Path()

    private val ring = OutlineShape()
    private val members = ArrayList<OutlineShape>()
    private var memberCount = 0
    private val warned = ArrayList<OutlineShape>()
    private var warnCount = 0
    private val probe = OutlineShape()
    private var hasSelection = false
    private var group = false
    private val handleX = FloatArray(3)
    private val handleY = FloatArray(3)
    private var badgeX = 0f
    private var badgeY = 0f

    private val selectionIn = FloatAnim(this, SELECT_MS)
    private val badgePress = FloatAnim(this, 0f)
    private val guideIn = FloatAnim(this, 0f)
    private val ghostOut = FloatAnim(this, 1f)
    private val ghosts = ArrayList<Ghost>()
    private val ghostRing = OutlineShape()
    private var ghostGroup = false
    private var pendingPop: Collection<String> = emptyList()

    private var guideX = Float.NaN
    private var guideY = Float.NaN
    private var readout: String? = null
    private var readoutValue = Int.MIN_VALUE
    private var readoutBase: Map<String, InputSurfaceElement> = emptyMap()

    private var badge = false
    private var original: List<InputSurfaceElement>? = null
    private var before: List<String> = emptyList()
    private var hit: String? = null
    private var keep = false
    private var downX = 0f
    private var downY = 0f
    private var corner = 0
    private var activeHandle = -1
    private var dragging = false

    private class Ghost(val bitmap: Bitmap, val x: Float, val y: Float, val alpha: Float)

    init {
        setBackgroundColor(Color.rgb(10, 11, 13))
        addView(preview, LayoutParams(-1, -1))
    }

    /** Plays the pop-in for newly added controls. Call it once their elements are set; ids not on screen yet pop when they arrive. */
    fun popIn(ids: Collection<String>) {
        if (!motionOn()) return
        val missing = ids.filter { id -> controlView(id)?.let(::pop) == null }
        pendingPop = missing
    }

    /** Plays the delete animation for [ids]. Call it while they are still on screen; removing them right after is fine. */
    fun playDelete(ids: Collection<String>) {
        clearGhosts()
        if (!motionOn() || width == 0 || height == 0) return
        for (id in ids) {
            val view = controlView(id)?.takeIf { it.width > 0 && it.height > 0 } ?: continue
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            var x = 0f; var y = 0f; var at: View = view
            while (at !== this) { x += at.left; y += at.top; at = at.parent as? View ?: break }
            ghosts += Ghost(bitmap, x, y, view.alpha)
        }
        if (hasSelection) { ghostRing.grow(ring, 0f); ghostGroup = group }
        ghostOut.animate(0f, 1f, DELETE_MS, CubicEase.EASE) { clearGhosts() }
    }

    private fun pop(view: View) {
        val target = view.alpha
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = POP_MS
            interpolator = LINEAR
            addUpdateListener {
                val f = it.animatedFraction
                val s = .6f + .4f * CubicEase.POP.getInterpolation(f)
                view.scaleX = s; view.scaleY = s
                view.alpha = target * CubicEase.EASE.getInterpolation((f * 2.5f).coerceAtMost(1f))
            }
        }.start()
    }

    private fun popPending() {
        if (pendingPop.isEmpty()) return
        val ids = pendingPop
        pendingPop = emptyList()
        for (id in ids) controlView(id)?.let(::pop)
    }

    private fun controlView(id: String): View? {
        fun find(view: View): View? {
            if ((view.tag as? InputSurfaceElement)?.id == id) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(preview)
    }

    private fun clearGhosts() {
        for (g in ghosts) g.bitmap.recycle()
        ghosts.clear()
        ghostRing.set(0f, 0f, 0f, 0f, 0f)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        ghostOut.snap(1f)
        clearGhosts()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshGeometry()
    }

    private fun shape(list: ArrayList<OutlineShape>, i: Int) = if (i < list.size) list[i] else OutlineShape().also { list += it }

    private fun refreshGeometry() {
        invalidate()
        hasSelection = false; memberCount = 0; warnCount = 0
        if (width == 0 || height == 0) return
        val w = width.toFloat(); val h = height.toFloat()
        val tinted = style.tint != null
        for (e in elements) if (e.id in selection) shape(members, memberCount++).ofControl(e, tinted, w, h, density)
        if (memberCount == 0) return
        hasSelection = true
        group = memberCount > 1
        if (dragging) for (e in elements) {
            if (e.id in selection) continue
            probe.ofControl(e, tinted, w, h, density)
            for (i in 0 until memberCount) if (probe.overlaps(members[i])) { shape(warned, warnCount++).grow(probe, RING_OUT * density); break }
        }
        if (group) {
            ring.enclose(members, memberCount, GROUP_PAD * density)
            for (i in 0 until memberCount) members[i].grow(members[i], MEMBER_OUT * density)
        } else ring.grow(members[0], RING_OUT * density)
        for (i in 0 until 3) {
            handleX[i] = ring.cornerX(CORNERS[i]).coerceIn(handleRadius, max(handleRadius, w - handleRadius))
            handleY[i] = ring.cornerY(CORNERS[i]).coerceIn(handleRadius, max(handleRadius, h - handleRadius))
        }
        badgeCenter()?.let { badgeX = it.first; badgeY = it.second }
    }

    internal fun badgeCenter(): Pair<Float, Float>? {
        val b = elements.boundsOf(selection) ?: return null
        if (width == 0 || height == 0) return null
        return deleteBadgeCenter(SurfaceBounds(width * b.left, height * b.top, width * b.right, height * b.bottom), width.toFloat(), height.toFloat(), badgeOffset, badgeReach)
    }

    /** Index of the resize handle under x,y: 0 top-left, 1 bottom-left, 2 bottom-right; -1 for none. */
    internal fun handleAt(x: Float, y: Float) = if (!hasSelection) -1 else pickHandle(x, y, handleX, handleY, handleReach, ring.centerX, ring.centerY)

    internal fun hitsBadge(x: Float, y: Float) = hasSelection && hypot(x - badgeX, y - badgeY) <= badgeReach

    internal val overlapping get() = warnCount

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawOverlay(canvas)
    }

    internal fun drawOverlay(canvas: Canvas) {
        drawGhosts(canvas)
        drawGuides(canvas)
        if (!hasSelection) return
        for (i in 0 until warnCount) drawRing(canvas, warned[i], WARN, WARN_HALO, 1f)
        val t = selectionIn.value
        val p = CubicEase.EASE.getInterpolation((t / OUTLINE_MS).coerceIn(0f, 1f))
        val s = 1.04f - .04f * p
        canvas.save()
        canvas.scale(s, s, ring.centerX, ring.centerY)
        if (group) drawGroup(canvas, p) else drawRing(canvas, ring, SIGNAL, HALO, p)
        canvas.restore()
        if (dragging && corner == 0) return
        for (i in 0 until 3) drawHandle(canvas, i, handleIn(t, i))
        drawBadge(canvas, handleIn(t, 3))
        if (dragging) drawReadout(canvas)
    }

    private fun handleIn(t: Float, i: Int) = CubicEase.POP.getInterpolation(((t - HANDLE_DELAY_MS - i * HANDLE_STAGGER_MS) / HANDLE_MS).coerceIn(0f, 1f))

    private fun drawRing(canvas: Canvas, shape: OutlineShape, color: Int, halo: Int, alpha: Float) {
        val d = 3 * density
        stroke.strokeWidth = 4 * density; stroke.color = withAlpha(halo, alpha)
        canvas.drawRoundRect(shape.left - d, shape.top - d, shape.right + d, shape.bottom + d, shape.corner + d, shape.corner + d, stroke)
        stroke.strokeWidth = 2 * density; stroke.color = withAlpha(color, alpha)
        canvas.drawRoundRect(shape.left, shape.top, shape.right, shape.bottom, shape.corner, shape.corner, stroke)
    }

    private fun drawGroup(canvas: Canvas, alpha: Float) {
        fill.color = withAlpha(GROUP_FILL, alpha)
        canvas.drawRoundRect(ring.left, ring.top, ring.right, ring.bottom, ring.corner, ring.corner, fill)
        dashed.color = withAlpha(SIGNAL, alpha)
        canvas.drawRoundRect(ring.left, ring.top, ring.right, ring.bottom, ring.corner, ring.corner, dashed)
        stroke.strokeWidth = 1.5f * density; stroke.color = withAlpha(MEMBER, alpha)
        for (i in 0 until memberCount) {
            val m = members[i]
            canvas.drawRoundRect(m.left, m.top, m.right, m.bottom, m.corner, m.corner, stroke)
        }
    }

    private fun drawHandle(canvas: Canvas, i: Int, scale: Float) {
        if (scale <= 0f) return
        val x = handleX[i]; val y = handleY[i]
        val active = dragging && i == activeHandle
        val r = handleRadius * scale * if (active) 1.12f else 1f
        if (active) { fill.color = HOT; canvas.drawCircle(x, y, r + 6 * density, fill) }
        fill.color = SHADOW; canvas.drawCircle(x, y + 2 * density, r + .5f * density, fill)
        fill.color = Color.WHITE; canvas.drawCircle(x, y, r, fill)
        stroke.strokeWidth = 2 * density * scale; stroke.color = SIGNAL
        canvas.drawCircle(x, y, r - density * scale, stroke)
    }

    private fun drawBadge(canvas: Canvas, appear: Float) {
        if (appear <= 0f) return
        val press = badgePress.value
        val s = appear * (1f - .1f * press)
        fill.color = withAlpha(ERROR, .2f * press); canvas.drawCircle(badgeX, badgeY, (badgeRadius + 7 * density) * s, fill)
        fill.color = BADGE_RING; canvas.drawCircle(badgeX, badgeY, (badgeRadius + 2 * density) * s, fill)
        fill.color = lerpColor(SURFACE_2, ERROR, press); canvas.drawCircle(badgeX, badgeY, badgeRadius * s, fill)
        stroke.strokeWidth = 1.5f * density * s; stroke.color = lerpColor(ERROR_LINE, ERROR, press)
        canvas.drawCircle(badgeX, badgeY, (badgeRadius - .75f * density) * s, stroke)
        val size = 17 * density * s
        canvas.save()
        canvas.translate(badgeX - size / 2f, badgeY - size / 2f)
        canvas.scale(size / 24f, size / 24f)
        glyph.color = lerpColor(ERROR, ERROR_INK, press)
        canvas.drawPath(trash, glyph)
        canvas.restore()
    }

    private fun drawReadout(canvas: Canvas) {
        val text = readout ?: return
        val margin = 8 * density; val tip = 5 * density; val gap = 10 * density; val h = 22 * density
        val w = label.measureText(text) + 20 * density
        val cx = ring.centerX.coerceIn(margin + w / 2f, max(margin + w / 2f, width - margin - w / 2f))
        val above = ring.top - gap - tip - h >= margin
        val top = if (above) ring.top - gap - tip - h else ring.bottom + gap + tip
        fill.color = SIGNAL
        canvas.drawRoundRect(cx - w / 2f, top, cx + w / 2f, top + h, h / 2f, h / 2f, fill)
        caret.reset()
        val base = if (above) top + h else top
        caret.moveTo(cx - tip, base); caret.lineTo(cx + tip, base); caret.lineTo(cx, if (above) base + tip else base - tip); caret.close()
        canvas.drawPath(caret, fill)
        canvas.drawText(text, cx, top + h / 2f - (label.ascent() + label.descent()) / 2f, label)
    }

    private fun drawGuides(canvas: Canvas) {
        if (guideX.isNaN() && guideY.isNaN()) return
        val a = guideIn.value
        stroke.strokeWidth = 4 * density; stroke.color = withAlpha(GUIDE_GLOW, a)
        guide.color = withAlpha(SIGNAL, a)
        if (!guideX.isNaN()) {
            val x = width * guideX
            canvas.drawLine(x, 0f, x, height.toFloat(), stroke); canvas.drawLine(x, 0f, x, height.toFloat(), guide)
        }
        if (!guideY.isNaN()) {
            val y = height * guideY
            canvas.drawLine(0f, y, width.toFloat(), y, stroke); canvas.drawLine(0f, y, width.toFloat(), y, guide)
        }
    }

    private fun drawGhosts(canvas: Canvas) {
        if (ghosts.isEmpty()) return
        val p = ghostOut.value
        val s = 1f - .6f * p
        for (i in ghosts.indices) {
            val g = ghosts[i]
            canvas.save()
            canvas.scale(s, s, g.x + g.bitmap.width / 2f, g.y + g.bitmap.height / 2f)
            ghostPaint.alpha = (255 * g.alpha * (1f - p)).toInt().coerceIn(0, 255)
            canvas.drawBitmap(g.bitmap, g.x, g.y, ghostPaint)
            canvas.restore()
        }
        if (ghostRing.right > ghostRing.left) {
            canvas.save()
            canvas.scale(s, s, ghostRing.centerX, ghostRing.centerY)
            if (ghostGroup) {
                dashed.color = withAlpha(SIGNAL, 1f - p)
                canvas.drawRoundRect(ghostRing.left, ghostRing.top, ghostRing.right, ghostRing.bottom, ghostRing.corner, ghostRing.corner, dashed)
            } else drawRing(canvas, ghostRing, SIGNAL, HALO, 1f - p)
            canvas.restore()
        }
    }

    private fun setGuides(x: Float, y: Float) {
        val shown = !guideX.isNaN() || !guideY.isNaN()
        guideX = x; guideY = y
        if (!shown && (!x.isNaN() || !y.isNaN())) guideIn.animate(0f, 1f, GUIDE_MS)
        invalidate()
    }

    private fun clearGuides() { guideX = Float.NaN; guideY = Float.NaN; guideIn.snap(0f) }

    private fun updateReadout(next: List<InputSurfaceElement>) {
        val value = sizeReadout?.invoke() ?: next.sizePercent(selection, readoutBase).roundToInt()
        if (value == readoutValue && readout != null) return
        readoutValue = value
        readout = "$value%"
    }

    override fun onInterceptTouchEvent(event: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || width == 0 || height == 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearGuides()
                badge = hitsBadge(event.x, event.y)
                if (badge) { badgePress.animate(badgePress.value, 1f, PRESS_MS); return true }
                downX = event.x; downY = event.y; dragging = false; corner = 0; readout = null
                before = selection
                activeHandle = handleAt(downX, downY)
                if (activeHandle >= 0) corner = CORNERS[activeHandle]
                hit = if (corner != 0) null else elements.asReversed().firstOrNull {
                    downX / width in it.x..(it.x + it.width) && downY / height in it.y..(it.y + it.height)
                }?.id
                val inside = hit == null && hasSelection && ring.contains(downX, downY)
                if (corner == 0 && !inside) {
                    val pressed = elements.pressSelection(before, hit)
                    if (pressed != selection) { selection = pressed; onSelect(pressed) }
                }
                keep = inside
                original = elements.takeIf { selection.isNotEmpty() }
            }
            MotionEvent.ACTION_MOVE -> {
                if (badge) return true
                val start = original ?: return true
                if (!dragging && abs(event.x - downX) + abs(event.y - downY) > ViewConfiguration.get(context).scaledTouchSlop) {
                    dragging = true; onBeginEdit()
                    readoutBase = start.filter { it.id in selection }.associateBy { it.id }
                    refreshGeometry()
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
                    snapSurfaceElement(box, others, 9 * density / width, 9 * density / height, corner) else null
                val x = aligned?.x ?: Float.NaN; val y = aligned?.y ?: Float.NaN
                if (aligned != null && (!x.isNaN() && x != guideX || !y.isNaN() && y != guideY)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                setGuides(x, y)
                val target = aligned?.element?.let { SurfaceBounds(it.x, it.y, it.x + it.width, it.y + it.height) } ?: moved
                val next = if (corner == 0) start.moveSelection(selection, target.left - b.left, target.top - b.top)
                    else start.resizeSelection(selection, corner, target)
                onChange(next.filter { it.id in selection })
                if (corner != 0) updateReadout(next)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (badge) {
                    badge = false
                    badgePress.animate(badgePress.value, 0f, RELEASE_MS, CubicEase.POP)
                    if (hitsBadge(event.x, event.y)) { playDelete(selection); onDelete() }
                    return true
                }
                if (!dragging && corner == 0 && !keep) {
                    val tapped = elements.tapSelection(before, hit)
                    if (tapped != selection) { selection = tapped; onSelect(tapped) }
                }
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (badge) badgePress.animate(badgePress.value, 0f, RELEASE_MS, CubicEase.POP)
                badge = false; endGesture()
            }
        }
        return true
    }

    private fun endGesture() {
        original = null; dragging = false; activeHandle = -1; readout = null; clearGuides(); refreshGeometry()
    }

    companion object {
        private val LINEAR = android.view.animation.LinearInterpolator()
        private val CORNERS = intArrayOf(1 or 4, 1 or 8, 2 or 8)
        private const val RING_OUT = 3f
        private const val MEMBER_OUT = 2f
        private const val GROUP_PAD = 10f
        private const val OUTLINE_MS = 120f
        private const val HANDLE_DELAY_MS = 90f
        private const val HANDLE_STAGGER_MS = 30f
        private const val HANDLE_MS = 200f
        private const val SELECT_MS = HANDLE_DELAY_MS + 3 * HANDLE_STAGGER_MS + HANDLE_MS
        private const val PRESS_MS = 70L
        private const val RELEASE_MS = 160L
        private const val GUIDE_MS = 90L
        private const val POP_MS = 280L
        private const val DELETE_MS = 180L
        private val SIGNAL = Color.rgb(0x1A, 0x9F, 0xFF)
        private val HALO = Color.argb(46, 0x1A, 0x9F, 0xFF)
        private val HOT = Color.argb(36, 0x1A, 0x9F, 0xFF)
        private val MEMBER = Color.argb(179, 0x1A, 0x9F, 0xFF)
        private val GROUP_FILL = Color.argb(10, 0x1A, 0x9F, 0xFF)
        private val GUIDE_GLOW = Color.argb(64, 0x1A, 0x9F, 0xFF)
        private val ON_SIGNAL = Color.rgb(0x03, 0x11, 0x1F)
        private val WARN = Color.rgb(0xFF, 0xB5, 0x47)
        private val WARN_HALO = Color.argb(41, 0xFF, 0xB5, 0x47)
        private val ERROR = Color.rgb(0xFF, 0x6B, 0x6B)
        private val ERROR_LINE = Color.argb(153, 0xFF, 0x6B, 0x6B)
        private val ERROR_INK = Color.rgb(0x1F, 0x06, 0x06)
        private val SURFACE_2 = Color.rgb(0x1A, 0x1D, 0x22)
        private val BADGE_RING = Color.argb(217, 0x0A, 0x0B, 0x0D)
        private val SHADOW = Color.argb(77, 0, 0, 0)
    }
}
