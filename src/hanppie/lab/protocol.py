"""Binary protocol primitives for the RoboMaster App-compatible transport.

The outer envelope and setup sequence are interoperability facts recovered from
the currently verified S1/App traffic. DUSS CRC calculation is self-contained.
"""

from __future__ import annotations

import secrets
import struct
from collections.abc import Sequence
from dataclasses import dataclass

APP_PORT = 45678
ROBOT_APP_PORT = 56789
LOCAL_CONTROL_PORT = 10609
ROBOT_CONTROL_PORT = 10607
ROBOT_FTP_PORT = 21
INITIAL_DUSS_SEQUENCE = 10072
DUSS_MAGIC = 0x55
CRC8_INIT = 0x77
CRC16_INIT = 0x3692
NEUTRAL_CONTROL = bytes.fromhex("0000042000010840000210")

# Host / Node IDs (host2byte: (index << 5) | (host & 0x1F))
HOST_CAMERA = 0x01
HOST_MOBILE = 0x02
HOST_CHASSIS_CAN = 0x03
HOST_GIMBAL = 0x04
HOST_WIFI = 0x07
HOST_HDVT_UAV = 0x09
HOST_GUN = 0x17
HOST_SYSTEM = 0x28
HOST_SCRATCH_CLIENT = 0x42
HOST_SCRATCH_SYS = 0xA9
HOST_CHASSIS = 0xC3
HOST_SCRATCH_SCRIPT = 0xC9
HOST_VISION = 0xF1

# Attr
ATTR_NO_ACK = 0x00
ATTR_NEED_ACK_NO_FINISH = 0x20
ATTR_NEED_ACK = 0x40
ATTR_ACK = 0x80
ATTR_RESP_NEED_ACK = 0xC0

# CmdSet
CMDSET_COMMON = 0x00
CMDSET_SPECIAL = 0x01
CMDSET_CAMERA = 0x02
CMDSET_FC = 0x03
CMDSET_GIMBAL = 0x04
CMDSET_WIFI = 0x07
CMDSET_DM368 = 0x08
CMDSET_HDVT = 0x09
CMDSET_VISION = 0x0A
CMDSET_RM = 0x3F
CMDSET_VIRTUAL_BUS = 0x48

# CmdID
# Common (cmdset 0x00)
CMD_GET_DEVICE_VERSION = 0x01
CMD_FW_TRANSMIT = 0x09
CMD_GET_CFG_FILE = 0x4F

# Special (cmdset 0x01)
CMD_SPECIAL_RM_CONTROL = 0x04

# Camera (cmdset 0x02)
CMD_CAPTURE = 0x01
CMD_SET_VIDEO_FORMAT = 0x18
CMD_SET_ZOOM_PARAM = 0x34

# Gimbal (cmdset 0x04)
CMD_GIMBAL_EXT_CTRL_ACCEL = 0x0C
CMD_GIMBAL_ROTATE_SPEED = 0x69

# Wi-Fi (cmdset 0x07)
CMD_WIFI_AP_PUSH_RSSI = 0x09
CMD_WIFI_AP_KEEPALIVE = 0x17
CMD_WIFI_AP_SET_COUNTRY_CODE = 0x30
CMD_WIFI_GET_WORK_MODE = 0x39
CMD_WIFI_CONFIG_BY_QRCODE = 0x3B

