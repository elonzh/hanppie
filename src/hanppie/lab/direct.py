"""Direct S1 control over the RoboMaster App-compatible UDP session."""

from __future__ import annotations

import struct
import threading
import time
from dataclasses import dataclass

from hanppie.lab import protocol
from hanppie.lab.app import AppConnection
from hanppie.lab.audio import LabAudio
from hanppie.lab.camera import LabCamera

_PAIR_HASH_1 = b"ba7dc15a96c84f408e436c7bca716ae67b2188f68100b217"
_PAIR_HASH_2 = b"ca01dd0a449f4c8f844008cc9aa9140e56b47e09372be5b2"

# Protocol facts recovered from Windows RoboMaster App traffic. These entries
# continue after APP_CONNECTION_SETUP and deliberately regenerate the active
# session, tick, DUSS sequence and CRC rather than replaying captured packets.
_DIRECT_MODE_SETUP = (
    ("direct", 0x02, 0x09, 0x00, 0x3F, 0x04, b"\x0b\x03\x00", b"\x00\x00"),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x77, b"\x01\x05\x00", b"\x00\x00"),
    ("direct", 0x02, 0x03, 0x40, 0x3F, 0x19, b"\x00", b"\x00\x00"),
    ("direct", 0x02, 0x28, 0x40, 0x00, 0x01, b"", b"\x40\x00"),
    ("direct", 0x02, 0xC3, 0x40, 0x3F, 0x66, b"\x02\x00", b"\x60\x40"),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x66, b"\x02\x00", b"\x40\x00"),
    ("direct", 0x02, 0xA9, 0x40, 0x3F, 0xA3, b"\xa9" + b"\x00" * 34, b"\x40\x00"),
    ("direct", 0x02, 0xA9, 0x40, 0x3F, 0xA3, b"\x09" + b"\x00" * 34, b"\x00\x00"),
    ("direct", 0x02, 0xA9, 0x40, 0x3F, 0xA3, b"\x51" + _PAIR_HASH_1, b"\x00\x00"),
    ("direct", 0x02, 0xA9, 0x40, 0x3F, 0xA3, b"\x91" + _PAIR_HASH_2, b"\x00\x00"),
    ("direct", 0x02, 0x09, 0x40, 0x48, 0x04, bytes.fromhex("00020a"), b"\x00\x00"),
    (
        "direct",
        0x02,
        0x09,
        0x40,
        0x48,
        0x03,
        bytes.fromhex("020a000001973c9bf7090002000a00"),
        b"\x00\x00",
    ),
    ("control", 0, 0, 0, 0, 0, protocol.NEUTRAL_CONTROL, b""),
    ("direct", 0x02, 0xA9, 0x40, 0x3F, 0xA3, b"\x08" + _PAIR_HASH_1, b"\x60\x00"),
    ("direct", 0x02, 0xA9, 0x40, 0x3F, 0xA3, b"\xa8" + _PAIR_HASH_2, b"\x00\x00"),
    ("control", 0, 0, 0, 0, 0, protocol.NEUTRAL_CONTROL, b""),
    ("control", 0, 0, 0, 0, 0, protocol.NEUTRAL_CONTROL, b""),
    ("direct", 0x02, 0x07, 0x40, 0x07, 0x39, b"", b"\x00\x00"),
    ("control", 0, 0, 0, 0, 0, protocol.NEUTRAL_CONTROL, b""),
    ("control", 0, 0, 0, 0, 0, protocol.NEUTRAL_CONTROL, b""),
    ("control", 0, 0, 0, 0, 0, protocol.NEUTRAL_CONTROL, b""),
    ("control", 0, 0, 0, 0, 0, protocol.NEUTRAL_CONTROL, b""),
    ("direct", 0x02, 0x09, 0x00, 0x3F, 0x04, b"\x0b\x03\x00", b"\x00\x00"),
)

_IR_GUN_CONFIG = bytes.fromhex(
    "0d000000e903000000000000ea03000001000000eb03000020bf0200ec030000b0040000"
    "ed03000064000000ef0300000a000000f003000000000000f1030000b80b0000f2030000"
    "dc0500000604000001000000070400000000000008040000010000000904000001000000"
    "05000000dd050000dc050000de050000c4090000df050000b80b0000e0050000b80b0000"
    "4006000000879303050000004d0400003075000001000000de0500004e04000010270000"
    "01000000dd0500004f0400003075000001000000df050000500400001027000001000000"
    "e0050000b0040000000000000100000040060000"
)

