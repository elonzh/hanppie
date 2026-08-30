from __future__ import annotations

import json
from collections.abc import Callable

import pytest

import hanppie.lab.robot as robot_module
from hanppie.lab.app import AppConnection, AppConnectionInfo
from hanppie.lab.bridge import LabBridge, LabTelemetry
from hanppie.lab.camera import LabCamera
from hanppie.lab.config import LabConfig
from hanppie.lab.program import LabProgramIdentity
from hanppie.lab.protocol import build_duss
from hanppie.lab.robot import LabRobot


class FakeSocket:
    def __init__(self, incoming: list[tuple[bytes, tuple[str, int]]] | None = None) -> None:
        self.incoming = list(incoming or [])
        self.sent: list[tuple[bytes, tuple[str, int]]] = []
        self.closed = False

    def sendto(self, payload: bytes, target: tuple[str, int]) -> None:
        self.sent.append((payload, target))

    def setsockopt(self, *_args: object) -> None:
        return None

    def bind(self, target: tuple[str, int]) -> None:
        self.bound = target

    def settimeout(self, timeout: float) -> None:
        self.timeout = timeout

    def recvfrom(self, _size: int) -> tuple[bytes, tuple[str, int]]:
        if not self.incoming:
            raise OSError("closed")
        return self.incoming.pop(0)

    def close(self) -> None:
        self.closed = True


class FakeConnection:
    def __init__(self) -> None:
        self.robot_ip = "192.0.2.10"
        self.connected = True
        self.info = AppConnectionInfo(self.robot_ip, "b6359877", "idle")
        self.suspend_idle_keepalive = False
        self.calls: list[tuple[object, ...] | str] = []

    def initialize(self, *, timeout: float) -> bool:
        self.calls.append(("initialize", timeout))
        return True

    def close(self) -> None:
        self.calls.append("close")
        self.connected = False

    def send_duss(
        self,
        sender: int,
        receiver: int,
        attr: int,
        cmdset: int,
        cmdid: int,
        payload: bytes = b"",
    ) -> int:
        self.calls.append(("duss", sender, receiver, attr, cmdset, cmdid, payload.hex()))
        return len(self.calls)

    def send_control(self) -> int:
        self.calls.append("control")
        return len(self.calls)

    def get_battery(self) -> int:
        return 77


class FakeBridge:
    def __init__(self, ready: bool = True, acknowledge_arm: bool = True) -> None:
        self.robot_ip = ""
        self.ready = ready
        self.acknowledge_arm = acknowledge_arm
        self.calls: list[object] = []
        self.worker_threads = {"rx": None, "motion": None}
        self.command_sequence = 0
        self.last_telemetry: LabTelemetry | None = None

    def on_telemetry(self, _callback: Callable[[LabTelemetry], None]) -> None:
        self.calls.append("callback")

    def start(self) -> None:
        self.calls.append("start")
        self.worker_threads["rx"] = 1

    def close(self) -> None:
        self.calls.append("close")
        self.worker_threads = {"rx": None, "motion": None}

    def prime(self, *, count: int, interval: float) -> None:
        self.calls.append(("prime", count, interval))

    def call(self, module: str, method: str, **params: object) -> bool:
        self.calls.append(("call", module, method, params))
        return True

    def wait_for_telemetry(self, _timeout: float) -> bool:
        self.calls.append("wait")
        return self.ready

    def arm(self) -> bool:
        self.calls.append("arm")
        self.command_sequence += 1
        return True

    def disarm(self) -> bool:
        self.calls.append("disarm")
        return True

    def stop_robot(self) -> bool:
        self.calls.append("stop")
        self.command_sequence += 1
        if self.acknowledge_arm:
            self.last_telemetry = LabTelemetry(
                self.command_sequence,
                0,
                {"rx_command_seq": self.command_sequence, "armed": True},
            )
        return True

    def drive_speed(self, x: float, y: float, z: float) -> bool:
        self.calls.append(("drive", x, y, z))
        return True

    def gimbal_drive_speed(self, pitch: float, yaw: float) -> bool:
        self.calls.append(("gimbal", pitch, yaw))
        return True

    def set_led(self, **values: object) -> bool:
        self.calls.append(("led", values))
        return True

    def fire(self, fire_type: str) -> bool:
        self.calls.append(("fire", fire_type))
        return True


