from __future__ import annotations

import json
import shlex
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest
from rich.console import Console

from hanppie.diagnosis.checks import DiagnosisChecks
from hanppie.diagnosis.discovery import discover_robots
from hanppie.diagnosis.firmware import STOCK_HASHES, classify_runtime
from hanppie.diagnosis.model import (
    DEFAULT_CHECKS,
    DiagnosisConfig,
    DiagnosisResult,
    normalize_check_names,
    validate_safety,
)
from hanppie.diagnosis.recorder import DiagnosisRecorder
from hanppie.diagnosis.runner import DiagnosisRunner, run_diagnosis
from hanppie.diagnosis.session import DeviceSession
from hanppie.lab.app import AppConnectionInfo
from hanppie.lab.bridge import LabTelemetry
from hanppie.lab.protocol import RobotBroadcast


class FakeDiscoverySocket:
    def __init__(self, packets: list[tuple[bytes, tuple[str, int]]]) -> None:
        self.packets = packets
        self.closed = False

    def recvfrom(self, _size: int):
        return self.packets.pop(0)

    def close(self) -> None:
        self.closed = True


def encode_broadcast() -> bytes:
    decoded = bytearray(24)
    decoded[:2] = b"\x5a\x5b"
    decoded[2] = 1
    decoded[6:10] = bytes([192, 0, 2, 10])
    decoded[10:16] = bytes.fromhex("001122334455")
    decoded[16:24] = b"b6359877"
    key = 7
    encoded = bytearray()
    for byte in decoded:
        encoded.append(byte ^ key)
        key = ((key + 7) ^ 178) & 0xFF
    return bytes(encoded)


def test_normalize_names_and_safety(tmp_path: Path) -> None:
    assert normalize_check_names(["video,app", "video"]) == ("app", "video")
    assert normalize_check_names([], all_checks=True)[-1] == "system"
    with pytest.raises(ValueError, match="unknown"):
        normalize_check_names(["unknown"])

    with pytest.raises(ValueError, match="allow-motion"):
        validate_safety(DiagnosisConfig(("chassis",), tmp_path))
    with pytest.raises(ValueError, match="allow-infrared"):
        validate_safety(DiagnosisConfig(("infrared",), tmp_path))
    with pytest.raises(ValueError, match="allow-gel"):
        validate_safety(DiagnosisConfig(("gel",), tmp_path))
    validate_safety(
        DiagnosisConfig(
            ("chassis", "gimbal", "infrared", "gel"),
            tmp_path,
            allow_motion=True,
            allow_infrared=True,
            allow_gel=True,
        )
    )


def test_discover_robots_decodes_and_deduplicates(monkeypatch) -> None:
    sock = FakeDiscoverySocket(
        [
            (encode_broadcast(), ("192.0.2.10", 45678)),
            (encode_broadcast(), ("192.0.2.10", 45678)),
        ]
    )

    def selectable(readers, _writers, _errors, _timeout):
        return (readers if sock.packets else [], [], [])

    ticks = iter([0.0, 0.0, 0.01, 0.02, 1.0])
    monkeypatch.setattr("hanppie.diagnosis.discovery.time.monotonic", lambda: next(ticks, 1.0))
    robots = discover_robots(
        0.1,
        socket_factory=lambda *_args, **_kwargs: sock,  # type: ignore[arg-type]
        select_fn=selectable,
    )

    assert len(robots) == 1
    assert robots[0].robot_ip == "192.0.2.10"
    assert robots[0].appid == "b6359877"
    assert sock.closed


def test_recorder_keeps_complete_local_evidence(tmp_path: Path) -> None:
    recorder = DiagnosisRecorder(tmp_path, now=datetime(2026, 8, 30, tzinfo=timezone.utc))
    recorder.event(
        "info",
        "sample",
        "robot 192.0.2.10 host 192.168.1.8 mac AA:BB:CC:DD:EE:FF",
        identity="secret-id",
    )
    config = DiagnosisConfig(("app",), tmp_path, robot_ip="192.0.2.10", appid="secret-id")
    recorder.write_report(
        config,
        [DiagnosisResult("app", "App 会话", "PASS", "192.0.2.10", 0.1, {"id": "secret-id"})],
    )

    log = recorder.log_path.read_text(encoding="utf-8")
    report = recorder.report_path.read_text(encoding="utf-8")
    assert "192.0.2.10" in log + report
    assert "192.168.1.8" in log
    assert "AA:BB:CC:DD:EE:FF" in log
    assert "secret-id" in log + report
    assert "docs/architecture.md" in report
    json.loads(log)


