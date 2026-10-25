package cn.elonzh.hanppie.robot.lab.ui

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.protocol.hexBytes
import kotlin.test.*

class LabUiControlTest {
    private val owner = "0123456789abcdef0123456789abcdef"
    private val stage = LabWidgetKey(LabWidgetType.STAGE, 0)
    private val button = LabWidgetKey(LabWidgetType.BUTTON, 0)
    private fun int(value: Int) = byteArrayOf(1, 4) + ByteArray(4) { (value ushr (it * 8)).toByte() }
    private fun number(value: Float) = byteArrayOf(2, 4) + ByteArray(4) { (value.toBits() ushr (it * 8)).toByte() }
    private fun text(value: String) = value.encodeToByteArray().let { byteArrayOf(3, it.size.toByte()) + it }
    private fun flag(value: Boolean) = byteArrayOf(0, 1, if (value) 1 else 0)

    private inner class Rig {
        var time = 1000L
        var acks = 0
        var failSend = false
        val sent = mutableListOf<ByteArray>()
        val control = LabUiControl({ time }, { acks++ }, { if (failSend) error("transport failed"); sent += it })
        fun frame(payload: ByteArray) = DussFrame(payload.size + 13, 0xc9, 2, 500, 0x40, 0x3f, 0xba, payload, true)
        fun attr(key: LabWidgetKey, function: Int, vararg parameters: ByteArray) {
            control.receive(frame(byteArrayOf(key.type.raw.toByte(), key.index.toByte(), function.toByte(), parameters.size.toByte()) +
                parameters.fold(byteArrayOf()) { bytes, p -> bytes + p }))
        }
        fun create(key: LabWidgetKey) = attr(key, 0)
        fun attach(key: LabWidgetKey, parent: LabWidgetKey = stage) = attr(parent, 128, int(key.type.raw), int(key.index))
        fun status(run: String = owner, value: Int = 2, sender: Int = 9) = control.receive(DussFrame(46, sender, 2, 10, 0,
            0x3f, 0xa5, byteArrayOf(value.toByte()) + run.encodeToByteArray(), true))
        fun ready() {
            control.reset(owner); create(stage); create(button); attach(button)
            status(); control.setVisible(owner, true); control.tick(true)
        }
        fun event(key: LabWidgetKey, event: Int, value: LabUiValue? = null, final: Boolean = false) =
            control.interact(owner, key, control.state.value.widgets.getValue(key).token, event, value, final)
    }

    @Test fun nativeIdleBeforeRunningDoesNotRetireThePendingPanel() {
        val r = Rig(); r.control.reset(owner)
        r.create(stage)
        r.status(value = 0, sender = 0xa9)
        assertEquals(owner, r.control.state.value.runId)
        assertTrue(stage in r.control.state.value.widgets)
        r.control.setVisible(owner, true); r.control.tick(true)
        assertFalse(r.control.state.value.inputEnabled)
        r.status(sender = 0xa9)
        r.create(button); r.attach(button); r.event(button, 0)
        assertEquals("01000000", r.sent.single().hex())
        r.status(value = 0, sender = 0xa9)
        assertNull(r.control.state.value.runId)
        assertTrue(r.control.state.value.widgets.isEmpty())
        r.control.reset(owner); r.status(value = 0, sender = 0xa9)
        assertEquals(owner, r.control.state.value.runId, "New run does not inherit the old started state")
        r.status(value = 5, sender = 0xa9)
        assertNull(r.control.state.value.runId, "Failure before RUNNING still retires the run")
    }

    @Test fun nativeIntegerPivotStillRejectsBooleans() {
        val r = Rig(); r.control.reset(owner)
        val label = LabWidgetKey(LabWidgetType.TEXT, 0)
        r.create(label)
        r.control.receive(r.frame("05000702010400000000010400000000".hexBytes()))
        assertEquals(0f, r.control.state.value.widgets.getValue(label).layout.pivotX)
        assertEquals(0f, r.control.state.value.widgets.getValue(label).layout.pivotY)
        assertNull(r.control.state.value.issue)
        r.attr(label, 7, flag(false), flag(false))
        assertEquals(LabUiIssue.INVALID_ATTRIBUTE, r.control.state.value.issue)
        assertEquals(0f, r.control.state.value.widgets.getValue(label).layout.pivotX)
    }