# RM (cmdset 0x3F)
CMD_RM_SPECIAL_CONTROL = 0x04
CMD_RM_GAME_STATE_SYNC = 0x09
CMD_RM_GAMECTRL_CMD = 0x0A
CMD_RM_MODULE_STATUS_PUSH = 0x12
CMD_RM_WORK_MODE_SET = 0x19
CMD_RM_PLAY_SOUND = 0x1A
CMD_RM_AUDIO_TO_APP = 0x1D
CMD_RM_SET_AUDIO_STATUS = 0x1E
CMD_RM_WHEEL_SPEED_SET = 0x20
CMD_RM_SPEED_SET = 0x21
CMD_RM_SPEED_MODE_SET = 0x28
CMD_RM_LED_COLOR_SET = 0x33
CMD_RM_EXIT_LOW_POWER_MODE = 0x4C
CMD_RM_SHOOT_CMD = 0x51
CMD_RM_GUN_LED_SET = 0x55
CMD_RM_GET_SIGHT_BEAD_POSITION = 0x57
CMD_RM_SYSTEM_FUNCTION_CONFIG = 0x59
CMD_RM_SYSTEM_STATUS_CONFIG = 0x5B
CMD_RM_AUDIO_TRANSFER = 0x5F
CMD_RM_FC_RMC = 0x66
CMD_RM_SAVE_PREF = 0x77
CMD_RM_SCRIPT_DOWNLOAD_DATA = 0xA1
CMD_RM_SCRIPT_DOWNLOAD_FINSH = 0xA2
CMD_RM_SCRIPT_CTRL = 0xA3
CMD_RM_SCRIPT_CUSTOM_INFO_PUSH = 0xA4
CMD_RM_SCRIPT_BLOCK_STATUS_PUSH = 0xA5
CMD_RM_SUB_MOBILE_INFO = 0xAB
CMD_RM_PLAY_SOUND_TASK = 0xB3
CMD_RM_CUSTOM_UI_ATTRIBUTE_SET = 0xBA
CMD_RM_STREAM_CTRL = 0xD2
CMD_RM_PRODUCT_ATTRIBUTE_GET = 0xFE

# Virtual Bus (cmdset 0x48)
CMD_VBUS_ADD_NODE = 0x01
CMD_VBUS_NODE_RESET = 0x02
CMD_VBUS_ADD_MSG = 0x03
CMD_VBUS_DEL_MSG = 0x04
CMD_VBUS_DATA_ANALYSIS = 0x08

# Vision (cmdset 0x0A)
CMD_VISION_CUSTOM = 0xA3

# System mode strings
MODE_NORMAL = "000300"
MODE_LAB = "020302"
MODE_REMOTE = "0b0300"
MODE_EXIT_PREF = "010300"

# SCRIPT_CTRL markers (cmd 0xA3)
SCRIPT_CTRL_METADATA_FULL = 0x21
SCRIPT_CTRL_METADATA_GUID = 0x2D
SCRIPT_CTRL_START = 0x52
SCRIPT_CTRL_STOP = 0x55

# Lab game state sync parameters
GAME_STATE_SYNC_LAB_PARAMS = (
    "05000000ea03000000000000ef0300000a000000f003000000000000"
    "f1030000b80b0000f2030000dc0500000000000000000000"
)

_PRECONNECT_TEMPLATE = bytes.fromhex(
    "3080dc6800000004d84664006400c005140000640064006400c005140000"
    "640014006400c00514000064000101040102"
)

