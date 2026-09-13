package cn.elonzh.hanppie.robot.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RouterProvisioningTest {
    @Test fun matchesTheDjiSdkProvisioningVector() {
        assertEquals(
            "Db4T/FDtVP5IA8ps03HLbudF5HaSKsyEPJAooBi0",
            RouterProvisioning.encode("HanppieLab", "12341234", "b6359877"),
        )
    }

    @Test fun measuresUtf8FieldsInBytes() {
        assertEquals(
            "iL5B+1HrDKQcUMpsfYwf+w6EZYBJr0An6B+F4krzRv5P7nDZZg==",
            RouterProvisioning.encode("机器人网络", "passphrase", "0123abcd"),
        )
    }

    @Test fun rejectsValuesTheRobotQrFormatCannotRepresent() {
        assertFailsWith<IllegalArgumentException> { RouterProvisioning.encode("", "12341234", "b6359877") }
        assertFailsWith<IllegalArgumentException> { RouterProvisioning.encode("wifi", "short", "b6359877") }
        assertFailsWith<IllegalArgumentException> { RouterProvisioning.encode("wifi", "1".repeat(32), "b6359877") }
        assertFailsWith<IllegalArgumentException> { RouterProvisioning.encode("wifi", "12341234", "not-an-id") }
    }
}