_DIRECT_MODE_EFFECT = (
    ("control", 0, 0, 0, 0, 0, protocol.NEUTRAL_CONTROL),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x77, bytes.fromhex("010301")),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x77, bytes.fromhex("010401")),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x77, bytes.fromhex("010201")),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0xB3, bytes.fromhex("05049012516a00000000")),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x77, bytes.fromhex("010401")),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x77, bytes.fromhex("010201")),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x5B, b"\x01"),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x09, _IR_GUN_CONFIG),
    ("direct", 0x02, 0x09, 0x00, 0x3F, 0x04, bytes.fromhex("010301")),
    ("direct", 0x02, 0x07, 0x40, 0x07, 0x17, b""),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x59, b"\x02"),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x09, _IR_GUN_CONFIG),
    ("direct", 0x02, 0x09, 0x00, 0x3F, 0x04, bytes.fromhex("010301")),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x0A, bytes.fromhex("0100")),
    ("direct", 0x02, 0x01, 0x40, 0x02, 0x34, bytes.fromhex("0900006400")),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x59, b"\x02"),
    ("direct", 0x02, 0xF1, 0x40, 0x0A, 0xA3, bytes.fromhex("0000")),
    ("direct", 0x02, 0xF1, 0x40, 0x0A, 0xA3, bytes.fromhex("0000")),
)

_DIRECT_MODE_EXIT = (
    ("control", 0, 0, 0, 0, 0, protocol.NEUTRAL_CONTROL),
    ("direct", 0x02, 0x01, 0x40, 0x02, 0x34, bytes.fromhex("0900006400")),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x59, b"\x00"),
    ("direct", 0x02, 0xF1, 0x40, 0x0A, 0xA3, bytes.fromhex("0000")),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0xB3, bytes.fromhex("06040000000000000000")),
    ("control", 0, 0, 0, 0, 0, protocol.NEUTRAL_CONTROL),
    ("direct", 0x02, 0x09, 0x00, 0x3F, 0x04, bytes.fromhex("000300")),
    ("direct", 0x02, 0x09, 0x40, 0x3F, 0x77, bytes.fromhex("010300")),
    ("direct", 0x02, 0xF1, 0x40, 0x0A, 0xA3, bytes.fromhex("0000")),
)

_TRIGGER_CONTROL = bytes.fromhex("0000042000010840000230")


@dataclass(frozen=True)
class DirectAck:
    sequence: int
    sender: int
    receiver: int
    cmdset: int
    cmdid: int
    payload: bytes

    @property
    def return_code(self) -> int | None:
        return self.payload[0] if self.payload else None

    @property
    def accepted(self) -> bool:
        return self.return_code in (None, 0)


@dataclass(frozen=True)
class DirectOdometry:
    received_at: float
    sequence: int
    battery_percent: int
    heading_like: float
    values: tuple[float, ...]
    payload_hex: str

    @property
    def x(self) -> float | None:
        return self.values[0] if self.values else None

    @property
    def y(self) -> float | None:
        return self.values[1] if len(self.values) > 1 else None

    @property
    def raw_motion_values(self) -> tuple[float, float, float] | None:
        if len(self.values) < 5:
            return None
        return self.values[2], self.values[3], self.values[4]


@dataclass(frozen=True)
class DirectGimbalTelemetry:
    received_at: float
    sequence: int
    values: tuple[int, int, int, int]
    flag: int
    payload_hex: str


def decode_direct_odometry(frame: protocol.DussFrame) -> DirectOdometry | None:
    if frame.cmdset != 0x48 or frame.cmdid != 0x08 or len(frame.payload) != 62:
        return None
    return DirectOdometry(
        time.monotonic(),
        frame.sequence,
        frame.payload[10],
        struct.unpack_from("<f", frame.payload, 12)[0],
        tuple(struct.unpack_from("<f", frame.payload, offset)[0] for offset in range(26, 62, 4)),
        frame.payload.hex(),
    )