def zero_delay_config(**values: object) -> LabConfig:
    defaults: dict[str, object] = {
        "telemetry_period": 0.001,
        "command_timeout": 0,
        "bridge_ready_timeout": 0,
        "bridge_probe_interval": 0,
        "lab_mode_settle": 0,
        "upload_settle": 0,
        "program_start_settle": 0,
        "upload_retry_timeout": 0,
    }
    defaults.update(values)
    return LabConfig(**defaults)  # type: ignore[arg-type]


def make_robot(config: LabConfig | None = None) -> tuple[LabRobot, FakeConnection]:
    robot = LabRobot(
        robot_ip="192.0.2.10",
        appid="b6359877",
        config=config or zero_delay_config(),
    )
    connection = FakeConnection()
    robot.connection = connection  # type: ignore[assignment]
    robot.base = connection  # type: ignore[assignment]
    return robot, connection


def test_app_connection_builds_setup_and_dispatches_video_and_battery(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = AppConnection("192.0.2.10", "b6359877")
    connection.socket = FakeSocket()  # type: ignore[assignment]
    monkeypatch.setattr("hanppie.lab.app.time.sleep", lambda _seconds: None)

    connection._send_connection_setup()
    assert len(connection.socket.sent) == 19  # type: ignore[union-attr]
    assert connection._sequence == 10091

    videos: list[object] = []
    connection.on("video", videos.append)
    video = bytearray(24)
    video[2:4] = connection.envelope.session
    video[6] = 2
    video[20:] = b"h264"
    connection._handle_packet(bytes(video))
    assert videos == [b"h264"]

    payload = bytearray(62)
    payload[10] = 76
    frame = build_duss(0x09, 0x02, 0x80, 0x48, 0x08, bytes(payload), 1)
    battery_events: list[object] = []
    connection.on("duss", battery_events.append)
    outer = bytearray(34) + bytearray(frame)
    outer[2:4] = connection.envelope.session
    outer[32:34] = len(frame).to_bytes(2, "little")
    connection._handle_packet(bytes(outer))
    assert connection.get_battery() == 76
    assert len(battery_events) == 1


def test_app_connection_callbacks_initialize_and_close(monkeypatch: pytest.MonkeyPatch) -> None:
    connection = AppConnection("192.0.2.10", "b6359877")
    calls: list[object] = []
    control_socket = FakeSocket()

    class Thread:
        def __init__(self, **_kwargs: object) -> None:
            self.joined = False

        def start(self) -> None:
            calls.append("thread-start")

        def join(self, *, timeout: float) -> None:
            self.joined = timeout == 1
            calls.append("thread-join")

    callback = calls.append
    assert connection.on("event", callback) is callback
    connection._emit("event", 3)
    assert calls == [3]
    assert connection.off("event", callback)
    assert not connection.off("event", callback)
    connection.on("event", callback)
    assert connection.off("event")

    monkeypatch.setattr(
        connection, "_claim_appid", lambda timeout: calls.append(("claim", timeout))
    )
    monkeypatch.setattr(
        connection, "_open_outer_session", lambda timeout: calls.append(("outer", timeout))
    )
    monkeypatch.setattr(connection, "_send_connection_setup", lambda: calls.append("setup"))
    monkeypatch.setattr("hanppie.lab.app.open_udp", lambda *_args, **_kwargs: control_socket)
    monkeypatch.setattr("hanppie.lab.app.threading.Thread", Thread)
    monkeypatch.setattr("hanppie.lab.app.time.sleep", lambda _seconds: None)

    assert connection.initialize(timeout=2)
    assert connection.initialize(timeout=2)
    assert connection.connected
    connection.close()
    assert not connection.connected
    assert control_socket.closed
    with pytest.raises(RuntimeError, match="not initialized"):
        connection._send(b"packet")


def test_bridge_serializes_commands_and_filters_telemetry_source_and_session() -> None:
    bridge = LabBridge("192.0.2.10")
    sender = FakeSocket()
    bridge._tx_socket = sender  # type: ignore[assignment]

    assert bridge.drive_speed(1, 2, 3)
    first = json.loads(sender.sent[-1][0])
    assert first["session_id"] == bridge.session_id
    assert first["command_seq"] == 1
    assert (first["x"], first["y"], first["z"]) == (1.0, 2.0, 3.0)
    assert bridge.stop_robot()
    assert json.loads(sender.sent[-1][0])["stop"] is True

    valid = json.dumps(
        {
            "type": "telemetry",
            "session_id": bridge.session_id,
            "sequence": 4,
            "time_ms": 123,
            "yaw": 12.5,
        }
    ).encode()
    bridge._rx_socket = FakeSocket(
        [
            (valid, ("198.51.100.1", 40924)),
            (b"not-json", ("192.0.2.10", 40924)),
            (b'{"session_id":0}', ("192.0.2.10", 40924)),
            (valid, ("192.0.2.10", 40924)),
        ]
    )  # type: ignore[assignment]
    received: list[LabTelemetry] = []
    bridge.on_telemetry(received.append)
    bridge._receive_loop()
    assert len(received) == 1
    assert received[0].sequence == 4
    assert received[0].values["yaw"] == 12.5
    assert bridge.wait_for_telemetry(0)


def test_bridge_start_common_commands_and_close(monkeypatch: pytest.MonkeyPatch) -> None:
    sockets = [FakeSocket(), FakeSocket()]

    class Thread:
        next_id = 10

        def __init__(self, **_kwargs: object) -> None:
            self.native_id = self.next_id
            Thread.next_id += 1
            self.started = False
            self.joined = False

        def start(self) -> None:
            self.started = True

        def join(self, *, timeout: float) -> None:
            self.joined = timeout == 1

    monkeypatch.setattr("hanppie.lab.bridge.socket.socket", lambda *_args: sockets.pop(0))
    monkeypatch.setattr("hanppie.lab.bridge.threading.Thread", Thread)
    bridge = LabBridge("192.0.2.10")

    assert not bridge.send(stop=True)
    bridge.start()
    bridge.start()
    assert bridge.worker_threads == {"rx": 10, "motion": 11}
    assert bridge.arm()
    assert bridge.disarm()
    assert bridge.gimbal_drive_speed(2, 3)
    assert bridge.call("gimbal", "recenter")
    assert bridge._motion is None
    assert bridge.drive_speed(1, 2, 3)
    assert bridge.gimbal_drive_speed(4, 5)
    assert bridge.call("gimbal", "stop")
    assert bridge._motion == {"x": 1.0, "y": 2.0, "z": 3.0}
    assert bridge.call("chassis", "stop")
    assert bridge._motion is None
    assert bridge.set_led(red=1, green=2, blue=3)
    assert bridge.fire("infrared")
    bridge._command_sequence = 0x7FFFFFFF
    assert bridge._next_sequence() == 1
    bridge.prime(count=1, interval=0)
    bridge.close()
    assert bridge.worker_threads == {"rx": None, "motion": None}


def test_camera_emits_app_stream_commands_and_queue_strategy(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[object, ...]] = []

    class Connection:
        def on(self, _event: str, callback: Callable[[object], None]) -> None:
            self.callback = callback

        def send_duss(self, *args: object) -> None:
            calls.append(args)

    class Thread:
        def __init__(self, **_kwargs: object) -> None:
            self.joined = False

        def start(self) -> None:
            return None

        def is_alive(self) -> bool:
            return True

        def join(self, *, timeout: float) -> None:
            self.joined = timeout == 1

    monkeypatch.setattr("hanppie.lab.camera.threading.Thread", Thread)
    camera = LabCamera(Connection())  # type: ignore[arg-type]

    camera._accept_chunk("not-bytes")
    camera._accept_chunk(b"h264")
    assert camera._chunks.get_nowait() == b"h264"

    assert camera.start_video_stream(resolution="720p")
    assert calls[0][3:5] == (0x02, 0x18)
    assert calls[1][-1] == b"\x01\x01\x00"
    assert calls[2][-1] == b"\x02\x01\x00"
    camera._put_latest(camera._frames, "old")
    camera._put_latest(camera._frames, "new")
    assert camera.read_video_frame(timeout=0, strategy="newest") == "new"
    with pytest.raises(ValueError, match="strategy"):
        camera.read_video_frame(strategy="invalid")
    with pytest.raises(ValueError, match="resolution"):
        camera.start_video_stream(resolution="4k")
    assert camera.stop_video_stream()
    camera.close()
    assert camera.read_video_frame(timeout=0) is None


def test_lab_robot_lifecycle_and_program_registration(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    robot, connection = make_robot()
    events: list[str] = []
    monkeypatch.setattr(robot_module.time, "sleep", lambda _seconds: None)
    monkeypatch.setattr(robot, "_start_keepalive", lambda: events.append("keepalive-start"))
    monkeypatch.setattr(robot, "_stop_keepalive", lambda: events.append("keepalive-stop"))

    assert robot.initialize()
    robot.enter_lab()
    commands = [call for call in connection.calls if isinstance(call, tuple) and call[0] == "duss"]
    assert [(call[4], call[5], call[6]) for call in commands[-3:]] == [
        (0x3F, 0x04, "020302"),
        (
            0x3F,
            0x09,
            "05000000ea03000000000000ef0300000a000000f003000000000000"
            "f1030000b80b0000f2030000dc0500000000000000000000",
        ),
        (0x3F, 0x57, ""),
    ]

    identity = LabProgramIdentity("1" * 32, "2" * 16)
    monkeypatch.setattr(robot_module, "upload_lab_program", lambda _ip, _dsp: "3" * 32)
    assert robot.upload_prepared_program("abc", identity) == "3" * 32
    robot.start_lab_program()
    assert robot._program_started
    assert robot._program_registered
    robot.stop_lab_program()
    assert not robot._program_started
    assert not robot._program_registered
    assert events.count("keepalive-stop") >= 2


def test_lab_bridge_readiness_and_telemetry_mapping() -> None:
    robot, _connection = make_robot()
    bridge = FakeBridge()
    robot.bridge = bridge  # type: ignore[assignment]
    robot._program_started = True

    robot.start_lab_bridge()
    assert bridge.calls[:6] == [
        "start",
        ("prime", 1, 0),
        (
            "call",
            "system",
            "set_telemetry",
            {"fields": ["x", "y", "yaw", "gimbal_yaw", "gimbal_pitch"]},
        ),
        "wait",
        "arm",
        "stop",
    ]

    attitudes: list[object] = []
    positions: list[object] = []
    robot.on("attitude", attitudes.append)
    robot.on("position", positions.append)
    robot._handle_telemetry(
        LabTelemetry(
            1,
            2,
            {"yaw": 10.0, "x": 1.0, "y": 2.0, "gimbal_pitch": -3.0},
        )
    )
    assert attitudes == [(10.0, None, None)]
    assert positions == [(1.0, 2.0, 10.0)]

    assert robot.chassis.drive_speed(1, 2, 3)
    assert robot.chassis.stop()
    assert robot.gimbal.drive_speed(pitch_speed=4, yaw_speed=5)
    assert robot.gimbal.stop()
    assert robot.gimbal.recenter()
    assert robot.call("media", "capture")
    assert robot.set_led(red=1, green=2, blue=3)
    assert robot.fire()


def test_chassis_subscription_and_exit_lab(monkeypatch: pytest.MonkeyPatch) -> None:
    robot, connection = make_robot()
    bridge = FakeBridge()
    robot.bridge = bridge  # type: ignore[assignment]
    samples: list[object] = []

    assert not robot.chassis.sub_attitude(callback=None)
    with pytest.raises(ValueError, match="freq"):
        robot.chassis.sub_attitude(freq=2, callback=samples.append)
    assert robot.chassis.sub_attitude(freq=5, callback=samples.append)
    robot._emit("attitude", (12.0, None, None))
    assert samples == [(12.0, None, None)]
    assert robot.chassis.unsub_attitude()
    assert not robot.chassis.unsub_attitude()

    robot._lab_entered = True
    monkeypatch.setattr(robot, "_stop_keepalive", lambda: None)
    monkeypatch.setattr(robot_module.time, "sleep", lambda _seconds: None)
    robot.exit_lab()
    assert not connection.suspend_idle_keepalive
    assert connection.calls[-1] == "control"


def test_stop_bridge_disarms_and_replaces_transport(monkeypatch: pytest.MonkeyPatch) -> None:
    robot, _connection = make_robot()
    old_bridge = FakeBridge()
    old_bridge.worker_threads["rx"] = 1
    new_bridge = FakeBridge()
    robot.bridge = old_bridge  # type: ignore[assignment]
    monkeypatch.setattr(robot, "_new_bridge", lambda: new_bridge)

    robot.stop_lab_bridge()
    assert old_bridge.calls == ["disarm", "close"]
    assert robot.bridge is new_bridge


def test_lab_bridge_timeout_closes_transport() -> None:
    robot, _connection = make_robot()
    bridge = FakeBridge(ready=False)
    robot.bridge = bridge  # type: ignore[assignment]
    robot._program_started = True

    with pytest.raises(TimeoutError, match="matching-session"):
        robot.start_lab_bridge()
    assert bridge.calls[-1] == "close"


def test_lab_bridge_requires_arm_and_neutral_confirmation() -> None:
    robot, _connection = make_robot()
    bridge = FakeBridge(acknowledge_arm=False)
    robot.bridge = bridge  # type: ignore[assignment]
    robot._program_started = True

    with pytest.raises(TimeoutError, match="confirm arm"):
        robot.start_lab_bridge()
    assert bridge.calls[-1] == "close"


def test_lab_bridge_retries_probe_until_program_socket_is_ready() -> None:
    robot, _connection = make_robot(
        zero_delay_config(bridge_ready_timeout=1, bridge_probe_interval=0.1)
    )
    bridge = FakeBridge(ready=False)
    attempts = 0

    def eventually_ready(_timeout: float) -> bool:
        nonlocal attempts
        attempts += 1
        return attempts == 3

    bridge.wait_for_telemetry = eventually_ready  # type: ignore[method-assign]
    robot.bridge = bridge  # type: ignore[assignment]
    robot._program_started = True

    robot.start_lab_bridge()

    assert attempts == 3
    assert bridge.calls.count(("prime", 1, 0)) == 3
    assert bridge.calls[-2:] == ["arm", "stop"]


def test_lab_lifecycle_rejects_out_of_order_operations() -> None:
    robot, _connection = make_robot()
    identity = LabProgramIdentity("1" * 32, "2" * 16)

    with pytest.raises(RuntimeError, match="enter_lab"):
        robot.upload_prepared_program("dsp", identity)
    with pytest.raises(RuntimeError, match="enter_lab"):
        robot.start_lab_program("3" * 32)
    with pytest.raises(RuntimeError, match="start_lab_program"):
        robot.start_lab_bridge()
