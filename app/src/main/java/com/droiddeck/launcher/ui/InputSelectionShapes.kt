package com.droiddeck.launcher.ui

import com.droiddeck.launcher.input.InputButtonShape
import com.droiddeck.launcher.input.InputControl
import com.droiddeck.launcher.input.InputElementKind
import com.droiddeck.launcher.input.InputSurfaceElement
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val SQRT_HALF = 0.70710677f

/** A rounded rectangle in pixels; a square with [corner] at half its side is a circle. Mutable so drawing can reuse it. */
internal class OutlineShape {
    var left = 0f; private set
    var top = 0f; private set
    var right = 0f; private set
    var bottom = 0f; private set
    var corner = 0f; private set
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
    val circle get() = abs((right - left) - (bottom - top)) < .5f && corner >= (right - left) / 2f - .5f

    fun set(l: Float, t: Float, r: Float, b: Float, c: Float): OutlineShape {
        left = l; top = t; right = r; bottom = b; corner = c.coerceIn(0f, max(0f, min(r - l, b - t) / 2f)); return this
    }

    fun grow(from: OutlineShape, by: Float) = set(from.left - by, from.top - by, from.right + by, from.bottom + by, from.corner + by)

    /** Shape of [e] as the controls preview draws it, for a surface of [width] x [height] pixels. */
    fun ofControl(e: InputSurfaceElement, width: Float, height: Float, density: Float): OutlineShape {
        val l = width * e.x; val t = height * e.y; val r = l + width * e.width; val b = t + height * e.height
        val w = r - l; val h = b - t; val half = min(w, h) / 2f
        val classic = classicLook(e)
        return when (e.kind) {
            InputElementKind.STICK -> if (classic) {
                val cx = when { w <= h -> l + w / 2f; e.control == InputControl.LS -> r - h / 2f; else -> l + h / 2f }
                set(cx - half, t + h / 2f - half, cx + half, t + h / 2f + half, half)
            } else set(l, t, r, b, CONTROL_CORNER * density)
            InputElementKind.TRACKPAD -> set(l, t, r, b, CONTROL_CORNER * density)
            InputElementKind.DPAD -> set(l + w / 2f - half, t + h / 2f - half, l + w / 2f + half, t + h / 2f + half, half)
            else -> set(l, t, r, b, when (e.buttonShape) {
                InputButtonShape.CIRCLE -> half
                InputButtonShape.PILL -> half
                InputButtonShape.RECTANGLE -> 2 * density
                InputButtonShape.ROUNDED -> CONTROL_CORNER * density
            })
        }
    }

    /** The outline around the first [count] of [members]: a circle when they are all round and sit in a square-ish cluster, else a rounded rectangle. */
    fun enclose(members: List<OutlineShape>, count: Int, pad: Float): OutlineShape {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var round = true; var corner = 0f
        for (i in 0 until count) {
            val m = members[i]
            l = min(l, m.left); t = min(t, m.top); r = max(r, m.right); b = max(b, m.bottom)
            round = round && m.circle; corner = max(corner, m.corner)
        }
        val w = r - l; val h = b - t
        if (round && w > 0f && h > 0f && w / h in .75f..1.34f) {
            val cx = (l + r) / 2f; val cy = (t + b) / 2f
            var reach = 0f
            for (i in 0 until count) { val m = members[i]; reach = max(reach, hypot(m.centerX - cx, m.centerY - cy) + (m.right - m.left) / 2f) }
            reach += pad
            return set(cx - reach, cy - reach, cx + reach, cy + reach, reach)
        }
        return set(l - pad, t - pad, r + pad, b + pad, corner + pad)
    }

    /** The point on this outline at the middle of the corner named by [corner] bits (1 left, 2 right, 4 top, 8 bottom). */
    fun cornerX(corner: Int) = if (corner and 1 != 0) left + this.corner * (1 - SQRT_HALF) else right - this.corner * (1 - SQRT_HALF)
    fun cornerY(corner: Int) = if (corner and 4 != 0) top + this.corner * (1 - SQRT_HALF) else bottom - this.corner * (1 - SQRT_HALF)

    fun contains(x: Float, y: Float): Boolean {
        if (x < left || x > right || y < top || y > bottom) return false
        val dx = max(0f, max(left + corner - x, x - (right - corner)))
        val dy = max(0f, max(top + corner - y, y - (bottom - corner)))
        return dx * dx + dy * dy <= corner * corner
    }

    /** True when the two shapes share area; shapes that only touch do not overlap. */
    fun overlaps(other: OutlineShape): Boolean {
        // Each shape is its rectangle shrunk by its corner radius, grown back by a disc of that radius.
        val gapX = max((other.left + other.corner) - (right - corner), (left + corner) - (other.right - other.corner))
        val gapY = max((other.top + other.corner) - (bottom - corner), (top + corner) - (other.bottom - other.corner))
        if (gapX < 0f && gapY < 0f) return true
        return hypot(max(gapX, 0f), max(gapY, 0f)) < corner + other.corner - OVERLAP_SLACK
    }

    companion object {
        private const val CONTROL_CORNER = 20f
        private const val OVERLAP_SLACK = .5f

        private fun classicLook(e: InputSurfaceElement) = (e.id == "legacy:${e.control.id}" || e.id == "builtin:keyboard") &&
            (e.buttonShape == InputButtonShape.CIRCLE || e.buttonShape == InputButtonShape.PILL)
    }
}

/** Corners of a selection outline as [OutlineShape.cornerX] bits, in the order the delete badge tries them. */
internal val BADGE_CORNERS = intArrayOf(2 or 4, 1 or 4, 2 or 8, 1 or 8)

/**
 * The corner of [ring] that carries the delete badge, a disc of [radius] centred on the outline: the first of [BADGE_CORNERS] where it
 * stays on a [width] x [height] screen and clear of the first [count] of [others], else the first where it stays on screen.
 */
internal fun deleteBadgeCorner(ring: OutlineShape, width: Float, height: Float, radius: Float, others: List<OutlineShape>, count: Int = others.size,
    disc: OutlineShape = OutlineShape()): Int {
    var fallback = 0
    for (corner in BADGE_CORNERS) {
        val x = ring.cornerX(corner); val y = ring.cornerY(corner)
        if (x < radius || y < radius || x > width - radius || y > height - radius) continue
        disc.set(x - radius, y - radius, x + radius, y + radius, radius)
        if ((0 until count).none { disc.overlaps(others[it]) }) return corner
        if (fallback == 0) fallback = corner
    }
    return if (fallback != 0) fallback else BADGE_CORNERS[0]
}

/**
 * Index of the handle a touch at [x],[y] grabs: the nearest of [xs],[ys] within [reach], unless the touch is nearer the selection's
 * centre ([cx],[cy]) than that handle, so a small control's middle still moves it. -1 when none.
 */
internal fun pickHandle(x: Float, y: Float, xs: FloatArray, ys: FloatArray, reach: Float, cx: Float, cy: Float): Int {
    var best = -1; var bestDistance = reach
    for (i in xs.indices) {
        val d = hypot(x - xs[i], y - ys[i])
        if (d <= bestDistance) { best = i; bestDistance = d }
    }
    return if (best >= 0 && hypot(x - cx, y - cy) < bestDistance) -1 else best
}
