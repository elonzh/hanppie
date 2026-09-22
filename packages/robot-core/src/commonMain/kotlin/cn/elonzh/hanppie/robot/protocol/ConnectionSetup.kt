package cn.elonzh.hanppie.robot.protocol

import cn.elonzh.hanppie.robot.telemetry.GimbalSubscription
import cn.elonzh.hanppie.robot.telemetry.ChassisSubscriptions

/** Startup transcript from lab/protocol.py, followed by the existing packet-tested gimbal DDS subscription. */
data class SetupCommand(val receiver: Int, val set: Int, val id: Int,
                        val payload: String = "", val flags: String = "0000", val control: Boolean = false)

val connectionSetup: List<SetupCommand> = listOf(
    SetupCommand(Protocol.HOST_SYSTEM, Protocol.CMDSET_COMMON, Protocol.CMD_GET_DEVICE_VERSION),
    SetupCommand(Protocol.HOST_SYSTEM, Protocol.CMDSET_RM, Protocol.CMD_RM_PRODUCT_ATTRIBUTE_GET, "00"),
    SetupCommand(Protocol.HOST_SYSTEM, Protocol.CMDSET_COMMON, Protocol.CMD_GET_CFG_FILE, "0100000000ffffffff", "4036"),
    SetupCommand(Protocol.HOST_SYSTEM, Protocol.CMDSET_COMMON, Protocol.CMD_GET_CFG_FILE, "01d4030000ffffffff"),
    SetupCommand(Protocol.HOST_WIFI, Protocol.CMDSET_WIFI, Protocol.CMD_WIFI_AP_SET_COUNTRY_CODE, "4a5000004a5000000100"),
    SetupCommand(Protocol.HOST_SYSTEM, Protocol.CMDSET_COMMON, Protocol.CMD_GET_CFG_FILE, "01a8070000ffffffff"),
    SetupCommand(Protocol.HOST_SYSTEM, Protocol.CMDSET_COMMON, Protocol.CMD_GET_CFG_FILE, "017c0b0000ffffffff"),
    SetupCommand(Protocol.HOST_HDVT_UAV, Protocol.CMDSET_SPECIAL, Protocol.CMD_SPECIAL_RM_CONTROL, control = true),
    SetupCommand(Protocol.HOST_SYSTEM, Protocol.CMDSET_COMMON, Protocol.CMD_GET_CFG_FILE, "01500f0000ffffffff"),
    SetupCommand(Protocol.HOST_SYSTEM, Protocol.CMDSET_COMMON, Protocol.CMD_GET_CFG_FILE, "0124130000ffffffff", "6000"),
    SetupCommand(Protocol.HOST_SYSTEM, Protocol.CMDSET_COMMON, Protocol.CMD_GET_CFG_FILE, "01f8160000ffffffff"),
    SetupCommand(Protocol.HOST_SYSTEM, Protocol.CMDSET_COMMON, Protocol.CMD_GET_CFG_FILE, "01cc1a0000ffffffff"),
    SetupCommand(Protocol.HOST_HDVT_UAV, Protocol.CMDSET_SPECIAL, Protocol.CMD_SPECIAL_RM_CONTROL, control = true),
    SetupCommand(Protocol.HOST_HDVT_UAV, Protocol.CMDSET_SPECIAL, Protocol.CMD_SPECIAL_RM_CONTROL, control = true),
    SetupCommand(Protocol.HOST_HDVT_UAV, Protocol.CMDSET_SPECIAL, Protocol.CMD_SPECIAL_RM_CONTROL, control = true),
    SetupCommand(Protocol.HOST_HDVT_UAV, Protocol.CMDSET_VIRTUAL_BUS, Protocol.CMD_VBUS_ADD_NODE, "0200000003"),
    SetupCommand(Protocol.HOST_HDVT_UAV, Protocol.CMDSET_VIRTUAL_BUS, Protocol.CMD_VBUS_DEL_MSG, "000201"),
) + List(2) { SetupCommand(Protocol.HOST_HDVT_UAV, Protocol.CMDSET_VIRTUAL_BUS, Protocol.CMD_VBUS_ADD_MSG,
    "02010000059f22626809000200c49ac5c409000200fd7b4c7809000200ceceb7ee090002009c00a449090002000100") } + listOf(
        // Reuse the packet-tested read-only DDS subscription independently of remote mode.
        SetupCommand(Protocol.HOST_HDVT_UAV, Protocol.CMDSET_VIRTUAL_BUS, Protocol.CMD_VBUS_DEL_MSG, GimbalSubscription.removePayload().hex()),
        SetupCommand(Protocol.HOST_HDVT_UAV, Protocol.CMDSET_VIRTUAL_BUS, Protocol.CMD_VBUS_ADD_MSG, GimbalSubscription.addPayload().hex()),
    ) + ChassisSubscriptions.topics.flatMap { (messageId, uid) -> listOf(
        SetupCommand(Protocol.HOST_HDVT_UAV, Protocol.CMDSET_VIRTUAL_BUS, Protocol.CMD_VBUS_DEL_MSG, byteArrayOf(0, 2, messageId.toByte()).hex()),
        SetupCommand(Protocol.HOST_HDVT_UAV, Protocol.CMDSET_VIRTUAL_BUS, Protocol.CMD_VBUS_ADD_MSG, ChassisSubscriptions.addPayload(messageId, uid).hex()),
    ) }