# The minimal App connection setup observed before entering Lab mode. Entries
# are (envelope, sender, receiver, attr, cmdset, cmdid, payload_hex, flags_hex).
APP_CONNECTION_SETUP = (
    (
        "direct",
        HOST_MOBILE,
        HOST_SYSTEM,
        ATTR_NEED_ACK,
        CMDSET_COMMON,
        CMD_GET_DEVICE_VERSION,
        "",
        "0000",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_SYSTEM,
        ATTR_NEED_ACK,
        CMDSET_RM,
        CMD_RM_PRODUCT_ATTRIBUTE_GET,
        "00",
        "0000",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_SYSTEM,
        ATTR_NEED_ACK,
        CMDSET_COMMON,
        CMD_GET_CFG_FILE,
        "0100000000ffffffff",
        "4036",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_SYSTEM,
        ATTR_NEED_ACK,
        CMDSET_COMMON,
        CMD_GET_CFG_FILE,
        "01d4030000ffffffff",
        "0000",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_WIFI,
        ATTR_NEED_ACK,
        CMDSET_WIFI,
        CMD_WIFI_AP_SET_COUNTRY_CODE,
        "4a5000004a5000000100",
        "0000",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_SYSTEM,
        ATTR_NEED_ACK,
        CMDSET_COMMON,
        CMD_GET_CFG_FILE,
        "01a8070000ffffffff",
        "0000",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_SYSTEM,
        ATTR_NEED_ACK,
        CMDSET_COMMON,
        CMD_GET_CFG_FILE,
        "017c0b0000ffffffff",
        "0000",
    ),
    (
        "control",
        HOST_MOBILE,
        HOST_HDVT_UAV,
        ATTR_NO_ACK,
        CMDSET_SPECIAL,
        CMD_SPECIAL_RM_CONTROL,
        NEUTRAL_CONTROL.hex(),
        "c8ec",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_SYSTEM,
        ATTR_NEED_ACK,
        CMDSET_COMMON,
        CMD_GET_CFG_FILE,
        "01500f0000ffffffff",
        "0000",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_SYSTEM,
        ATTR_NEED_ACK,
        CMDSET_COMMON,
        CMD_GET_CFG_FILE,
        "0124130000ffffffff",
        "6000",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_SYSTEM,
        ATTR_NEED_ACK,
        CMDSET_COMMON,
        CMD_GET_CFG_FILE,
        "01f8160000ffffffff",
        "0000",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_SYSTEM,
        ATTR_NEED_ACK,
        CMDSET_COMMON,
        CMD_GET_CFG_FILE,
        "01cc1a0000ffffffff",
        "0000",
    ),
    (
        "control",
        HOST_MOBILE,
        HOST_HDVT_UAV,
        ATTR_NO_ACK,
        CMDSET_SPECIAL,
        CMD_SPECIAL_RM_CONTROL,
        NEUTRAL_CONTROL.hex(),
        "f0ec",
    ),
    (
        "control",
        HOST_MOBILE,
        HOST_HDVT_UAV,
        ATTR_NO_ACK,
        CMDSET_SPECIAL,
        CMD_SPECIAL_RM_CONTROL,
        NEUTRAL_CONTROL.hex(),
        "f0ec",
    ),
    (
        "control",
        HOST_MOBILE,
        HOST_HDVT_UAV,
        ATTR_NO_ACK,
        CMDSET_SPECIAL,
        CMD_SPECIAL_RM_CONTROL,
        NEUTRAL_CONTROL.hex(),
        "f0ec",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_HDVT_UAV,
        ATTR_NEED_ACK,
        CMDSET_VIRTUAL_BUS,
        CMD_VBUS_ADD_NODE,
        "0200000003",
        "0000",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_HDVT_UAV,
        ATTR_NEED_ACK,
        CMDSET_VIRTUAL_BUS,
        CMD_VBUS_DEL_MSG,
        "000201",
        "0000",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_HDVT_UAV,
        ATTR_NEED_ACK,
        CMDSET_VIRTUAL_BUS,
        CMD_VBUS_ADD_MSG,
        "02010000059f22626809000200c49ac5c409000200fd7b4c7809000200"
        "ceceb7ee090002009c00a449090002000100",
        "0000",
    ),
    (
        "direct",
        HOST_MOBILE,
        HOST_HDVT_UAV,
        ATTR_NEED_ACK,
        CMDSET_VIRTUAL_BUS,
        CMD_VBUS_ADD_MSG,
        "02010000059f22626809000200c49ac5c409000200fd7b4c7809000200"
        "ceceb7ee090002009c00a449090002000100",
        "0000",
    ),
)


def normalize_appid(value: str) -> str:
    appid = value.strip().lower()
    if len(appid) != 8 or any(character not in "0123456789abcdef" for character in appid):
        raise ValueError("AppID must be exactly 8 hexadecimal characters")
    return appid


