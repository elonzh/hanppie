package cn.elonzh.hanppie.robot.maintenance.firmware

import cn.elonzh.hanppie.robot.product.WorkingRobotDevice
import cn.elonzh.hanppie.robot.telemetry.BatteryTelemetry
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import kotlin.test.*

class FirmwareCompatibilityTest {
    @Test fun hardwareVariantsStayDistinctAndAdditionalPackageVariantsDoNotReplaceMissingOnes() {
        val old = config(listOf(module("0400", "hw01")))
        val otherBatch = config(listOf(module("0400", "")))
        assertEquals(setOf(FirmwareCompatibilityIssue.MISSING_VARIANT), otherBatch.compatibilityWith(old).issues)
        val full = config(listOf(module("0400", ""), module("0400", "hw01")))
        assertTrue(full.compatibilityWith(old).matchesMetadata)
        assertTrue(full.matchesInstalled(old)); assertFalse(otherBatch.matchesInstalled(old))
    }
    @Test fun productAndVersionChecksUseNumericSegmentsAndNeverGuessInvalidValues() {
        val current = config(version = "00.06.0521")
        assertTrue(config(version = "0.6.521").matchesInstalled(current))
        assertTrue(config(version = "00.06.0522").compatibilityWith(current).matchesMetadata)
        assertEquals(setOf(FirmwareCompatibilityIssue.DOWNGRADE), config(version = "00.06.0520").compatibilityWith(current).issues)
        assertEquals(setOf(FirmwareCompatibilityIssue.PRODUCT_MISMATCH), current.copy(deviceId = "another").compatibilityWith(current).issues)
        assertEquals(setOf(FirmwareCompatibilityIssue.INVALID_VERSION), config(version = "unknown").compatibilityWith(current).issues)
        assertFalse(config(version = "unknown").matchesInstalled(current))
    }
    @Test fun readbackRequiresModuleVersionsAsWellAsTheOverallVersion() {
        val expected = config(listOf(module("0306", "hw02", "01.03.32.00")))
        val mixed = config(listOf(module("0306", "hw02", "01.03.31.00")))
        assertTrue(expected.compatibilityWith(mixed).matchesMetadata)
        assertFalse(expected.matchesInstalled(mixed))
        assertEquals("01.03.32.00", expected.compatibilityWith(mixed).changes.single().available)
    }
    @Test fun nativeBatteryThresholdAndExplicitExternalPowerAreSeparate() {
        val motors = (0x0314..0x0317).map { WorkingRobotDevice(it, emptyList()) }
        fun health(percent: Int?, online: Boolean = true) = RobotHealth(modules = motors + if (online) listOf(WorkingRobotDevice(0x030a, emptyList())) else emptyList(),
            battery = percent?.let { BatteryTelemetry(0, 0, 0, it) })
        assertTrue(firmwareReadiness(health(50)).ready)
        assertEquals(setOf(FirmwareReadinessIssue.BATTERY_LOW), firmwareReadiness(health(49)).issues)
        assertEquals(setOf(FirmwareReadinessIssue.BATTERY_LOW), firmwareReadiness(health(0)).issues)
        assertEquals(setOf(FirmwareReadinessIssue.POWER_UNKNOWN), firmwareReadiness(health(null)).issues)
        val external = firmwareReadiness(health(null, false))
        assertTrue(external.ready); assertTrue(external.needsPowerConfirmation); assertTrue(external.missingMotorIds.isEmpty())
    }
    @Test fun missingModulesAndKnownAddressConflictsHaveDifferentDecisions() {
        assertEquals(setOf(FirmwareReadinessIssue.MODULES_UNKNOWN), firmwareReadiness(RobotHealth()).issues)
        val h = RobotHealth(modules = listOf(WorkingRobotDevice(0x0314, emptyList()), WorkingRobotDevice(0x0100, listOf(0x800a))))
        val readiness = firmwareReadiness(h)
        assertEquals(setOf(0x0315, 0x0316, 0x0317), readiness.missingMotorIds)
        assertEquals(setOf(FirmwareReadinessIssue.ADDRESS_CONFLICT), readiness.issues)
    }
}

private fun config(modules: List<FirmwareModule> = listOf(module("0306", "hw02")), version: String = "00.06.0521") =
    FirmwareManifest("xw607", version, emptyMap(), modules, 519)
private fun module(id: String, type: String, version: String = "01.03.32.00") = FirmwareModule(id, version, type, "", 3,
    "900150983cd24fb0d6963f7d28e17f72", "m-$id-$type.sig")