def decode_direct_gimbal(frame: protocol.DussFrame) -> DirectGimbalTelemetry | None:
    if (
        frame.cmdset != 0x48
        or frame.cmdid != 0x08
        or len(frame.payload) != 11
        or frame.payload[:2] != b"\x00\x0a"
    ):
        return None
    return DirectGimbalTelemetry(
        time.monotonic(),
        frame.sequence,
        tuple(struct.unpack_from("<hhhh", frame.payload, 2)),
        frame.payload[10],
        frame.payload.hex(),
    )


def build_chassis_control_payload(x: float, y: float, z: float) -> bytes:
    """Encode App control-channel chassis velocity in m/s and degrees/s."""

    x = max(-1.0, min(1.0, float(x)))
    y = max(-1.0, min(1.0, float(y)))
    z = max(-150.0, min(150.0, float(z))) / 150.0
    linear_x = max(0, min(2047, round(1024 + 256 * x)))
    linear_y = max(0, min(2047, round(1024 + 256 * y)))
    angular = max(-1024, min(1023, round(256 * z))) & 0x0FFF
    payload = bytearray(bytes.fromhex("0004200001084000000000000000"))
    payload[0] = linear_y & 0xFF
    payload[1] = ((linear_x << 3) & 0xF8) | ((linear_y >> 8) & 0x07)
    payload[2] = (payload[2] & 0xC0) | ((linear_x >> 5) & 0x3F)
    payload[5] = ((angular << 4) & 0xF0) | 0x08
    payload[6] = (angular >> 4) & 0xFF
    payload[8] = 0x02 | ((angular << 2) & 0xFF)
    payload[9] = (angular >> 6) & 0xFF
    payload[10:14] = bytes.fromhex("040c0004")
    return bytes(payload)


def build_gimbal_speed_payload(pitch: float, yaw: float) -> bytes:
    """Encode App direct-channel gimbal velocity in degrees/s."""

    pitch = max(-120.0, min(120.0, float(pitch))) / 120.0
    yaw = max(-120.0, min(120.0, float(yaw))) / 120.0
    pitch_raw = max(-1024, min(1023, round(-1024 * pitch)))
    yaw_raw = max(-1024, min(1023, round(-1024 * yaw)))
    return bytes.fromhex("0805") + struct.pack("<hh", pitch_raw, yaw_raw)


def build_led_payload(
    component: int,
    *,
    red: int,
    green: int,
    blue: int,
    effect: str,
) -> bytes:
    modes = {"off": 0, "on": 1, "solid": 1, "breath": 2, "flash": 3}
    try:
        mode = modes[effect.lower()]
    except KeyError as exc:
        raise ValueError("effect must be off, on, solid, breath, or flash") from exc
    interval = 250 if mode == 3 else 1000
    return struct.pack(
        "<IHBBBBBHH",
        component & 0xFFFFFFFF,
        0xFF,
        0x70 | mode,
        max(0, min(255, int(red))),
        max(0, min(255, int(green))),
        max(0, min(255, int(blue))),
        0,
        interval,
        interval,
    )


class DirectChassis:
    def __init__(self, robot: DirectRobot) -> None:
        self._robot = robot

    def drive_speed(
        self,
        x: float = 0.0,
        y: float = 0.0,
        z: float = 0.0,
        *,
        lease_seconds: float = 0.25,
    ) -> None:
        self._robot._require_armed()
        self._robot.connection.set_control_payload(
            build_chassis_control_payload(x, y, z),
            lease_seconds=lease_seconds,
        )

    def stop(self) -> None:
        self._robot.connection.set_control_payload(protocol.NEUTRAL_CONTROL)

    def drive_wheels(
        self,
        w1: int = 0,
        w2: int = 0,
        w3: int = 0,
        w4: int = 0,
        *,
        timeout: float = 1.0,
    ) -> DirectAck:
        self._robot._require_armed()
        values = tuple(max(-1000, min(1000, int(value))) for value in (w1, w2, w3, w4))
        return self._robot.request(0x02, 0x03, 0x3F, 0x20, struct.pack("<hhhh", *values), timeout)


class DirectGimbal:
    def __init__(self, robot: DirectRobot) -> None:
        self._robot = robot

    def drive_speed(
        self,
        *,
        pitch_speed: float = 0.0,
        yaw_speed: float = 0.0,
        lease_seconds: float = 0.25,
    ) -> None:
        self._robot._require_armed()
        self._robot.connection.set_periodic_duss(
            "gimbal-speed",
            (0x02, 0x04, 0x00, 0x04, 0x69, build_gimbal_speed_payload(pitch_speed, yaw_speed)),
            lease_seconds=lease_seconds,
        )

    def stop(self) -> None:
        self._robot.connection.set_periodic_duss("gimbal-speed", None)
        if self._robot.connection.connected:
            self._robot.connection.send_duss(
                0x02,
                0x04,
                0x00,
                0x04,
                0x69,
                build_gimbal_speed_payload(0, 0),
            )