    @Test fun attributesRequireCorrectIngressButAckReceiptEvenOutsideAnOwnedRun() {
        val r = Rig()
        val frame = r.frame(byteArrayOf(0, 0, 0, 0))
        for (bad in listOf(frame.copy(sender = 9), frame.copy(receiver = 3), frame.copy(attr = 0),
            frame.copy(set = 0x0a), frame.copy(id = 0xbb), frame.copy(valid = false))) r.control.receive(bad)
        assertEquals(0, r.acks)
        r.control.receive(frame)
        assertEquals(1, r.acks)
        assertTrue(r.control.state.value.widgets.isEmpty())
        r.control.reset(owner); r.control.receive(frame)
        assertEquals(1920f, r.control.state.value.widgets.getValue(stage).layout.width)
        assertFalse(r.control.state.value.inputEnabled)
        r.status("f".repeat(32)); r.control.setVisible(owner, true); r.control.tick(true)
        assertFalse(r.control.state.value.inputEnabled)
        r.status(); assertTrue(r.control.state.value.inputEnabled)
    }

    @Test fun precreateAttributesAreBoundedAndOneInvalidCommandDoesNotLoseValidText() {
        val r = Rig(); r.control.reset(owner)
        r.attr(button, 2) // Restored helper has this malformed call; it must not wipe the next update.
        r.attr(button, 128, text("Ready")); r.attr(button, 200)
        r.create(button)
        val first = r.control.state.value.widgets.getValue(button)
        assertEquals("Ready", first.text)
        r.create(button)
        assertEquals(first.token, r.control.state.value.widgets.getValue(button).token)
        assertNotNull(r.control.state.value.issue)
        r.control.receive(r.frame(byteArrayOf(7, 0, 0, 0)))
        assertEquals(LabUiIssue.UNSUPPORTED_ATTRIBUTE, r.control.state.value.issue)
        assertEquals(1, r.control.state.value.widgets.size)
        val pending = LabWidgetKey(LabWidgetType.TEXT, 4)
        repeat(513) { r.attr(pending, 128, text("queued")) }
        assertEquals(LabUiIssue.RESOURCE_LIMIT, r.control.state.value.issue)
        r.create(pending)
        assertEquals("queued", r.control.state.value.widgets.getValue(pending).text)
    }

    @Test fun commonGeometryParentageAndRemovalCannotCreateCyclesOrReuseAHandle() {
        val r = Rig(); r.ready()
        val token = r.control.state.value.widgets.getValue(button).token
        r.attr(button, 4, number(-125f), number(80f))
        r.attr(button, 5, int(240), int(72)); r.attr(button, 6, number(45f))
        r.attr(button, 7, number(0f), number(1f)); r.attr(button, 8, int(4))
        assertEquals(LabWidgetLayout(-125f, 80f, 240f, 72f, 0f, 1f, 45f, 4), r.control.state.value.widgets.getValue(button).layout)
        val nested = LabWidgetKey(LabWidgetType.STAGE, 1)
        r.create(nested); r.attach(nested); r.attach(stage, nested)
        assertNull(r.control.state.value.widgets.getValue(stage).parent)
        r.attr(stage, 129, int(1), int(0)); r.event(button, 0); assertTrue(r.sent.isEmpty())
        r.attach(button); r.attr(button, 1); r.create(button); r.attach(button)
        r.control.interact(owner, button, token, 0)
        assertTrue(r.sent.isEmpty())
        assertTrue(token != r.control.state.value.widgets.getValue(button).token)
    }

    @Test fun nativeTextToggleSliderDropdownAndInputPropertiesRetainTheirMeaning() {
        val r = Rig(); r.ready()
        val color = arrayOf(int(224), int(113), int(60), int(255))
        for (type in listOf(LabWidgetType.BUTTON, LabWidgetType.TOGGLE, LabWidgetType.TEXT, LabWidgetType.INPUT)) {
            val key = LabWidgetKey(type, 0)
            r.create(key); r.attach(key)
            r.attr(key, 128, text("Control")); r.attr(key, 129, *color)
            r.attr(key, 130, int(0)); r.attr(key, 131, int(24))
            val widget = r.control.state.value.widgets.getValue(key)
            assertEquals("Control", widget.text)
            assertEquals(LabUiTextStyle(LabUiColor(224, 113, 60, 255), 0, 24), widget.textStyle)
        }
        val label = LabWidgetKey(LabWidgetType.TEXT, 0)
        r.attr(label, 136, text(" appended")); r.attr(label, 132, *color); r.attr(label, 134, flag(true)); r.attr(label, 135, flag(false))
        assertEquals("Control appended", r.control.state.value.widgets.getValue(label).text)
        assertTrue(r.control.state.value.widgets.getValue(label).borderVisible)
        assertFalse(r.control.state.value.widgets.getValue(label).backgroundVisible)
        val toggle = LabWidgetKey(LabWidgetType.TOGGLE, 0)
        r.attr(toggle, 134, flag(true)); assertTrue(r.control.state.value.widgets.getValue(toggle).checked)
        val input = LabWidgetKey(LabWidgetType.INPUT, 0)
        r.attr(input, 132, text("Your name")); r.attr(input, 134, int(8)); r.attr(input, 135, int(18))
        assertEquals("Your name", r.control.state.value.widgets.getValue(input).hint)
        assertEquals(18, r.control.state.value.widgets.getValue(input).hintStyle.size)
        val dropdown = LabWidgetKey(LabWidgetType.DROPDOWN, 0)
        r.create(dropdown); r.attach(dropdown); r.attr(dropdown, 128, text("One"), text("Two"))
        assertEquals(listOf("One", "Two"), r.control.state.value.widgets.getValue(dropdown).options)
        val slider = LabWidgetKey(LabWidgetType.SLIDER, 0)
        r.create(slider); r.attach(slider); r.attr(slider, 128, int(-10), int(20)); r.attr(slider, 130, *color)
        assertEquals(-10, r.control.state.value.widgets.getValue(slider).minimum)
        assertEquals(20, r.control.state.value.widgets.getValue(slider).maximum)
        assertEquals(LabUiColor(224, 113, 60, 255), r.control.state.value.widgets.getValue(slider).fill)
        r.attr(slider, 128, int(20), int(20))
        assertEquals(20, r.control.state.value.widgets.getValue(slider).value)
        assertTrue(r.sent.isEmpty(), "Device attributes render state without synthesizing user input")
    }

