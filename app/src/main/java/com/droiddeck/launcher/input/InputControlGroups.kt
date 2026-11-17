package com.droiddeck.launcher.input

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class InputControlGroup(val controls: Set<InputControl>) {
    DPAD(setOf(InputControl.UP, InputControl.RIGHT, InputControl.DOWN, InputControl.LEFT)),
    FACE(setOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y)),
    LEFT_SHOULDER(setOf(InputControl.LB, InputControl.LT)),
    RIGHT_SHOULDER(setOf(InputControl.RB, InputControl.RT)),
    LEFT_STICK(setOf(InputControl.LS)),
    RIGHT_STICK(setOf(InputControl.RS));

    companion object {
        // Only the stock gamepad controls form groups; controls added in the editor stay on their own.
        fun of(e: InputSurfaceElement): InputControlGroup? =
            if (e.id == "legacy:${e.control.id}") entries.firstOrNull { e.control in it.controls } else null
    }
}

data class SurfaceBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}

private const val MIN_SIZE = .03f

fun List<InputSurfaceElement>.boundsOf(ids: Collection<String>): SurfaceBounds? {
    val members = filter { it.id in ids }.ifEmpty { return null }
    return SurfaceBounds(members.minOf { it.x }, members.minOf { it.y }, members.maxOf { it.x + it.width }, members.maxOf { it.y + it.height })
}

fun List<InputSurfaceElement>.groupOf(id: String): List<String> {
    val group = firstOrNull { it.id == id }?.let(InputControlGroup::of) ?: return listOf(id)
    return filter { InputControlGroup.of(it) == group }.map { it.id }
}

fun List<InputSurfaceElement>.selectedGroup(selection: List<String>): InputControlGroup? {
    if (selection.size < 2) return null
    val group = firstOrNull { it.id == selection.first() }?.let(InputControlGroup::of) ?: return null
    return group.takeIf { groupOf(selection.first()).toSet() == selection.toSet() }
}

fun List<InputSurfaceElement>.pressSelection(current: List<String>, hit: String?): List<String> {
    if (hit == null) return emptyList()
    val group = groupOf(hit)
    return when {
        current.toSet() == group.toSet() || current.size > 1 && hit in current -> current
        current.size == 1 && current.first() in group -> listOf(hit)
        else -> group
    }
}

fun List<InputSurfaceElement>.tapSelection(before: List<String>, hit: String?): List<String> {
    if (hit != null && before.size > 1 && hit in before) return listOf(hit)
    return pressSelection(before, hit)
}

private fun List<InputSurfaceElement>.keepsAspect(ids: Collection<String>) = any { it.id in ids && it.kind == InputElementKind.STICK }

private fun List<InputSurfaceElement>.place(ids: Collection<String>, from: SurfaceBounds, to: SurfaceBounds): List<InputSurfaceElement> {
    val sx = to.width / from.width
    val sy = to.height / from.height
    return map { e ->
        if (e.id !in ids) e
        else e.copy(x = to.left + (e.x - from.left) * sx, y = to.top + (e.y - from.top) * sy, width = e.width * sx, height = e.height * sy).bounded()
    }
}

fun List<InputSurfaceElement>.moveSelection(ids: Collection<String>, dx: Float, dy: Float): List<InputSurfaceElement> {
    val b = boundsOf(ids) ?: return this
    val x = dx.coerceIn(-b.left, 1f - b.right)
    val y = dy.coerceIn(-b.top, 1f - b.bottom)
    return place(ids, b, SurfaceBounds(b.left + x, b.top + y, b.right + x, b.bottom + y))
}

fun List<InputSurfaceElement>.resizeSelection(ids: Collection<String>, corner: Int, target: SurfaceBounds): List<InputSurfaceElement> {
    val b = boundsOf(ids) ?: return this
    val members = filter { it.id in ids }
    val minX = MIN_SIZE / members.minOf { it.width }
    val minY = MIN_SIZE / members.minOf { it.height }
    val anchorX = if (corner and 1 != 0) b.right else b.left
    val anchorY = if (corner and 4 != 0) b.bottom else b.top
    val roomX = (if (corner and 1 != 0) anchorX else 1f - anchorX) / b.width
    val roomY = (if (corner and 4 != 0) anchorY else 1f - anchorY) / b.height
    var sx = (target.width / b.width).coerceIn(minX, max(minX, roomX))
    var sy = (target.height / b.height).coerceIn(minY, max(minY, roomY))
    if (keepsAspect(ids)) {
        val s = (if (abs(sx - 1f) >= abs(sy - 1f)) sx else sy).coerceIn(max(minX, minY), max(max(minX, minY), min(roomX, roomY)))
        sx = s; sy = s
    }
    val w = b.width * sx
    val h = b.height * sy
    val left = if (corner and 1 != 0) anchorX - w else anchorX
    val top = if (corner and 4 != 0) anchorY - h else anchorY
    return place(ids, b, SurfaceBounds(left, top, left + w, top + h))
}

fun List<InputSurfaceElement>.scaleSelection(ids: Collection<String>, factor: Float): List<InputSurfaceElement> {
    val b = boundsOf(ids) ?: return this
    val members = filter { it.id in ids }
    val smallest = max(MIN_SIZE / members.minOf { it.width }, MIN_SIZE / members.minOf { it.height })
    val largest = min(1f / b.width, 1f / b.height)
    val f = factor.coerceIn(smallest, max(smallest, largest))
    val w = b.width * f
    val h = b.height * f
    val left = (b.centerX - w / 2f).coerceIn(0f, max(0f, 1f - w))
    val top = (b.centerY - h / 2f).coerceIn(0f, max(0f, 1f - h))
    return place(ids, b, SurfaceBounds(left, top, left + w, top + h))
}

fun List<InputSurfaceElement>.sizePercent(ids: Collection<String>, reference: Map<String, InputSurfaceElement>): Float {
    val ratios = filter { it.id in ids }.mapNotNull { e ->
        reference[e.id]?.takeIf { it.width > 0f && it.height > 0f }?.let { sqrt(e.width * e.height / (it.width * it.height)) }
    }
    return if (ratios.isEmpty()) 100f else 100f * ratios.average().toFloat()
}
