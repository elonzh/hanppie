package cn.elonzh.hanppie.ui.robot.remote.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType

/** Raw UIKit force is already carried by Compose touch events; the platform supplies capability only. */
internal val LocalPressureTouchAvailable = staticCompositionLocalOf<() -> Boolean> { { false } }

internal data class PressureContact(val id: Long, val pressed: Boolean, val freshDown: Boolean, val force: Float)

internal fun pressureContact(change: PointerInputChange): PressureContact? =
    if (change.type == PointerType.Touch) PressureContact(change.id.value, change.pressed,
        change.pressed && !change.previousPressed, change.pressure) else null

/** A retired contact cannot rejoin until a fresh down, including after a control revision changes. */
internal class PressureFireInput(sensitivity: Int) {
    init { require(sensitivity in 1..4) }
    private val threshold = 7f - sensitivity
    private val admitted = mutableSetOf<Long>()
    private var revision: Long? = null
    private var held by mutableStateOf(false)

    fun isHeld(controlRevision: Long): Boolean = held && revision == controlRevision

    fun update(contacts: List<PressureContact>, controlRevision: Long) {
        if (revision != controlRevision) retire()
        revision = controlRevision
        admitted.retainAll(contacts.filter { it.pressed }.map { it.id }.toSet())
        contacts.filter { it.pressed && it.freshDown }.forEach { admitted.add(it.id) }
        held = contacts.any { it.id in admitted && it.pressed && it.force.isFinite() && it.force > threshold }
    }

    fun retire() { held = false; admitted.clear(); revision = null }
}