CRC8_TABLE: tuple[int, ...] = (
    0x00,
    0x5E,
    0xBC,
    0xE2,
    0x61,
    0x3F,
    0xDD,
    0x83,
    0xC2,
    0x9C,
    0x7E,
    0x20,
    0xA3,
    0xFD,
    0x1F,
    0x41,
    0x9D,
    0xC3,
    0x21,
    0x7F,
    0xFC,
    0xA2,
    0x40,
    0x1E,
    0x5F,
    0x01,
    0xE3,
    0xBD,
    0x3E,
    0x60,
    0x82,
    0xDC,
    0x23,
    0x7D,
    0x9F,
    0xC1,
    0x42,
    0x1C,
    0xFE,
    0xA0,
    0xE1,
    0xBF,
    0x5D,
    0x03,
    0x80,
    0xDE,
    0x3C,
    0x62,
    0xBE,
    0xE0,
    0x02,
    0x5C,
    0xDF,
    0x81,
    0x63,
    0x3D,
    0x7C,
    0x22,
    0xC0,
    0x9E,
    0x1D,
    0x43,
    0xA1,
    0xFF,
    0x46,
    0x18,
    0xFA,
    0xA4,
    0x27,
    0x79,
    0x9B,
    0xC5,
    0x84,
    0xDA,
    0x38,
    0x66,
    0xE5,
    0xBB,
    0x59,
    0x07,
    0xDB,
    0x85,
    0x67,
    0x39,
    0xBA,
    0xE4,
    0x06,
    0x58,
    0x19,
    0x47,
    0xA5,
    0xFB,
    0x78,
    0x26,
    0xC4,
    0x9A,
    0x65,
    0x3B,
    0xD9,
    0x87,
    0x04,
    0x5A,
    0xB8,
    0xE6,
    0xA7,
    0xF9,
    0x1B,
    0x45,
    0xC6,
    0x98,
    0x7A,
    0x24,
    0xF8,
    0xA6,
    0x44,
    0x1A,
    0x99,
    0xC7,
    0x25,
    0x7B,
    0x3A,
    0x64,
    0x86,
    0xD8,
    0x5B,
    0x05,
    0xE7,
    0xB9,
    0x8C,
    0xD2,
    0x30,
    0x6E,
    0xED,
    0xB3,
    0x51,
    0x0F,
    0x4E,
    0x10,
    0xF2,
    0xAC,
    0x2F,
    0x71,
    0x93,
    0xCD,
    0x11,
    0x4F,
    0xAD,
    0xF3,
    0x70,
    0x2E,
    0xCC,
    0x92,
    0xD3,
    0x8D,
    0x6F,
    0x31,
    0xB2,
    0xEC,
    0x0E,
    0x50,
    0xAF,
    0xF1,
    0x13,
    0x4D,
    0xCE,
    0x90,
    0x72,
    0x2C,
    0x6D,
    0x33,
    0xD1,
    0x8F,
    0x0C,
    0x52,
    0xB0,
    0xEE,
    0x32,
    0x6C,
    0x8E,
    0xD0,
    0x53,
    0x0D,
    0xEF,
    0xB1,
    0xF0,
    0xAE,
    0x4C,
    0x12,
    0x91,
    0xCF,
    0x2D,
    0x73,
    0xCA,
    0x94,
    0x76,
    0x28,
    0xAB,
    0xF5,
    0x17,
    0x49,
    0x08,
    0x56,
    0xB4,
    0xEA,
    0x69,
    0x37,
    0xD5,
    0x8B,
    0x57,
    0x09,
    0xEB,
    0xB5,
    0x36,
    0x68,
    0x8A,
    0xD4,
    0x95,
    0xCB,
    0x29,
    0x77,
    0xF4,
    0xAA,
    0x48,
    0x16,
    0xE9,
    0xB7,
    0x55,
    0x0B,
    0x88,
    0xD6,
    0x34,
    0x6A,
    0x2B,
    0x75,
    0x97,
    0xC9,
    0x4A,
    0x14,
    0xF6,
    0xA8,
    0x74,
    0x2A,
    0xC8,
    0x96,
    0x15,
    0x4B,
    0xA9,
    0xF7,
    0xB6,
    0xE8,
    0x0A,
    0x54,
    0xD7,
    0x89,
    0x6B,
    0x35,
)

