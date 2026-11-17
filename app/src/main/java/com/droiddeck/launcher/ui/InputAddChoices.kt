package com.droiddeck.launcher.ui

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*

/** One controller input the editor can add: a control of [kind] that sends only [action], labelled [name]. */
internal data class InputAddChoice(
    val key: String, val name: String, val kind: InputElementKind, val control: InputControl, val action: InputAction,
    val stock: List<String>, val icon: Int = 0,
)

private val BUTTONS = listOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y)
private val CONTROLS = listOf(InputControl.LS, InputControl.RS, InputControl.LB, InputControl.RB, InputControl.LT, InputControl.RT,
    InputControl.L3, InputControl.R3, InputControl.L4, InputControl.R4, InputControl.L5, InputControl.R5, InputControl.SELECT, InputControl.START, InputControl.GUIDE, InputControl.QAM)
private val DPAD_IDS = listOf(InputControl.UP, InputControl.RIGHT, InputControl.DOWN, InputControl.LEFT).map { "legacy:${it.id}" }

private fun controlChoice(control: InputControl) = InputAddChoice("control-${control.id}", control.label, InputElementKind.from(control), control,
    InputAction.Native(control), listOf("legacy:${control.id}"))

internal fun inputAddChoices(context: Context, trackpads: Boolean): List<InputAddChoice> =
    BUTTONS.map(::controlChoice) +
        InputAddChoice("control-dpad", context.getString(R.string.input_group_dpad), InputElementKind.DPAD, InputElementKind.DPAD.control,
            InputAction.Native(), DPAD_IDS) +
        CONTROLS.map(::controlChoice) +
        InputAddChoice("control-keyboard", context.getString(R.string.input_add_keyboard), InputElementKind.BUTTON, InputControl.A,
            InputAction.Command(InputCommand.KEYBOARD), listOf("builtin:keyboard"), InputButtonIcons.KEYBOARD) +
        if (trackpads) listOf(InputControl.LEFT_PAD, InputControl.RIGHT_PAD).map(::controlChoice) else emptyList()
