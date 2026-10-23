package cn.elonzh.hanppie.robot.maintenance.firmware

import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.robot.telemetry.RobotHealth

enum class FirmwareCompatibilityIssue { PRODUCT_MISMATCH, DOWNGRADE, MISSING_VARIANT, INVALID_VERSION }
data class FirmwareVariantChange(val moduleId: String, val hardwareType: String, val installed: String, val available: String?)
data class FirmwareCompatibility(val issues: Set<FirmwareCompatibilityIssue>, val changes: List<FirmwareVariantChange>) {
    val matchesMetadata: Boolean get() = issues.isEmpty()
}

/** Compares declared product/module metadata. Device signature and rollback enforcement remain device-owned. */
fun FirmwareManifest.compatibilityWith(installed: FirmwareManifest): FirmwareCompatibility {
    val issues = mutableSetOf<FirmwareCompatibilityIssue>()
    if (deviceId != installed.deviceId) issues += FirmwareCompatibilityIssue.PRODUCT_MISMATCH
    val comparison = compareFirmwareVersions(version, installed.version)
    if (comparison == null) issues += FirmwareCompatibilityIssue.INVALID_VERSION
    else if (comparison < 0) issues += FirmwareCompatibilityIssue.DOWNGRADE
    val changes = installed.modules.map { old ->
        val next = modules.singleOrNull { it.id == old.id && it.hardwareType == old.hardwareType }
        if (next == null) issues += FirmwareCompatibilityIssue.MISSING_VARIANT
        if (compareFirmwareVersions(old.version, next?.version ?: old.version) == null) issues += FirmwareCompatibilityIssue.INVALID_VERSION
        FirmwareVariantChange(old.id, old.hardwareType, old.version, next?.version)
    }
    return FirmwareCompatibility(issues.toSet(), changes)
}

internal fun FirmwareManifest.matchesInstalled(installed: FirmwareManifest): Boolean = deviceId == installed.deviceId &&
    compareFirmwareVersions(version, installed.version) == 0 && installed.modules.isNotEmpty() && installed.modules.all { old ->
        modules.singleOrNull { it.id == old.id && it.hardwareType == old.hardwareType }?.let {
            compareFirmwareVersions(it.version, old.version) == 0
        } == true
    }

private fun compareFirmwareVersions(first: String, second: String): Int? {
    val a = versionParts(first) ?: return null; val b = versionParts(second) ?: return null
    for (i in 0 until maxOf(a.size, b.size)) {
        val result = (a.getOrNull(i) ?: 0).compareTo(b.getOrNull(i) ?: 0)
        if (result != 0) return result
    }
    return 0
}

enum class FirmwareReadinessIssue { MODULES_UNKNOWN, POWER_UNKNOWN, BATTERY_LOW, ADDRESS_CONFLICT }
data class FirmwareReadiness(val issues: Set<FirmwareReadinessIssue>, val missingMotorIds: Set<Int>, val needsPowerConfirmation: Boolean) {
    val ready: Boolean get() = issues.isEmpty()
}

/** Same 50% threshold, four motor identities and address-conflict codes as the native installation workflow. */
fun firmwareReadiness(health: RobotHealth): FirmwareReadiness {
    val issues = mutableSetOf<FirmwareReadinessIssue>()
    val modules = health.modules
    if (modules == null) issues += FirmwareReadinessIssue.MODULES_UNKNOWN
    val batteryOnline = modules?.any { it.deviceId == RobotComponent.BATTERY.deviceId } == true
    val percent = health.battery?.percent
    if (batteryOnline && percent == null) issues += FirmwareReadinessIssue.POWER_UNKNOWN
    if (percent != null && percent < 50 && (percent > 0 || batteryOnline)) issues += FirmwareReadinessIssue.BATTERY_LOW
    if (health.issues.any { it.code in 0x0100800a..0x0100800d }) issues += FirmwareReadinessIssue.ADDRESS_CONFLICT
    val missing = (0x0314..0x0317).filterTo(mutableSetOf()) { id -> modules?.none { it.deviceId == id } != false }
    return FirmwareReadiness(issues.toSet(), missing, modules != null && !batteryOnline && (percent == null || percent == 0))
}

data class FirmwareInstallConsent(val missingMotorIds: Set<Int> = emptySet(), val externalPowerConfirmed: Boolean = false)