CRC16_TABLE: tuple[int, ...] = (
    0x0000,
    0x1189,
    0x2312,
    0x329B,
    0x4624,
    0x57AD,
    0x6536,
    0x74BF,
    0x8C48,
    0x9DC1,
    0xAF5A,
    0xBED3,
    0xCA6C,
    0xDBE5,
    0xE97E,
    0xF8F7,
    0x1081,
    0x0108,
    0x3393,
    0x221A,
    0x56A5,
    0x472C,
    0x75B7,
    0x643E,
    0x9CC9,
    0x8D40,
    0xBFDB,
    0xAE52,
    0xDAED,
    0xCB64,
    0xF9FF,
    0xE876,
    0x2102,
    0x308B,
    0x0210,
    0x1399,
    0x6726,
    0x76AF,
    0x4434,
    0x55BD,
    0xAD4A,
    0xBCC3,
    0x8E58,
    0x9FD1,
    0xEB6E,
    0xFAE7,
    0xC87C,
    0xD9F5,
    0x3183,
    0x200A,
    0x1291,
    0x0318,
    0x77A7,
    0x662E,
    0x54B5,
    0x453C,
    0xBDCB,
    0xAC42,
    0x9ED9,
    0x8F50,
    0xFBEF,
    0xEA66,
    0xD8FD,
    0xC974,
    0x4204,
    0x538D,
    0x6116,
    0x709F,
    0x0420,
    0x15A9,
    0x2732,
    0x36BB,
    0xCE4C,
    0xDFC5,
    0xED5E,
    0xFCD7,
    0x8868,
    0x99E1,
    0xAB7A,
    0xBAF3,
    0x5285,
    0x430C,
    0x7197,
    0x601E,
    0x14A1,
    0x0528,
    0x37B3,
    0x263A,
    0xDECD,
    0xCF44,
    0xFDDF,
    0xEC56,
    0x98E9,
    0x8960,
    0xBBFB,
    0xAA72,
    0x6306,
    0x728F,
    0x4014,
    0x519D,
    0x2522,
    0x34AB,
    0x0630,
    0x17B9,
    0xEF4E,
    0xFEC7,
    0xCC5C,
    0xDDD5,
    0xA96A,
    0xB8E3,
    0x8A78,
    0x9BF1,
    0x7387,
    0x620E,
    0x5095,
    0x411C,
    0x35A3,
    0x242A,
    0x16B1,
    0x0738,
    0xFFCF,
    0xEE46,
    0xDCDD,
    0xCD54,
    0xB9EB,
    0xA862,
    0x9AF9,
    0x8B70,
    0x8408,
    0x9581,
    0xA71A,
    0xB693,
    0xC22C,
    0xD3A5,
    0xE13E,
    0xF0B7,
    0x0840,
    0x19C9,
    0x2B52,
    0x3ADB,
    0x4E64,
    0x5FED,
    0x6D76,
    0x7CFF,
    0x9489,
    0x8500,
    0xB79B,
    0xA612,
    0xD2AD,
    0xC324,
    0xF1BF,
    0xE036,
    0x18C1,
    0x0948,
    0x3BD3,
    0x2A5A,
    0x5EE5,
    0x4F6C,
    0x7DF7,
    0x6C7E,
    0xA50A,
    0xB483,
    0x8618,
    0x9791,
    0xE32E,
    0xF2A7,
    0xC03C,
    0xD1B5,
    0x2942,
    0x38CB,
    0x0A50,
    0x1BD9,
    0x6F66,
    0x7EEF,
    0x4C74,
    0x5DFD,
    0xB58B,
    0xA402,
    0x9699,
    0x8710,
    0xF3AF,
    0xE226,
    0xD0BD,
    0xC134,
    0x39C3,
    0x284A,
    0x1AD1,
    0x0B58,
    0x7FE7,
    0x6E6E,
    0x5CF5,
    0x4D7C,
    0xC60C,
    0xD785,
    0xE51E,
    0xF497,
    0x8028,
    0x91A1,
    0xA33A,
    0xB2B3,
    0x4A44,
    0x5BCD,
    0x6956,
    0x78DF,
    0x0C60,
    0x1DE9,
    0x2F72,
    0x3EFB,
    0xD68D,
    0xC704,
    0xF59F,
    0xE416,
    0x90A9,
    0x8120,
    0xB3BB,
    0xA232,
    0x5AC5,
    0x4B4C,
    0x79D7,
    0x685E,
    0x1CE1,
    0x0D68,
    0x3FF3,
    0x2E7A,
    0xE70E,
    0xF687,
    0xC41C,
    0xD595,
    0xA12A,
    0xB0A3,
    0x8238,
    0x93B1,
    0x6B46,
    0x7ACF,
    0x4854,
    0x59DD,
    0x2D62,
    0x3CEB,
    0x0E70,
    0x1FF9,
    0xF78F,
    0xE606,
    0xD49D,
    0xC514,
    0xB1AB,
    0xA022,
    0x92B9,
    0x8330,
    0x7BC7,
    0x6A4E,
    0x58D5,
    0x495C,
    0x3DE3,
    0x2C6A,
    0x1EF1,
    0x0F78,
)