def test_firmware_classification() -> None:
    assert classify_runtime(dict(STOCK_HASHES)) == "stock"
    assert classify_runtime({}) == "unknown"


class FakeFrame:
    width = 1280
    height = 720
    pts = 42
    format = SimpleNamespace(name="yuv420p")


class FakeCamera:
    def __init__(self) -> None:
        self.audio_streaming = False

    def start_video_stream(self, **_kwargs) -> bool:
        return True

    def read_video_frame(self, **_kwargs):
        return FakeFrame()

    def stop_video_stream(self) -> bool:
        return True

    def start_audio_stream(self) -> bool:
        self.audio_streaming = True
        return True

    def read_audio_frame(self, **_kwargs):
        return (1000).to_bytes(2, "little", signed=True) * 480

    def stop_audio_stream(self) -> bool:
        self.audio_streaming = False
        return True

    def close(self) -> None:
        return None


class FakeAudio:
    def play_pcm(self, pcm: bytes) -> int:
        assert pcm
        return 2


class FakeDirectChassis:
    def __init__(self, robot) -> None:
        self.robot = robot

    def drive_speed(
        self,
        x: float = 0,
        y: float = 0,
        z: float = 0,
        *,
        lease_seconds: float,
    ) -> None:
        assert self.robot.armed
        assert lease_seconds > 0
        self.robot.x += x * lease_seconds
        self.robot.y += y * lease_seconds
        self.robot.heading += z * lease_seconds

    def stop(self) -> None:
        return None


class FakeDirectGimbal:
    def __init__(self, robot) -> None:
        self.robot = robot

    def drive_speed(
        self,
        *,
        pitch_speed: float = 0,
        yaw_speed: float = 0,
        lease_seconds: float,
    ) -> None:
        assert self.robot.armed
        values = list(self.robot.gimbal_values)
        values[0] += round(pitch_speed * lease_seconds * 10)
        values[1] += round(yaw_speed * lease_seconds * 10)
        self.robot.gimbal_values = tuple(values)

    def stop(self) -> None:
        return None


class FakeDirectRobot:
    instances: list[FakeDirectRobot] = []

    def __init__(self, *, robot_ip: str, appid: str, **_kwargs) -> None:
        self.robot_ip = robot_ip
        self.appid = appid
        self.connected = False
        self.control_mode = False
        self.armed = False
        self.info = AppConnectionInfo(robot_ip, appid, "idle", "00:11:22:33:44:55")
        self.base = SimpleNamespace(get_battery=lambda: 77)
        self.camera = FakeCamera()
        self.audio = FakeAudio()
        self.chassis = FakeDirectChassis(self)
        self.gimbal = FakeDirectGimbal(self)
        self.sequence = 100
        self.x = 0.0
        self.y = 0.0
        self.heading = 0.0
        self.gimbal_values = (0, 0, 0, 0)
        self.instances.append(self)

    def initialize(self, **_kwargs) -> bool:
        self.connected = True
        return True

    def enter_control_mode(self) -> tuple[int, ...]:
        self.control_mode = True
        return (self._next_sequence(),)

    def arm(self) -> None:
        assert self.control_mode
        self.armed = True

    def disarm(self) -> None:
        self.armed = False

    def wait_for_odometry(self, **_kwargs):
        return SimpleNamespace(
            received_at=1.0,
            sequence=self._next_sequence(),
            battery_percent=77,
            heading_like=self.heading,
            values=(self.x, self.y, 0.0, 0.0, 0.0),
            x=self.x,
            y=self.y,
            raw_motion_values=(0.0, 0.0, 0.0),
        )

    def wait_for_gimbal(self, **_kwargs):
        return SimpleNamespace(
            received_at=1.0,
            sequence=self._next_sequence(),
            values=self.gimbal_values,
            flag=0,
        )

    def set_led(self, **_values):
        return SimpleNamespace(sequence=self._next_sequence(), accepted=True)

    def set_muzzle_led(self, **_values):
        return SimpleNamespace(sequence=self._next_sequence(), accepted=True)

    def play_sound(self, _sound_id: int):
        return SimpleNamespace(sequence=self._next_sequence(), accepted=True)

    def fire_infrared(self, *, lease_seconds: float) -> None:
        assert self.armed
        assert lease_seconds > 0

    def close(self) -> None:
        self.connected = False
        self.control_mode = False
        self.armed = False

    def _next_sequence(self) -> int:
        self.sequence += 1
        return self.sequence


