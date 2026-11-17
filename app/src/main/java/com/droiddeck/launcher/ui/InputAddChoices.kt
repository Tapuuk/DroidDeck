package com.droiddeck.launcher.ui

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*

internal enum class InputAddCategory(val label: Int) {
    CONTROLLER(R.string.input_add_controller), KEYBOARD(R.string.input_add_keyboard), MOUSE(R.string.input_add_mouse)
}

/** One input the editor can add: a control of [kind] that sends only [action], labelled [name]. */
internal data class InputAddChoice(
    val key: String, val title: String, val name: String, val kind: InputElementKind, val control: InputControl, val action: InputAction,
    val click: InputAction? = null, val stock: List<String> = emptyList(), val mouseGestures: Boolean = false,
)

private val CONTROLLER_INPUTS = listOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y, null, InputControl.LS, InputControl.RS,
    InputControl.LB, InputControl.RB, InputControl.LT, InputControl.RT, InputControl.L3, InputControl.R3, InputControl.SELECT, InputControl.START,
    InputControl.GUIDE, InputControl.QAM, InputControl.LEFT_PAD, InputControl.RIGHT_PAD)

private val DPAD_IDS = listOf(InputControl.UP, InputControl.RIGHT, InputControl.DOWN, InputControl.LEFT).map { "legacy:${it.id}" }

internal fun inputAddChoices(context: Context, category: InputAddCategory): List<InputAddChoice> = when (category) {
    InputAddCategory.CONTROLLER -> CONTROLLER_INPUTS.map { control ->
        if (control == null) context.getString(R.string.input_group_dpad).let {
            InputAddChoice("control-dpad", it, it, InputElementKind.DPAD, InputElementKind.DPAD.control, InputAction.Native(), stock = DPAD_IDS)
        } else {
            InputAddChoice("control-${control.id}", control.label, control.label, InputElementKind.from(control), control, InputAction.Native(control),
                stock = listOf("legacy:${control.id}"))
        }
    }
    InputAddCategory.KEYBOARD -> PC_KEY_ROWS.flatten().distinctBy { it.code }.map { key ->
        val label = pcKeyLabel(context, key.code)
        InputAddChoice("key-${key.code}", label, label, InputElementKind.BUTTON, InputControl.A, InputAction.Keys(listOf(key.code)))
    }
    InputAddCategory.MOUSE -> listOf(
        Triple("mouse-left", R.string.input_add_mouse_left, InputAction.MouseButton(272)),
        Triple("mouse-right", R.string.input_add_mouse_right, InputAction.MouseButton(273)),
        Triple("mouse-middle", R.string.input_add_mouse_middle, InputAction.MouseButton(274)),
        Triple("mouse-wheel-up", R.string.input_wheel_up, InputAction.Wheel(-1)),
        Triple("mouse-wheel-down", R.string.input_wheel_down, InputAction.Wheel(1)),
        Triple("mouse-back", R.string.input_mouse_back, InputAction.MouseButton(275)),
        Triple("mouse-forward", R.string.input_mouse_forward, InputAction.MouseButton(276)),
    ).map { (key, title, action) ->
        val name = when (action) {
            InputAction.MouseButton(272) -> context.getString(R.string.input_mouse_left_short)
            InputAction.MouseButton(273) -> context.getString(R.string.input_mouse_right_short)
            InputAction.MouseButton(274) -> context.getString(R.string.input_mouse_middle_short)
            else -> context.getString(title)
        }
        InputAddChoice(key, context.getString(title), name, InputElementKind.BUTTON, InputControl.A, action)
    } + context.getString(R.string.input_add_mouse_trackpad).let {
        InputAddChoice("mouse-trackpad", it, it, InputElementKind.TRACKPAD, InputControl.RIGHT_PAD, InputAction.Pointer(),
            click = InputAction.MouseButton(272), mouseGestures = true)
    }
}