def crc8(data: Sequence[int], init: int = CRC8_INIT) -> int:
    crc = init
    for byte in data:
        crc = CRC8_TABLE[crc ^ byte]
    return crc


def crc16(data: Sequence[int], init: int = CRC16_INIT) -> int:
    crc = init
    for byte in data:
        crc = ((crc >> 8) & 0xFF) ^ CRC16_TABLE[(crc ^ byte) & 0xFF]
    return crc


def xor_header(data: bytes) -> int:
    result = 0
    for byte in data:
        result ^= byte
    return result


def new_session() -> bytes:
    value = secrets.randbelow(0xFFFF) + 1
    return struct.pack("<H", value)


def next_session(session: bytes) -> bytes:
    if len(session) != 2:
        raise ValueError("session must contain exactly two bytes")
    value = (int.from_bytes(session, "little") + 1) & 0xFFFF
    return struct.pack("<H", value or 1)


def new_tick() -> int:
    return secrets.randbelow(0x2000) * 8


def build_duss(
    sender: int,
    receiver: int,
    attr: int,
    cmdset: int,
    cmdid: int,
    payload: bytes,
    sequence: int,
) -> bytes:
    length = 13 + len(payload)
    if not 13 <= length <= 0x3FF:
        raise ValueError(f"DUSS frame length out of range: {length}")
    frame = bytearray(
        [DUSS_MAGIC, length & 0xFF, 0x04 | ((length >> 8) & 0x03), 0, sender, receiver]
    )
    frame += struct.pack("<H", sequence & 0xFFFF)
    frame += bytes((attr, cmdset, cmdid)) + payload + b"\x00\x00"
    frame[3] = crc8(frame[:3])
    frame[-2:] = struct.pack("<H", crc16(frame[:-2]))
    return bytes(frame)


@dataclass(frozen=True)
class DussFrame:
    offset: int
    sender: int
    receiver: int
    sequence: int
    attr: int
    cmdset: int
    cmdid: int
    payload: bytes
    valid: bool


def parse_duss_frames(data: bytes) -> list[DussFrame]:
    frames: list[DussFrame] = []
    offset = 0
    while offset <= len(data) - 13:
        if data[offset] != DUSS_MAGIC or data[offset + 2] & 0xFC != 0x04:
            offset += 1
            continue
        length = data[offset + 1] | ((data[offset + 2] & 0x03) << 8)
        if length < 13 or offset + length > len(data):
            offset += 1
            continue
        raw = data[offset : offset + length]
        frames.append(
            DussFrame(
                offset=offset,
                sender=raw[4],
                receiver=raw[5],
                sequence=int.from_bytes(raw[6:8], "little"),
                attr=raw[8],
                cmdset=raw[9],
                cmdid=raw[10],
                payload=raw[11:-2],
                valid=(
                    crc8(raw[:3]) == raw[3]
                    and crc16(raw[:-2]) == int.from_bytes(raw[-2:], "little")
                ),
            )
        )
        offset += length
    return frames


@dataclass(frozen=True)
class RobotBroadcast:
    robot_ip: str
    robot_mac: str
    appid: str
    pairing: bool


