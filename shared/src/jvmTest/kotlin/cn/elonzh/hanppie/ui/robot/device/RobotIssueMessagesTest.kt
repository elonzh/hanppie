package cn.elonzh.hanppie.ui.robot.device

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.telemetry.RobotIssue
import kotlin.test.*

class RobotIssueMessagesTest {
    @Test fun fixedClientMessageIdentitiesAllHaveAnExplanation() {
        // Message identities from macOS build 239 Language.csv; no vendor wording is embedded.
        val codes = listOf(
            0x01008000, 0x0100c001, 0x01008004, 0x0100c005, 0x0100c007, 0x0100c008,
            0x0100c009, 0x0100800a, 0x0100800b, 0x0100800c, 0x0100800d, 0x0100c00e,
            0x0100c00f, 0x01004200, 0x01004201, 0x0104c400, 0x0104c401, 0x01048402,
            0x03008065, 0x03008101, 0x03008165, 0x03008197, 0x03008198, 0x03008202,
            0x03008204, 0x03008201, 0x0300c205, 0x03008207, 0x03008208, 0x0300c209,
            0x0300c20a, 0x0300820b, 0x0300c401, 0x03008501, 0x0300c502, 0x0300cb01,
            0x03008b0b, 0x0300cc02, 0x03008c0c, 0x0300cd03, 0x03008d0d, 0x0300ce04,
            0x03008e0e, 0x0314d401, 0x0314d488, 0x0314d489, 0x0314d48a, 0x0314d48b,
            0x0315d501, 0x0315d588, 0x0315d589, 0x0315d58a, 0x0315d58b, 0x0316d601,
            0x0316d688, 0x0316d689, 0x0316d68a, 0x0316d68b, 0x0317d701, 0x0317d788,
            0x0317d789, 0x0317d78a, 0x0317d78b, 0x030a8a01, 0x0400c000, 0x0400c001,
            0x0400c002, 0x0400c003, 0x0400c004, 0x04004005, 0x04008006, 0x04008007,
            0x0400c008, 0x0400c009, 0x0400c00b, 0x0400c00c, 0x0400c100, 0x17008001,
            0x17008002, 0x17008004, 0x1700c003, 0x18018000, 0x18028000, 0x18038000,
            0x18048000, 0x18058000, 0x18058001, 0x18068000, 0x18068001, 0x01000014,
            0x01000015, 0x01000016, 0x01000017, 0x01000018, 0x0100001a, 0x0100001b,
            0x0100001c, 0x0100001d, 0x0100001e, 0x0100001f, 0x01000100, 0x01000300,
            0x01000301, 0x01000302, 0x01000303, 0x01000006,
        )
        for (code in codes) assertNotEquals(Res.string.health_unknown_issue,
            robotIssueMessage(RobotIssue(code ushr 16, code and 0xffff)), code.toString(16))
    }
    @Test fun rangeBoundariesAndProtectionMessagesRemainDistinct() {
        assertEquals(Res.string.health_motor_fault, robotIssueMessage(RobotIssue(0x314, 0xd487)))
        assertEquals(Res.string.health_motor_protection, robotIssueMessage(RobotIssue(0x314, 0xd488)))
        assertEquals(Res.string.health_motor_protection, robotIssueMessage(RobotIssue(0x317, 0xd78a)))
        assertEquals(Res.string.health_unknown_issue, robotIssueMessage(RobotIssue(0x317, 0xd7ff)))
        assertEquals(Res.string.health_gimbal_blocked, robotIssueMessage(RobotIssue(0x400, 0x8007)))
        assertEquals(Res.string.health_blaster_protection, robotIssueMessage(RobotIssue(0x1700, 0x8004)))
        assertEquals(Res.string.health_unknown_issue, robotIssueMessage(RobotIssue(0xabcd, 0xffff)))
    }
}