class FakeBridge:
    def __init__(self) -> None:
        self.worker_threads = {"rx": None, "motion": None}
        self.command_sequence = 1
        self.values = {
            "sequence": 1,
            "rx_command_seq": 1,
            "armed": True,
            "motion_active": False,
            "last_command": "system.stop",
            "last_command_ok": True,
            "last_command_error": "",
            "x": 0.0,
            "y": 0.0,
            "yaw": 0.0,
            "gimbal_yaw": 0.0,
            "gimbal_pitch": 0.0,
        }
        self.last_telemetry = LabTelemetry(1, 1, dict(self.values))

    def _update(self, command: str) -> None:
        self.command_sequence += 1
        self.values.update(
            sequence=self.command_sequence,
            rx_command_seq=self.command_sequence,
            last_command=command,
            last_command_ok=True,
            motion_active=False,
        )
        self.last_telemetry = LabTelemetry(
            self.command_sequence, self.command_sequence, dict(self.values)
        )

    def send(self, **command) -> bool:
        if "x" in command:
            self.values["x"] += float(command["x"]) * 0.2
            self.values["y"] += float(command["y"]) * 0.2
            self.values["yaw"] += float(command["z"]) * 0.2
            name = "chassis.move_with_speed"
        elif "gimbal_pitch" in command:
            self.values["gimbal_pitch"] += float(command["gimbal_pitch"]) * 0.2
            self.values["gimbal_yaw"] += float(command["gimbal_yaw"]) * 0.2
            name = "gimbal.rotate_with_speed"
        elif command.get("stop"):
            name = "system.stop"
        elif command.get("led"):
            name = "led.set"
        elif command.get("fire"):
            suffix = "fire_ir" if command["fire_type"] == "infrared" else "fire_gel"
            name = f"blaster.{suffix}"
            self.values.update(
                last_fire_led_ok=True,
                last_fire_sound_ok=True,
                last_fire_actuator_ok=True,
            )
        else:
            name = f"{command['module']}.{command['method']}"
            if name == "gimbal.recenter":
                self.values["gimbal_pitch"] = 0.0
                self.values["gimbal_yaw"] = 0.0
        self._update(name)
        return True

    def stop_robot(self) -> bool:
        return self.send(stop=True)


class FakeRobot:
    instances: list[FakeRobot] = []

    def __init__(self, *, robot_ip: str, appid: str, **_kwargs) -> None:
        self.robot_ip = robot_ip
        self.appid = appid
        self.connected = False
        self.info = AppConnectionInfo(robot_ip, appid, "idle", "00:11:22:33:44:55")
        self.base = SimpleNamespace(get_battery=lambda: 77)
        self.camera = FakeCamera()
        self.audio = FakeAudio()
        self.bridge = FakeBridge()
        self.config = SimpleNamespace(command_timeout=0.0)
        self.instances.append(self)

    def initialize(self, **_kwargs) -> bool:
        self.connected = True
        return True

    def enter_lab(self) -> None:
        return None

    def upload_lab_bridge(self) -> str:
        return "bridge-digest"

    def upload_prepared_program(self, _dsp, _identity) -> str:
        return "adb-digest"

    def start_lab_program(self, _digest: str) -> None:
        return None

    def stop_lab_program(self) -> None:
        return None

    def start_lab_bridge(self) -> None:
        self.bridge.worker_threads["rx"] = 1

    def set_led(self, **values) -> bool:
        return self.bridge.send(led=True, **values)

    def fire(self, fire_type: str) -> bool:
        return self.bridge.send(fire=True, fire_type=fire_type)

    def call(self, module: str, method: str, **params) -> bool:
        return self.bridge.send(module=module, method=method, params=params)

    def close(self) -> None:
        self.connected = False
        self.bridge.worker_threads = {"rx": None, "motion": None}


