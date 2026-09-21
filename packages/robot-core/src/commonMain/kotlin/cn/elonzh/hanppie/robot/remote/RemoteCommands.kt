package cn.elonzh.hanppie.robot.remote

import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.telemetry.GimbalSubscription

/** Ported verbatim from packet-tested src/hanppie/lab/direct.py. */
internal data class RemoteCommand(val receiver: Int, val attr: Int, val set: Int, val id: Int, val payload: String, val flags: String, val control: Boolean)

private fun controlCommand(flags: String = "") =
    RemoteCommand(0, 0, 0, 0, Protocol.neutral.hex(), flags, control = true)

internal val remoteSetup = listOf(
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NO_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SPECIAL_CONTROL, Protocol.MODE_REMOTE, "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SAVE_PREF, "010500", "0000", false),
    RemoteCommand(Protocol.HOST_CHASSIS_CAN, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_WORK_MODE_SET, "00", "0000", false),
    RemoteCommand(Protocol.HOST_SYSTEM, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_COMMON, Protocol.CMD_GET_DEVICE_VERSION, "", "4000", false),
    RemoteCommand(Protocol.HOST_CHASSIS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_FC_RMC, "0200", "6040", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_FC_RMC, "0200", "4000", false),
    RemoteCommand(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_CTRL, "a900000000000000000000000000000000000000000000000000000000000000000000", "4000", false),
    RemoteCommand(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_CTRL, "0900000000000000000000000000000000000000000000000000000000000000000000", "0000", false),
    RemoteCommand(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_CTRL, "51626137646331356139366338346634303865343336633762636137313661653637623231383866363831303062323137", "0000", false),
    RemoteCommand(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_CTRL, "91636130316464306134343966346338663834343030386363396161393134306535366234376530393337326265356232", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_VIRTUAL_BUS, Protocol.CMD_VBUS_DEL_MSG, GimbalSubscription.removePayload().hex(), "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_VIRTUAL_BUS, Protocol.CMD_VBUS_ADD_MSG, GimbalSubscription.addPayload().hex(), "0000", false),
    controlCommand(),
    RemoteCommand(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_CTRL, "08626137646331356139366338346634303865343336633762636137313661653637623231383866363831303062323137", "6000", false),
    RemoteCommand(Protocol.HOST_SCRATCH_SYS, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SCRIPT_CTRL, "a8636130316464306134343966346338663834343030386363396161393134306535366234376530393337326265356232", "0000", false),
    controlCommand(),
    controlCommand(),
    RemoteCommand(Protocol.HOST_WIFI, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_WIFI, Protocol.CMD_WIFI_GET_WORK_MODE, "", "0000", false),
    controlCommand(),
    controlCommand(),
    controlCommand(),
    controlCommand(),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NO_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SPECIAL_CONTROL, Protocol.MODE_REMOTE, "0000", false),
)

internal val remoteEffects = listOf(
    controlCommand("0000"),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SAVE_PREF, "010301", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SAVE_PREF, "010401", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SAVE_PREF, "010201", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_PLAY_SOUND_TASK, "05049012516a00000000", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SAVE_PREF, "010401", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SAVE_PREF, "010201", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SYSTEM_STATUS_CONFIG, "01", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_GAME_STATE_SYNC, "0d000000e903000000000000ea03000001000000eb03000020bf0200ec030000b0040000ed03000064000000ef0300000a000000f003000000000000f1030000b80b0000f2030000dc050000060400000100000007040000000000000804000001000000090400000100000005000000dd050000dc050000de050000c4090000df050000b80b0000e0050000b80b00004006000000879303050000004d0400003075000001000000de0500004e0400001027000001000000dd0500004f0400003075000001000000df050000500400001027000001000000e0050000b0040000000000000100000040060000", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NO_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SPECIAL_CONTROL, "010301", "0000", false),
    RemoteCommand(Protocol.HOST_WIFI, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_WIFI, Protocol.CMD_WIFI_AP_KEEPALIVE, "", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SYSTEM_FUNCTION_CONFIG, "02", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_GAME_STATE_SYNC, "0d000000e903000000000000ea03000001000000eb03000020bf0200ec030000b0040000ed03000064000000ef0300000a000000f003000000000000f1030000b80b0000f2030000dc050000060400000100000007040000000000000804000001000000090400000100000005000000dd050000dc050000de050000c4090000df050000b80b0000e0050000b80b00004006000000879303050000004d0400003075000001000000de0500004e0400001027000001000000dd0500004f0400003075000001000000df050000500400001027000001000000e0050000b0040000000000000100000040060000", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NO_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SPECIAL_CONTROL, "010301", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_GAMECTRL_CMD, "0100", "0000", false),
    RemoteCommand(Protocol.HOST_CAMERA, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_CAMERA, Protocol.CMD_SET_ZOOM_PARAM, "0900006400", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SYSTEM_FUNCTION_CONFIG, "02", "0000", false),
    RemoteCommand(Protocol.HOST_VISION, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_VISION, Protocol.CMD_VISION_CUSTOM, "0000", "0000", false),
    RemoteCommand(Protocol.HOST_VISION, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_VISION, Protocol.CMD_VISION_CUSTOM, "0000", "0000", false),
)

internal val remoteExit = listOf(
    controlCommand("0000"),
    RemoteCommand(Protocol.HOST_CAMERA, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_CAMERA, Protocol.CMD_SET_ZOOM_PARAM, "0900006400", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SYSTEM_FUNCTION_CONFIG, "00", "0000", false),
    RemoteCommand(Protocol.HOST_VISION, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_VISION, Protocol.CMD_VISION_CUSTOM, "0000", "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_PLAY_SOUND_TASK, "06040000000000000000", "0000", false),
    controlCommand("0000"),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NO_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SPECIAL_CONTROL, Protocol.MODE_NORMAL, "0000", false),
    RemoteCommand(Protocol.HOST_HDVT_UAV, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_RM, Protocol.CMD_RM_SAVE_PREF, "010300", "0000", false),
    RemoteCommand(Protocol.HOST_VISION, Protocol.ATTR_NEED_ACK, Protocol.CMDSET_VISION, Protocol.CMD_VISION_CUSTOM, "0000", "0000", false),
)