class DirectRobot:
    """High-level direct backend that does not upload or execute a Lab DSP."""

    def __init__(
        self,
        *,
        robot_ip: str,
        appid: str,
        local_ip: str = "0.0.0.0",
        debug: bool = False,
    ) -> None:
        self.robot_ip = robot_ip
        self.appid = appid
        self.local_ip = local_ip
        self.debug = debug
        self.connection = AppConnection(robot_ip, appid, local_ip=local_ip, debug=debug)
        # These transports are App-session capabilities and do not depend on Lab.
        self.base = self.connection
        self.camera = LabCamera(self.connection)
        self.audio = LabAudio(self.connection)
        self.chassis = DirectChassis(self)
        self.gimbal = DirectGimbal(self)
        self._control_mode = False
        self._armed = False
        self._telemetry_condition = threading.Condition()
        self._odometry: DirectOdometry | None = None
        self._gimbal: DirectGimbalTelemetry | None = None
        self.connection.on("duss", self._handle_duss)

    @property
    def connected(self) -> bool:
        return self.connection.connected

    @property
    def control_mode(self) -> bool:
        return self._control_mode

    @property
    def armed(self) -> bool:
        return self._armed

    @property
    def info(self):
        return self.connection.info

    @property
    def odometry(self) -> DirectOdometry | None:
        with self._telemetry_condition:
            return self._odometry

    @property
    def gimbal_telemetry(self) -> DirectGimbalTelemetry | None:
        with self._telemetry_condition:
            return self._gimbal

    def initialize(
        self,
        conn_type: str = "sta",
        proto_type: str = "udp",
        sn: str | None = None,
        *,
        timeout: float = 20.0,
    ) -> bool:
        del sn
        if conn_type != "sta" or proto_type != "udp":
            raise ValueError("the S1 direct backend requires conn_type='sta' and proto_type='udp'")
        initialized = self.connection.initialize(timeout=timeout)
        self.robot_ip = self.connection.robot_ip
        return initialized

    def enter_control_mode(self) -> tuple[int, ...]:
        if not self.connected:
            raise RuntimeError("App connection is not initialized")
        if self._control_mode:
            return ()
        self.connection.set_control_payload(protocol.NEUTRAL_CONTROL)
        self.connection.configure_mode_keepalive(bytes.fromhex("0b0300"), send_sdk_ready=False)
        sequences = self._send_setup(_DIRECT_MODE_SETUP, include_flags=True)
        sequences.extend(self._send_setup(_DIRECT_MODE_EFFECT, include_flags=False))
        self._control_mode = True
        self._armed = False
        return tuple(sequences)

    def exit_control_mode(self) -> None:
        if not self._control_mode:
            return
        self.disarm()
        self._send_setup(_DIRECT_MODE_EXIT, include_flags=False)
        self.connection.configure_mode_keepalive(bytes.fromhex("000300"), send_sdk_ready=True)
        self._control_mode = False

    def arm(self) -> None:
        if not self._control_mode:
            raise RuntimeError("enter_control_mode() must complete before arm()")
        self.stop()
        self._armed = True

    def disarm(self) -> None:
        self._armed = False
        self.stop()

    def stop(self) -> None:
        self.chassis.stop()
        self.gimbal.stop()

    def close(self) -> None:
        try:
            self.audio.close()
            self.camera.close()
            if self.connected:
                self.exit_control_mode()
        finally:
            self.connection.close()

    def request(
        self,
        sender: int,
        receiver: int,
        cmdset: int,
        cmdid: int,
        payload: bytes = b"",
        timeout: float = 1.0,
    ) -> DirectAck:
        sequence = self.connection.send_duss(sender, receiver, 0x40, cmdset, cmdid, payload)
        frame = self.connection.wait_for_duss(
            sequence,
            cmdset=cmdset,
            cmdid=cmdid,
            ack=True,
            timeout=timeout,
        )
        if frame is None:
            raise TimeoutError(f"DUSS 0x{cmdset:02x}/0x{cmdid:02x} ACK timed out")
        ack = DirectAck(
            frame.sequence,
            frame.sender,
            frame.receiver,
            frame.cmdset,
            frame.cmdid,
            frame.payload,
        )
        if not ack.accepted:
            raise RuntimeError(f"DUSS 0x{cmdset:02x}/0x{cmdid:02x} rejected with {ack.return_code}")
        return ack

    def set_led(
        self,
        *,
        component: str = "all",
        red: int = 255,
        green: int = 255,
        blue: int = 255,
        effect: str = "on",
        timeout: float = 1.0,
    ) -> DirectAck:
        components = {"all": 0x3F, "top": 0x30, "gimbal": 0x30, "bottom": 0x0F}
        try:
            component_id = components[component.lower()]
        except KeyError as exc:
            raise ValueError("component must be all, top, gimbal, or bottom") from exc
        payload = build_led_payload(
            component_id,
            red=red,
            green=green,
            blue=blue,
            effect=effect,
        )
        return self.request(0x02, 0x09, 0x3F, 0x33, payload, timeout)

    def set_muzzle_led(
        self,
        *,
        fire: bool,
        enabled: bool,
        timeout: float = 1.0,
    ) -> DirectAck:
        mode = 0 if fire else 7
        payload = struct.pack(
            "<IHBBBBBHH",
            1 << 6,
            0xFF,
            (mode << 4) | int(enabled),
            255,
            255,
            255,
            100,
            1,
            1,
        )
        return self.request(0x02, 0x09, 0x3F, 0x33, payload, timeout)

    def play_sound(self, sound_id: int, *, timeout: float = 1.0) -> DirectAck:
        control = 2 if 0x107 <= int(sound_id) <= 0x12A else 1
        payload = struct.pack("<IBHB", int(sound_id), control, 5000, 1)
        return self.request(0x02, 0x09, 0x3F, 0x1A, payload, timeout)

    def capture(self, *, timeout: float = 1.0) -> DirectAck:
        return self.request(0x02, 0x01, 0x02, 0x01, b"\x01", timeout)

    def fire_infrared(self, *, lease_seconds: float = 0.12) -> None:
        self._require_armed()
        self.connection.set_control_payload(_TRIGGER_CONTROL, lease_seconds=lease_seconds)

    def wait_for_odometry(
        self,
        *,
        after: float = 0.0,
        timeout: float = 2.0,
    ) -> DirectOdometry | None:
        deadline = time.monotonic() + max(0.0, timeout)
        with self._telemetry_condition:
            while True:
                if self._odometry is not None and self._odometry.received_at > after:
                    return self._odometry
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self._telemetry_condition.wait(remaining)

    def wait_for_gimbal(
        self,
        *,
        after: float = 0.0,
        timeout: float = 2.0,
    ) -> DirectGimbalTelemetry | None:
        deadline = time.monotonic() + max(0.0, timeout)
        with self._telemetry_condition:
            while True:
                if self._gimbal is not None and self._gimbal.received_at > after:
                    return self._gimbal
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self._telemetry_condition.wait(remaining)

    def _send_setup(
        self,
        entries: tuple[tuple[object, ...], ...],
        *,
        include_flags: bool,
    ) -> list[int]:
        sequences: list[int] = []
        for entry in entries:
            kind, sender, receiver, attr, cmdset, cmdid, payload, *rest = entry
            if kind == "control":
                sequences.append(self.connection.send_control(payload))
                time.sleep(0.02)
                continue
            flags = rest[0] if include_flags and rest else None
            sequences.append(
                self.connection.send_duss(
                    sender,
                    receiver,
                    attr,
                    cmdset,
                    cmdid,
                    payload,
                    flags=flags,
                )
            )
            time.sleep(0.006)
        return sequences

    def _require_armed(self) -> None:
        if not self._armed:
            raise RuntimeError("direct mechanical control requires arm()")

    def _handle_duss(self, value: object) -> None:
        if not isinstance(value, protocol.DussFrame):
            return
        telemetry = decode_direct_odometry(value) or decode_direct_gimbal(value)
        if telemetry is not None:
            with self._telemetry_condition:
                if isinstance(telemetry, DirectOdometry):
                    self._odometry = telemetry
                else:
                    self._gimbal = telemetry
                self._telemetry_condition.notify_all()
