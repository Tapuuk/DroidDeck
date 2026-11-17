package com.droiddeck.launcher.input

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val TOOL_STEPS = 12

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
