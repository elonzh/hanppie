from __future__ import annotations

import struct

import pytest

from hanppie.lab import protocol
from hanppie.lab.app import AppConnectionInfo
from hanppie.lab.direct import (
    DirectRobot,
    build_chassis_control_payload,
    build_gimbal_speed_payload,
    build_led_payload,
)


class FakeDirectConnection:
    def __init__(self, *, return_code: int = 0) -> None:
        self.robot_ip = "192.0.2.10"
        self.connected = True
        self.info = AppConnectionInfo(self.robot_ip, "b6359877", "idle")
        self.return_code = return_code
        self.sequence = 100
        self.controls: list[tuple[bytes, float | None]] = []
        self.periodic: list[tuple[str, object, float | None]] = []
        self.keepalives: list[tuple[bytes, bool]] = []
        self.duss: list[tuple[object, ...]] = []

    def set_control_payload(self, payload: bytes, *, lease_seconds: float | None = None) -> None:
        self.controls.append((payload, lease_seconds))

    def set_periodic_duss(
        self, name: str, command: object, *, lease_seconds: float | None = None
    ) -> None:
        self.periodic.append((name, command, lease_seconds))

    def configure_mode_keepalive(self, payload: bytes, *, send_sdk_ready: bool) -> None:
        self.keepalives.append((payload, send_sdk_ready))

    def send_control(self, payload: bytes = protocol.NEUTRAL_CONTROL) -> int:
        self.controls.append((payload, None))
        self.sequence += 1
        return self.sequence

    def send_duss(
        self,
        sender: int,
        receiver: int,
        attr: int,
        cmdset: int,
        cmdid: int,
        payload: bytes = b"",
        *,
        flags: bytes | None = None,
    ) -> int:
        self.duss.append((sender, receiver, attr, cmdset, cmdid, payload, flags))
        self.sequence += 1
        return self.sequence

    def wait_for_duss(
        self,
        sequence: int,
        *,
        cmdset: int,
        cmdid: int,
        ack: bool,
        timeout: float,
    ) -> protocol.DussFrame:
        del ack, timeout
        return protocol.DussFrame(
            0,
            0x09,
            0x02,
            sequence,
            0xC0,
            cmdset,
            cmdid,
            bytes((self.return_code,)),
            True,
        )

    def close(self) -> None:
        self.connected = False


def make_robot(*, return_code: int = 0) -> tuple[DirectRobot, FakeDirectConnection]:
    robot = DirectRobot(robot_ip="192.0.2.10", appid="b6359877")
    connection = FakeDirectConnection(return_code=return_code)
    robot.connection = connection  # type: ignore[assignment]
    robot.base = connection  # type: ignore[assignment]
    return robot, connection


def test_direct_payload_builders_are_bounded_and_deterministic() -> None:
    neutral = build_chassis_control_payload(0, 0, 0)
    assert len(neutral) == 14
    assert neutral == bytes.fromhex("00042000010800000200040c0004")
    assert build_chassis_control_payload(99, -99, 999) == build_chassis_control_payload(1, -1, 150)

    assert build_gimbal_speed_payload(120, 0) == bytes.fromhex("080500fc0000")
    assert build_gimbal_speed_payload(-999, 999) == bytes.fromhex("0805ff0300fc")

    led = build_led_payload(0x3F, red=300, green=-1, blue=7, effect="flash")
    component, mask, mode, red, green, blue, count, on_ms, off_ms = struct.unpack("<IHBBBBBHH", led)
    assert (component, mask, mode) == (0x3F, 0xFF, 0x73)
    assert (red, green, blue, count, on_ms, off_ms) == (255, 0, 7, 0, 250, 250)
    with pytest.raises(ValueError, match="effect"):
        build_led_payload(1, red=0, green=0, blue=0, effect="rainbow")


def test_direct_robot_requires_arm_and_uses_command_leases(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    robot, connection = make_robot()
    monkeypatch.setattr("hanppie.lab.direct.time.sleep", lambda _seconds: None)

    with pytest.raises(RuntimeError, match="arm"):
        robot.chassis.drive_speed(x=0.1)

    setup_sequences = robot.enter_control_mode()
    assert setup_sequences
    assert robot.control_mode
    assert not robot.armed
    assert connection.keepalives[0] == (bytes.fromhex("0b0300"), False)

    robot.arm()
    robot.chassis.drive_speed(x=0.1, lease_seconds=0.25)
    robot.gimbal.drive_speed(yaw_speed=15, lease_seconds=0.2)
    assert connection.controls[-1][1] == 0.25
    assert connection.periodic[-1][2] == 0.2

    robot.disarm()
    assert not robot.armed
    assert connection.controls[-1] == (protocol.NEUTRAL_CONTROL, None)
    assert connection.periodic[-1][1] is None


def test_direct_request_requires_success_ack() -> None:
    robot, connection = make_robot()
    ack = robot.set_led(red=1, green=2, blue=3)
    assert ack.accepted
    assert connection.duss[-1][3:5] == (0x3F, 0x33)

    rejected, _ = make_robot(return_code=5)
    with pytest.raises(RuntimeError, match="rejected with 5"):
        rejected.play_sound(0x107)


def test_direct_robot_decodes_native_telemetry() -> None:
    robot, _ = make_robot()
    odometry = bytearray(62)
    odometry[10] = 83
    struct.pack_into("<f", odometry, 12, 12.5)
    for index, offset in enumerate(range(26, 62, 4)):
        struct.pack_into("<f", odometry, offset, index + 0.25)
    robot._handle_duss(protocol.DussFrame(0, 9, 2, 7, 0x80, 0x48, 0x08, bytes(odometry), True))

    assert robot.odometry is not None
    assert robot.odometry.battery_percent == 83
    assert robot.odometry.x == pytest.approx(0.25)
    assert robot.odometry.y == pytest.approx(1.25)
    assert robot.odometry.raw_motion_values == pytest.approx((2.25, 3.25, 4.25))

    gimbal = b"\x00\x0a" + struct.pack("<hhhhB", 10, -20, 30, -40, 3)
    robot._handle_duss(protocol.DussFrame(0, 9, 2, 8, 0x80, 0x48, 0x08, gimbal, True))
    assert robot.gimbal_telemetry is not None
    assert robot.gimbal_telemetry.values == (10, -20, 30, -40)
    assert robot.gimbal_telemetry.flag == 3