class FakeDeviceSession(DeviceSession):
    def verify_direct_loss_stop(self):
        self.close_robot()
        return {
            "child_exitcode": -15,
            "pre_loss_observed_displacement_m": 0.02,
            "post_loss_observation_seconds": 1.5,
            "passive_post_loss_samples": 1,
            "post_loss_displacement_m": 0.001,
            "maximum_allowed_post_loss_displacement_m": 0.05,
            "loss_stop_within_bound_observed": True,
        }

    def run_command(self, command, *, check=True, timeout=15.0):
        del timeout
        arguments = list(command)
        stdout = ""
        if arguments[-1] == "get-state":
            stdout = "device\n"
        elif arguments[-1] == "version":
            stdout = "Android Debug Bridge version 1.0.41\n"
        elif "connect" in arguments:
            stdout = "connected\n"
        elif "disconnect" in arguments:
            stdout = "disconnected\n"
        elif "shell" in arguments:
            shell = shlex.split(arguments[arguments.index("shell") + 1])
            if shell == ["id"]:
                stdout = "uid=0(root) gid=0(root)\n"
            elif shell[:1] == ["getprop"]:
                stdout = "running\n" if shell[1].startswith("init.svc") else "value\n"
            elif shell[:1] == ["/data/python_files/bin/python"]:
                stdout = "3.6.6 (default)\n"
            elif shell == ["ps"]:
                stdout = "USER PID NAME\nroot 1 /init\nroot 42 dji_scratch\n"
            elif shell == ["netstat", "-an"]:
                stdout = "udp 0 0 0.0.0.0:30030 0.0.0.0:*\n"
            elif shell == ["cat", "/proc/mounts"]:
                stdout = "/dev/root /system ext4 ro 0 0\n"
            elif shell == ["cat", "/proc/net/unix"]:
                stdout = "Num RefCount Protocol Flags Type St Inode Path\n"
            elif "sha256sum" in shell:
                stdout = f"{STOCK_HASHES[shell[-1]]}  {shell[-1]}\n"
            elif shell[:2] == ["ls", "-l"]:
                stdout = f"-rwxr-xr-x root root 1 {shell[-1]}\n"
        result = subprocess.CompletedProcess(arguments, 0, stdout, "")
        if check and result.returncode:
            raise RuntimeError("command failed")
        return result

    @staticmethod
    def _tcp_open(_host: str, _port: int, *, timeout: float) -> bool:
        del timeout
        return False


def all_config(tmp_path: Path) -> DiagnosisConfig:
    return DiagnosisConfig(
        checks=normalize_check_names([], all_checks=True),
        output_base=tmp_path,
        robot_ip="192.0.2.10",
        appid="b6359877",
        discovery_timeout=0.01,
        allow_motion=True,
        allow_infrared=True,
        allow_gel=True,
    )


def test_runner_executes_complete_diagnosis_and_cleans_up(tmp_path: Path) -> None:
    FakeRobot.instances.clear()
    FakeDirectRobot.instances.clear()
    config = all_config(tmp_path)
    recorder = DiagnosisRecorder(tmp_path, now=datetime(2026, 8, 30, tzinfo=timezone.utc))
    discovered = RobotBroadcast("192.0.2.10", "00:11:22:33:44:55", "b6359877", False)
    runner = DiagnosisRunner(
        config,
        recorder,
        console=Console(file=None, quiet=True),
        robot_factory=FakeRobot,  # type: ignore[arg-type]
        direct_factory=FakeDirectRobot,  # type: ignore[arg-type]
        sleep=lambda _seconds: None,
        discover=lambda _timeout: [discovered],
        session_type=FakeDeviceSession,
    )

    status, results = runner.run()

    assert status == 0
    assert [result.status for result in results] == ["PASS"] * 17
    by_name = {result.name: result for result in results}
    assert len(by_name["led"].evidence["colors"]) == 4
    assert [sound["sound_id"] for sound in by_name["speaker"].evidence["sounds"]] == [
        0x107,
        0x102,
    ]
    assert by_name["speaker"].evidence["host_pcm"]["transfer_packets"] == 2
    assert by_name["microphone"].evidence["format"] == "48 kHz mono signed 16-bit PCM"
    assert by_name["muzzle"].evidence["sequence"][-1] == "fire:off"
    assert len(by_name["chassis"].evidence["steps"]) == 6
    assert by_name["failsafe"].evidence["loss_stop_within_bound_observed"] is True
    assert len(by_name["gimbal"].evidence["steps"]) == 4
    assert by_name["gel"].evidence["count"] == 1
    assert by_name["cleanup"].evidence["adb_tcp_5555_closed"]
    assert by_name["cleanup"].evidence["pre_adb_robot_cleanup"]["motion_stopped"]
    assert by_name["system"].evidence["runtime_classification"] == "stock"
    report = recorder.report_path.read_text(encoding="utf-8")
    log = recorder.log_path.read_text(encoding="utf-8")
    assert "192.0.2.10" in report + log
    assert "b6359877" in report + log
    assert all(not robot.connected for robot in FakeRobot.instances)
    assert all(not robot.connected for robot in FakeDirectRobot.instances)


