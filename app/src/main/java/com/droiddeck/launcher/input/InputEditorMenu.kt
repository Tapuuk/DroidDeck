package com.droiddeck.launcher.input

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val DOCK_STEPS = 12

/**
 * Top-left, in pixels, of the open editor menu. It docks to the top or bottom edge, whichever is farther from [selection], and along
 * that edge sits where it covers the fewest [elements], nearest the centre. Without a selection it keeps the [top] edge or the bottom one.
 * [elements] and [selection] are fractions of the canvas.
 */
internal fun dockSurfaceMenu(width: Float, height: Float, menuWidth: Float, menuHeight: Float, margin: Float,
    elements: List<SurfaceBounds>, selection: SurfaceBounds?, top: Boolean): Pair<Float, Float> {
    val maxX = (width - menuWidth - margin).coerceAtLeast(margin)
    val topY = margin
    val bottomY = (height - menuHeight - margin).coerceAtLeast(margin)
    val centre = ((width - menuWidth) / 2).coerceIn(margin, maxX)
    fun covered(b: SurfaceBounds, x: Float, y: Float) =
        max(0f, min(x + menuWidth, b.right * width) - max(x, b.left * width)) * max(0f, min(y + menuHeight, b.bottom * height) - max(y, b.top * height))
    val edges = when {
        selection == null -> listOf(if (top) topY else bottomY)
        selection.top * height >= height - selection.bottom * height -> listOf(topY, bottomY)
        else -> listOf(bottomY, topY)
    }
    val xs = (0..DOCK_STEPS).map { margin + (maxX - margin) * it / DOCK_STEPS } + centre
    for (y in edges) {
        val clear = xs.filter { x -> selection == null || covered(selection, x, y) == 0f }
        if (clear.isNotEmpty()) return clear.minWith(compareBy({ x -> elements.sumOf { covered(it, x, y).toDouble() } }, { x -> abs(x - centre) })) to y
    }
    return edges.flatMap { y -> xs.map { it to y } }.minBy { (x, y) -> selection?.let { covered(it, x, y) } ?: 0f }
}

/** Whether the editor menu docked at [y] (pixels, top of a [menuHeight] bar) sits on the top edge of a [height] canvas. */
internal fun menuOnTop(y: Float, menuHeight: Float, height: Float) = y + menuHeight / 2 < height / 2

/** The slider range for a selection [size] in percent: the usual range, widened in steps of [step] when the size is already outside it. */
internal fun selectionSizeRange(size: Int, usual: IntRange = 40..250, step: Int = 5) =
    minOf(usual.first, Math.floorDiv(size, step) * step)..maxOf(usual.last, Math.floorDiv(size + step - 1, step) * step)

/** [raw] snapped to the nearest multiple of [step] counted from the start of [range], kept inside it. */
internal fun stepPercent(raw: Float, range: IntRange, step: Int): Int =
    (((raw - range.first) / step).roundToInt() * step + range.first).coerceIn(range.first, range.last)
