package com.droiddeck.launcher.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TOOL_STEPS = 12
private const val PILL_STEPS = 8
private const val PILL_GRID = 24

/** [elements], [selection] and [preferred] are fractions of the canvas; the result is the panel's top-left corner in pixels. */
internal fun placeSurfaceTools(width: Float, height: Float, panelWidth: Float, panelHeight: Float, margin: Float,
    elements: List<SurfaceBounds>, selection: SurfaceBounds?, preferred: Pair<Float, Float>? = null): Pair<Float, Float> {
    val maxX = (width - panelWidth - margin).coerceAtLeast(margin)
    val maxY = (height - panelHeight - margin).coerceAtLeast(margin)
    fun covered(b: SurfaceBounds, at: Pair<Float, Float>): Float {
        val (x, y) = at
        return max(0f, min(x + panelWidth, b.right * width) - max(x, b.left * width)) *
            max(0f, min(y + panelHeight, b.bottom * height) - max(y, b.top * height))
    }
    val kept = preferred?.let { (x, y) -> (x * width).coerceIn(margin, maxX) to (y * height).coerceIn(margin, maxY) }
    if (kept != null && (selection == null || covered(selection, kept) == 0f)) return kept
    val anchor = kept ?: (((width - panelWidth) / 2).coerceIn(margin, maxX) to margin)
    val candidates = (0..TOOL_STEPS).flatMap { i ->
        (0..TOOL_STEPS).map { j -> margin + (maxX - margin) * i / TOOL_STEPS to margin + (maxY - margin) * j / TOOL_STEPS }
    } + anchor
    fun sameSide(at: Pair<Float, Float>) = kept == null && selection != null && (selection.centerX * width < width / 2) == (at.first + panelWidth / 2 < width / 2)
    return candidates.minWith(compareBy<Pair<Float, Float>>({ at -> selection?.let { covered(it, at) } ?: 0f }, ::sameSide,
        { at -> elements.sumOf { covered(it, at).toDouble() } }, { at -> hypot(at.first - anchor.first, at.second - anchor.second) }))
}

/** Widest panel, between [min] and [max] pixels, that fits beside [selection] (fractions of the canvas) on its roomier side. */
internal fun surfaceToolsWidth(width: Float, margin: Float, selection: SurfaceBounds?, min: Float, max: Float): Float =
    selection?.let { (max(it.left * width, width - it.right * width) - 2 * margin).coerceIn(min, max) } ?: max

/** Top-left, as canvas fractions, for a new [width] x [height] control: where it covers the fewest controls, then farthest from them. */
internal fun freeSurfaceSpot(elements: List<SurfaceBounds>, width: Float, height: Float): Pair<Float, Float> {
    val maxX = (1f - width).coerceAtLeast(0f)
    val maxY = (1f - height).coerceAtLeast(0f)
    fun covered(b: SurfaceBounds, x: Float, y: Float) =
        max(0f, min(x + width, b.right) - max(x, b.left)) * max(0f, min(y + height, b.bottom) - max(y, b.top))
    fun clearance(b: SurfaceBounds, x: Float, y: Float) =
        hypot(max(0f, max(b.left - (x + width), x - b.right)), max(0f, max(b.top - (y + height), y - b.bottom)))
    val candidates = (0..TOOL_STEPS).flatMap { i -> (0..TOOL_STEPS).map { j -> maxX * i / TOOL_STEPS to maxY * j / TOOL_STEPS } }
    return candidates.minWith(compareBy<Pair<Float, Float>>({ (x, y) -> elements.sumOf { covered(it, x, y).toDouble() } },
        { (x, y) -> -(elements.minOfOrNull { clearance(it, x, y) } ?: 0f) },
        { (x, y) -> hypot(x - maxX / 2, y - maxY / 2) }))
}

/**
 * Top-left, in pixels, for the size and opacity pill. It tries the spots beside, below and above [selection], each centred and slid
 * along that side, then a grid over the screen, and takes one at least [gap] off the selection that covers the least of [avoid] (controls, menu, cards), then the nearest, the
 * least slid and the one on the roomier side. All bounds are pixels.
 */
internal fun placeSelectionPill(width: Float, height: Float, pillWidth: Float, pillHeight: Float, margin: Float, gap: Float,
    selection: SurfaceBounds, avoid: List<SurfaceBounds>): Pair<Float, Float> {
    fun clamp(x: Float, y: Float) = x.coerceIn(margin, (width - pillWidth - margin).coerceAtLeast(margin)) to
        y.coerceIn(margin, (height - pillHeight - margin).coerceAtLeast(margin))
    fun covered(b: SurfaceBounds, at: Pair<Float, Float>) =
        max(0f, min(at.first + pillWidth, b.right) - max(at.first, b.left)) * max(0f, min(at.second + pillHeight, b.bottom) - max(at.second, b.top))
    val right = selection.right + gap
    val left = selection.left - gap - pillWidth
    val below = selection.bottom + gap
    val above = selection.top - gap - pillHeight
    val sides = if (width - selection.right >= selection.left) listOf(right, left) else listOf(left, right)
    val rows = if (height - selection.bottom >= selection.top) listOf(below, above) else listOf(above, below)
    val ys = (0..PILL_STEPS).map { selection.top - pillHeight + (selection.height + pillHeight) * it / PILL_STEPS }
    val xs = (0..PILL_STEPS).map { selection.left - pillWidth + (selection.width + pillWidth) * it / PILL_STEPS }
    val gridX = (0..PILL_GRID).map { margin + ((width - pillWidth - margin).coerceAtLeast(margin) - margin) * it / PILL_GRID }
    val gridY = (0..PILL_GRID).map { margin + ((height - pillHeight - margin).coerceAtLeast(margin) - margin) * it / PILL_GRID }
    val candidates = sides.map { clamp(it, selection.centerY - pillHeight / 2) } + rows.map { clamp(selection.centerX - pillWidth / 2, it) } +
        sides.flatMap { x -> ys.map { clamp(x, it) } } + rows.flatMap { y -> xs.map { clamp(it, y) } } + gridX.flatMap { x -> gridY.map { x to it } }
    fun distance(x: Float, y: Float) = hypot(max(0f, max(selection.left - x - pillWidth, x - selection.right)),
        max(0f, max(selection.top - y - pillHeight, y - selection.bottom))).roundToInt()
    fun slide(x: Float, y: Float) = if (x + pillWidth <= selection.left || x >= selection.right) abs(y + pillHeight / 2 - selection.centerY)
        else abs(x + pillWidth / 2 - selection.centerX)
    val clear = (gap - .5f).coerceAtLeast(0f)
    val keepOff = SurfaceBounds(selection.left - clear, selection.top - clear, selection.right + clear, selection.bottom + clear)
    return candidates.minWith(compareBy<Pair<Float, Float>>({ covered(keepOff, it) }, { at -> avoid.sumOf { covered(it, at).toDouble() } },
        { (x, y) -> distance(x, y) }, { (x, y) -> slide(x, y).roundToInt() }))
}
