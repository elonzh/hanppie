package cn.elonzh.hanppie.ui

import androidx.compose.ui.input.key.Key
import kotlinx.serialization.Serializable

@Serializable
internal enum class ControlAction {
    Forward, Backward, StrafeLeft, StrafeRight,
    RotateCounterclockwise, RotateClockwise,
    GimbalUp, GimbalDown, GimbalLeft, GimbalRight,
    Gear1, Gear2, Gear3, Gear4, Gear5,
    Creep, Fire, SwitchAmmo, Photo, Recording, PushToTalk, RobotMicrophone, Stop,
}

@Serializable
internal enum class ControlKey {
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    Digit0, Digit1, Digit2, Digit3, Digit4, Digit5, Digit6, Digit7, Digit8, Digit9,
    ArrowUp, ArrowDown, ArrowLeft, ArrowRight, Space, Escape, Shift;

    val label: String get() = when (this) {
        Digit0 -> "0"; Digit1 -> "1"; Digit2 -> "2"; Digit3 -> "3"; Digit4 -> "4"
        Digit5 -> "5"; Digit6 -> "6"; Digit7 -> "7"; Digit8 -> "8"; Digit9 -> "9"
        ArrowUp -> "↑"; ArrowDown -> "↓"; ArrowLeft -> "←"; ArrowRight -> "→"
        Space -> "Space"; Escape -> "Esc"; Shift -> "Shift"
        else -> name
    }

    fun matches(key: Key): Boolean = when (this) {
        Digit0 -> key == Key.Zero || key == Key.NumPad0
        Digit1 -> key == Key.One || key == Key.NumPad1
        Digit2 -> key == Key.Two || key == Key.NumPad2
        Digit3 -> key == Key.Three || key == Key.NumPad3
        Digit4 -> key == Key.Four || key == Key.NumPad4
        Digit5 -> key == Key.Five || key == Key.NumPad5
        Digit6 -> key == Key.Six || key == Key.NumPad6
        Digit7 -> key == Key.Seven || key == Key.NumPad7
        Digit8 -> key == Key.Eight || key == Key.NumPad8
        Digit9 -> key == Key.Nine || key == Key.NumPad9
        ArrowUp -> key == Key.DirectionUp
        ArrowDown -> key == Key.DirectionDown
        ArrowLeft -> key == Key.DirectionLeft
        ArrowRight -> key == Key.DirectionRight
        Space -> key == Key.Spacebar
        Escape -> key == Key.Escape
        Shift -> key == Key.ShiftLeft || key == Key.ShiftRight
        else -> key == letterKey(this)
    }

    companion object {
        fun from(key: Key): ControlKey? = entries.firstOrNull { it.matches(key) }

        private fun letterKey(key: ControlKey): Key = when (key) {
            A -> Key.A; B -> Key.B; C -> Key.C; D -> Key.D; E -> Key.E; F -> Key.F
            G -> Key.G; H -> Key.H; I -> Key.I; J -> Key.J; K -> Key.K; L -> Key.L
            M -> Key.M; N -> Key.N; O -> Key.O; P -> Key.P; Q -> Key.Q; R -> Key.R
            S -> Key.S; T -> Key.T; U -> Key.U; V -> Key.V; W -> Key.W; X -> Key.X
            Y -> Key.Y; Z -> Key.Z
            else -> error("not a letter")
        }
    }
}

@Serializable
internal data class KeyBinding(val key: ControlKey, val shift: Boolean = false) {
    val label: String get() = if (shift && key != ControlKey.Shift) "Shift + ${key.label}" else key.label
}

@Serializable
internal data class ControlShortcuts(
    val bindings: Map<ControlAction, KeyBinding> = defaults(),
) {
    operator fun get(action: ControlAction): KeyBinding = bindings[action] ?: defaults().getValue(action)
    fun bind(action: ControlAction, binding: KeyBinding) = copy(bindings = bindings + (action to binding))

    companion object {
        fun defaults(): Map<ControlAction, KeyBinding> = mapOf(
            ControlAction.Forward to KeyBinding(ControlKey.W),
            ControlAction.Backward to KeyBinding(ControlKey.S),
            ControlAction.StrafeLeft to KeyBinding(ControlKey.A),
            ControlAction.StrafeRight to KeyBinding(ControlKey.D),
            ControlAction.RotateCounterclockwise to KeyBinding(ControlKey.Q),
            ControlAction.RotateClockwise to KeyBinding(ControlKey.E),
            ControlAction.GimbalUp to KeyBinding(ControlKey.ArrowUp),
            ControlAction.GimbalDown to KeyBinding(ControlKey.ArrowDown),
            ControlAction.GimbalLeft to KeyBinding(ControlKey.ArrowLeft),
            ControlAction.GimbalRight to KeyBinding(ControlKey.ArrowRight),
            ControlAction.Gear1 to KeyBinding(ControlKey.Digit1),
            ControlAction.Gear2 to KeyBinding(ControlKey.Digit2),
            ControlAction.Gear3 to KeyBinding(ControlKey.Digit3),
            ControlAction.Gear4 to KeyBinding(ControlKey.Digit4),
            ControlAction.Gear5 to KeyBinding(ControlKey.Digit5),
            ControlAction.Creep to KeyBinding(ControlKey.Shift),
            ControlAction.Fire to KeyBinding(ControlKey.Space),
            ControlAction.SwitchAmmo to KeyBinding(ControlKey.G),
            ControlAction.Photo to KeyBinding(ControlKey.R),
            ControlAction.Recording to KeyBinding(ControlKey.R, shift = true),
            ControlAction.PushToTalk to KeyBinding(ControlKey.T),
            ControlAction.RobotMicrophone to KeyBinding(ControlKey.M),
            ControlAction.Stop to KeyBinding(ControlKey.Escape),
        )
    }
}

internal fun ControlShortcuts.isHeld(action: ControlAction, keys: Set<Key>, shiftHeld: Boolean): Boolean {
    val binding = this[action]
    return keys.any(binding.key::matches) && (!binding.shift || shiftHeld)
}

internal fun ControlShortcuts.edgeAction(key: Key, shiftHeld: Boolean): ControlAction? {
    val exact = ControlAction.entries.firstOrNull { action ->
        val binding = this[action]
        binding.key.matches(key) && (binding.key == ControlKey.Shift || binding.shift == shiftHeld)
    }
    return exact ?: ControlAction.entries.firstOrNull { action ->
        val binding = this[action]
        binding.key.matches(key) && !binding.shift
    }
}

internal fun ControlShortcuts.supports(key: Key): Boolean =
    key == Key.Escape || ControlAction.entries.any { this[it].key.matches(key) }