def test_pcm_level_detects_requested_tone_frequency() -> None:
    tone = DiagnosisChecks._tone_pcm(
        frequency=440.0,
        duration=0.02,
        amplitude=2000,
        sample_rate=48_000,
    )
    matching = DiagnosisChecks._pcm_level(tone, tone_frequency=440.0)
    unrelated = DiagnosisChecks._pcm_level(tone, tone_frequency=1000.0)

    assert matching["tone_amplitude"] > 1500
    assert unrelated["tone_amplitude"] < matching["tone_amplitude"] / 5


def test_runner_records_failure_and_still_cleans_up(tmp_path: Path) -> None:
    config = DiagnosisConfig(("app",), tmp_path, robot_ip="192.0.2.10", appid="b6359877")
    recorder = DiagnosisRecorder(tmp_path)

    def broken_factory(**_kwargs):
        raise RuntimeError("offline")

    runner = DiagnosisRunner(
        config,
        recorder,
        console=Console(file=None, quiet=True),
        robot_factory=broken_factory,
        direct_factory=broken_factory,
        sleep=lambda _seconds: None,
    )

    status, results = runner.run()

    assert status == 1
    assert results[0].status == "FAIL"
    assert results[-1].status == "PASS"
    assert "offline" in results[0].error


def test_cleanup_accepts_adb_client_timeout_when_port_closed(tmp_path: Path) -> None:
    class TimeoutRebootSession(FakeDeviceSession):
        def run_command(self, command, *, check=True, timeout=15.0):
            if list(command)[-1] == "reboot":
                raise subprocess.TimeoutExpired(command, timeout)
            return super().run_command(command, check=check, timeout=timeout)

    config = DiagnosisConfig(("app",), tmp_path, robot_ip="192.0.2.10", appid="b6359877")
    recorder = DiagnosisRecorder(tmp_path)
    discovered = RobotBroadcast("192.0.2.10", "00:11:22:33:44:55", "b6359877", False)
    session = TimeoutRebootSession(
        config,
        recorder,
        discover=lambda _timeout: [discovered],
        sleep=lambda _seconds: None,
    )
    session.adb_target = "192.0.2.10:5555"
    session.adb_enabled = True
    session.adb_ready = True

    evidence, errors = session.cleanup()

    assert not errors
    assert evidence["reboot_client_timeout"] is True
    assert evidence["post_reboot_broadcast_received"] is True
    assert evidence["adb_tcp_5555_closed"] is True


def test_cleanup_rejects_port_closure_without_post_reboot_broadcast(tmp_path: Path) -> None:
    config = DiagnosisConfig(("app",), tmp_path, robot_ip="192.0.2.10", appid="b6359877")
    recorder = DiagnosisRecorder(tmp_path)
    session = FakeDeviceSession(
        config,
        recorder,
        discover=lambda _timeout: [],
        sleep=lambda _seconds: None,
    )
    session.adb_target = "192.0.2.10:5555"
    session.adb_enabled = True
    session.adb_ready = True

    evidence, errors = session.cleanup()

    assert errors == ["S1 App broadcast did not return after reboot"]
    assert evidence["post_reboot_broadcast_received"] is False
    assert evidence["adb_tcp_5555_closed"] is False


def test_run_diagnosis_applies_safety_policy(tmp_path: Path) -> None:
    assert DEFAULT_CHECKS[0] == "discovery"
    with pytest.raises(ValueError, match="allow-gel"):
        run_diagnosis(DiagnosisConfig(("gel",), tmp_path))
