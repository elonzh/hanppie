"""Binary protocol primitives for the RoboMaster App-compatible transport.

The outer envelope and setup sequence are interoperability facts recovered from
the currently verified S1/App traffic. DUSS CRC calculation reuses the runtime
recovered from the robot instead of maintaining a second copy of its lookup tables.
"""

from __future__ import annotations

import secrets
import struct
from dataclasses import dataclass

from hanppie.runtime.duml_crc import duss_util_crc8_calc, duss_util_crc16_calc

APP_PORT = 45678
ROBOT_APP_PORT = 56789
LOCAL_CONTROL_PORT = 10609
ROBOT_CONTROL_PORT = 10607
INITIAL_DUSS_SEQUENCE = 10072
NEUTRAL_CONTROL = bytes.fromhex("0000042000010840000210")

_PRECONNECT_TEMPLATE = bytes.fromhex(
    "3080dc6800000004d84664006400c005140000640064006400c005140000"
    "640014006400c00514000064000101040102"
)

# The minimal App connection setup observed before entering Lab mode. Entries
# are (envelope, sender, receiver, attr, cmdset, cmdid, payload_hex, flags_hex).
APP_CONNECTION_SETUP = (
    ("direct", 0x02, 0x28, 0x40, 0x00, 0x01, "", "0000"),
    ("direct", 0x02, 0x28, 0x40, 0x3F, 0xFE, "00", "0000"),
    ("direct", 0x02, 0x28, 0x40, 0x00, 0x4F, "0100000000ffffffff", "4036"),
    ("direct", 0x02, 0x28, 0x40, 0x00, 0x4F, "01d4030000ffffffff", "0000"),
    ("direct", 0x02, 0x07, 0x40, 0x07, 0x30, "4a5000004a5000000100", "0000"),
    ("direct", 0x02, 0x28, 0x40, 0x00, 0x4F, "01a8070000ffffffff", "0000"),
    ("direct", 0x02, 0x28, 0x40, 0x00, 0x4F, "017c0b0000ffffffff", "0000"),
    ("control", 0x02, 0x09, 0x00, 0x01, 0x04, NEUTRAL_CONTROL.hex(), "c8ec"),
    ("direct", 0x02, 0x28, 0x40, 0x00, 0x4F, "01500f0000ffffffff", "0000"),
    ("direct", 0x02, 0x28, 0x40, 0x00, 0x4F, "0124130000ffffffff", "6000"),
    ("direct", 0x02, 0x28, 0x40, 0x00, 0x4F, "01f8160000ffffffff", "0000"),
    ("direct", 0x02, 0x28, 0x40, 0x00, 0x4F, "01cc1a0000ffffffff", "0000"),
    ("control", 0x02, 0x09, 0x00, 0x01, 0x04, NEUTRAL_CONTROL.hex(), "f0ec"),
    ("control", 0x02, 0x09, 0x00, 0x01, 0x04, NEUTRAL_CONTROL.hex(), "f0ec"),
    ("control", 0x02, 0x09, 0x00, 0x01, 0x04, NEUTRAL_CONTROL.hex(), "f0ec"),
    ("direct", 0x02, 0x09, 0x40, 0x48, 0x01, "0200000003", "0000"),
    ("direct", 0x02, 0x09, 0x40, 0x48, 0x04, "000201", "0000"),
    (
        "direct",
        0x02,
        0x09,
        0x40,
        0x48,
        0x03,
        "02010000059f22626809000200c49ac5c409000200fd7b4c7809000200"
        "ceceb7ee090002009c00a449090002000100",
        "0000",
    ),
    (
        "direct",
        0x02,
        0x09,
        0x40,
        0x48,
        0x03,
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


def crc8(data: bytes) -> int:
    return int(duss_util_crc8_calc(data, 0x77))


def crc16(data: bytes) -> int:
    return int(duss_util_crc16_calc(data, 0x3692))


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
    frame = bytearray([0x55, length & 0xFF, 0x04 | ((length >> 8) & 0x03), 0, sender, receiver])
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
        if data[offset] != 0x55 or data[offset + 2] & 0xFC != 0x04:
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