def parse_robot_broadcast(data: bytes) -> RobotBroadcast | None:
    if len(data) != 24:
        return None
    key = 7
    decoded = bytearray()
    for byte in data:
        decoded.append(byte ^ key)
        key = ((key + 7) ^ 178) & 0xFF
    if decoded[:2] != b"\x5a\x5b":
        return None
    appid_bytes = bytes(decoded[16:24])
    appid = (
        "00000000" if appid_bytes == b"\x00" * 8 else appid_bytes.decode("ascii", errors="replace")
    )
    return RobotBroadcast(
        robot_ip=".".join(str(part) for part in decoded[6:10]),
        robot_mac=":".join(f"{part:02X}" for part in decoded[10:16]),
        appid=appid,
        pairing=bool(decoded[2] & 1),
    )


def is_video_packet(data: bytes, session: bytes) -> bool:
    return len(data) > 20 and data[2:4] == session and data[2:4] != b"\x00\x00" and data[6] == 0x02


class AppEnvelope:
    """Maintain the dynamic window and sequence fields of the App outer packet."""

    def __init__(self, session: bytes | None = None, tick: int | None = None) -> None:
        self.session = session or new_session()
        self.tick = new_tick() if tick is None else tick & 0xFFFF
        self.direct_tick = (self.tick + 8) & 0xFFFF
        self.direct_reference = self.tick
        self.latest_direct = self.tick
        self.control_tick = (self.tick + 0xA8) & 0xFFFF
        self.control_reference = self.tick
        self.packet_index = 1

    def preconnect(self) -> bytes:
        packet = bytearray(_PRECONNECT_TEMPLATE)
        packet[2:4] = self.session
        packet[7] = xor_header(packet[:7])
        packet[8:10] = struct.pack("<H", self.tick)
        return bytes(packet)

    def wrap_direct(self, duss: bytes, flags: bytes = b"\x00\x00") -> bytes:
        tick = self.direct_tick
        self.direct_tick = (self.direct_tick + 8) & 0xFFFF
        total = 20 + len(duss)
        packet = bytearray((total & 0xFF, 0x80 | ((total >> 8) & 0x03)))
        packet += self.session + struct.pack("<H", tick) + b"\x05\x00"
        packet[7] = xor_header(packet[:7])
        packet += struct.pack("<H", self.direct_reference)
        packet += struct.pack("<H", tick) + b"\x00" * 4
        packet += bytes((self.packet_index, 0x01)) + flags[:2].ljust(2, b"\x00")
        self.latest_direct = tick
        self.packet_index = (self.packet_index + 1) & 0xFF
        return bytes(packet) + duss

    def wrap_control(self, duss: bytes) -> bytes:
        tick = self.control_tick
        self.control_tick = (self.control_tick + 8) & 0xFFFF
        total = 34 + len(duss)
        packet = bytearray((total & 0xFF, 0x80))
        packet += self.session + b"\x00\x00\x04\x00"
        packet[7] = xor_header(packet[:7])
        packet += struct.pack("<H", tick) * 2 + b"\x00" * 4
        packet += struct.pack("<H", self.control_reference) * 2 + b"\x00" * 4
        packet += struct.pack("<H", self.direct_reference)
        packet += struct.pack("<H", self.latest_direct) + b"\x00" * 4
        packet += struct.pack("<H", len(duss))
        return bytes(packet) + duss

    def observe(self, data: bytes) -> None:
        if len(data) < 12 or data[2:4] != self.session:
            return
        if len(data) >= 28 and data[4:6] == b"\x00\x00":
            direct = int.from_bytes(data[24:26], "little")
            control = int.from_bytes(data[16:18], "little")
            if direct:
                self.direct_reference = direct
            if control:
                self.control_reference = control
        # Stream packets carry the robot's current control window at bytes
        # 10..12. Following it prevents a long-running media session from
        # drifting outside the window used by subsequent control packets.
        if len(data) != 34 and any(
            frame.valid and frame.offset >= 34 for frame in parse_duss_frames(data)
        ):
            control_tick = int.from_bytes(data[10:12], "little")
            if control_tick:
                self.control_tick = control_tick