    @Test fun realEventsHaveNativeTypesAndSliderFlushesTheLastValueWithoutAnAutoReplay() {
        val r = Rig(); r.ready()
        r.event(button, 1); r.event(button, 1); r.event(button, 2); r.event(button, 2); r.event(button, 0)
        assertEquals(listOf("01000100", "01000200", "01000000"), r.sent.map { it.hex() })
        val toggle = LabWidgetKey(LabWidgetType.TOGGLE, 0)
        r.create(toggle); r.attach(toggle); r.event(toggle, 0, LabUiValue.Flag(true))
        assertEquals("02000001000101", r.sent.last().hex())
        val slider = LabWidgetKey(LabWidgetType.SLIDER, 0)
        r.create(slider); r.attach(slider); r.attr(slider, 128, int(0), int(100))
        val mark = r.sent.size
        r.event(slider, 0, LabUiValue.Integer(20)); r.time += 50; r.event(slider, 0, LabUiValue.Integer(30))
        assertEquals(mark + 1, r.sent.size)
        r.event(slider, 0, LabUiValue.Integer(30), final = true)
        assertEquals("0300000101041e000000", r.sent.last().hex())
        val sent = r.sent.size; r.time += 200; r.control.tick(true); assertEquals(sent, r.sent.size)
        assertFailsWith<IllegalArgumentException> { r.event(slider, 0, LabUiValue.Integer(101)) }
    }

    @Test fun hiddenBackgroundExpiredAndStaleSurfacesReleaseOnceAndNeverResumeAPress() {
        val r = Rig(); r.ready(); r.event(button, 1)
        r.control.setVisible(owner, false)
        assertEquals("01000200", r.sent.last().hex())
        val mark = r.sent.size; r.event(button, 0); assertEquals(mark, r.sent.size)
        r.control.setVisible(owner, true); r.event(button, 1)
        r.time += 501; r.control.tick(true)
        assertEquals("01000200", r.sent.last().hex())
        val expired = r.sent.size; r.control.tick(true); r.control.setVisible(owner, true)
        assertEquals(expired, r.sent.size)
        r.event(button, 1); r.control.tick(false); assertEquals("01000200", r.sent.last().hex())
        r.control.tick(true); r.event(button, 1); r.attr(button, 2, flag(false))
        assertEquals("01000200", r.sent.last().hex())
        r.attr(button, 2, flag(true)); r.event(button, 1); r.attr(stage, 1)
        assertEquals("01000200", r.sent.last().hex())
        assertTrue(r.control.state.value.widgets.isEmpty())
    }

    @Test fun terminalStateAndNewOwnerInvalidateLocalCallbacksAndDoNotExposeOldContent() {
        val r = Rig(); r.ready()
        val oldToken = r.control.state.value.widgets.getValue(button).token
        r.status(value = 5)
        assertNull(r.control.state.value.runId); assertTrue(r.control.state.value.widgets.isEmpty())
        r.attr(button, 128, text("late old data")); assertTrue(r.control.state.value.widgets.isEmpty())
        val next = "f".repeat(32)
        r.control.reset(next); r.create(stage); r.create(button); r.attach(button); r.status(next)
        r.control.setVisible(next, true); r.control.tick(true)
        r.status(owner, 0); assertEquals(next, r.control.state.value.runId)
        r.control.setVisible(owner, false); assertTrue(r.control.state.value.inputEnabled)
        r.control.interact(owner, button, oldToken, 0); assertTrue(r.sent.isEmpty())
        r.control.close(); assertTrue(r.control.state.value.widgets.isEmpty())
        r.create(stage); assertTrue(r.control.state.value.widgets.isEmpty())
    }
}
