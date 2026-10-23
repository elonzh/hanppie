package cn.elonzh.hanppie.robot.lab.ui

import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.protocol.hexBytes
import kotlin.test.*

class LabUiProtocolTest {
    @Test fun nativeAttributeVectorDecodesTypedValuesAndRequiresExactLengths() {
        val vector = "010780040001010104feffffff02040000c03f0306e4bda0e5a5bd".hexBytes()
        val result = assertNotNull(LabUiProtocol.attribute(vector))
        assertEquals(1, result.type); assertEquals(7, result.index); assertEquals(128, result.function)
        assertEquals(listOf(LabUiValue.Flag(true), LabUiValue.Integer(-2), LabUiValue.Decimal(1.5f), LabUiValue.Text("你好")), result.parameters)
        for (end in 0 until vector.size) assertNull(LabUiProtocol.attribute(vector.copyOf(end)))
        assertNull(LabUiProtocol.attribute(vector + 0))
    }

    @Test fun nativeEventVectorsUseButtonActionsBooleanIntAndUtf8ByteLength() {
        fun key(type: LabWidgetType) = LabWidgetKey(type, 3)
        assertEquals("01030100", LabUiProtocol.event(key(LabWidgetType.BUTTON), 1, null).hex())
        assertEquals("01030200", LabUiProtocol.event(key(LabWidgetType.BUTTON), 2, null).hex())
        assertEquals("02030001000101", LabUiProtocol.event(key(LabWidgetType.TOGGLE), 0, LabUiValue.Flag(true)).hex())
        assertEquals("030300010104f6ffffff", LabUiProtocol.event(key(LabWidgetType.SLIDER), 0, LabUiValue.Integer(-10)).hex())
        assertEquals("04030001010402000000", LabUiProtocol.event(key(LabWidgetType.DROPDOWN), 0, LabUiValue.Integer(2)).hex())
        assertEquals("060300010306e4bda0e5a5bd", LabUiProtocol.event(key(LabWidgetType.INPUT), 0, LabUiValue.Text("你好")).hex())
        assertFailsWith<IllegalArgumentException> { LabUiProtocol.event(key(LabWidgetType.INPUT), 0, LabUiValue.Text("你".repeat(86))) }
    }

    @Test fun malformedTypesUtf8NonfiniteAndOversizedAttributesAreRejected() {
        for (hex in listOf("0100800100020100", "01008001000102", "010080010103010000", "0100800102040000807f",
            "0100800102040000c07f", "010080010302c080", "01008001040100", "0100800000")) {
            assertNull(LabUiProtocol.attribute(hex.hexBytes()), hex)
        }
        assertNull(LabUiProtocol.attribute(ByteArray(1011)))
        assertNotNull(LabUiProtocol.attribute("00000000".hexBytes()))
        assertNotNull(LabUiProtocol.attribute("050080010300".hexBytes()))
    }
}
