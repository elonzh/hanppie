"""Connection and cleanup lifecycle shared by all diagnosis checks."""

from __future__ import annotations

import shlex
import socket
import subprocess
import time
from collections.abc import Callable, Sequence
from importlib.resources import files
from typing import Any

from hanppie.diagnosis.discovery import discover_robots
from hanppie.diagnosis.model import DiagnosisConfig
from hanppie.diagnosis.recorder import DiagnosisRecorder
from hanppie.lab.program import build_lab_program
from hanppie.lab.protocol import RobotBroadcast
from hanppie.lab.robot import LabRobot


class DeviceSession:
    """Own App, Lab, and temporary ADB state for exactly one diagnosis run."""

    def __init__(
        self,
        config: DiagnosisConfig,
        recorder: DiagnosisRecorder,
        *,
        robot_factory: Callable[..., LabRobot] = LabRobot,
        sleep: Callable[[float], None] = time.sleep,
        discover: Callable[[float], list[RobotBroadcast]] = discover_robots,
    ) -> None:
        self.config = config
        self.recorder = recorder
        self.robot_factory = robot_factory
        self.sleep = sleep
        self.discover = discover
        self.robot_ip = config.robot_ip
        self.appid = config.appid
        self.robot: LabRobot | None = None
        self.lab_ready = False
        self.adb_target = ""
        self.adb_enabled = False
        self.adb_ready = False
        self.pre_adb_cleanup: dict[str, Any] = {}

    def discover_devices(self) -> list[RobotBroadcast]:
        return self.discover(self.config.discovery_timeout)

    def adopt_discovery(self, robots: Sequence[RobotBroadcast]) -> RobotBroadcast:
        selected = self._select_broadcast(robots)
        self.robot_ip = selected.robot_ip
        if selected.appid != "00000000":
            self.appid = selected.appid
        return selected

    def ensure_app(self) -> LabRobot:
        if self.robot is not None and self.robot.connected:
            return self.robot
        self._ensure_identity()
        assert self.robot_ip is not None and self.appid is not None
        self.robot = self.robot_factory(
            robot_ip=self.robot_ip,
            appid=self.appid,
            debug=self.config.debug,
        )
        if not self.robot.initialize(
            conn_type="sta", proto_type="udp", timeout=self.config.timeout
        ):
            raise RuntimeError("S1 App 连接初始化失败")
        return self.robot

    def ensure_lab(self) -> LabRobot:
        robot = self.ensure_app()
        if self.lab_ready:
            return robot
        robot.enter_lab()
        digest = robot.upload_lab_bridge()
        robot.start_lab_program(digest)
        robot.start_lab_bridge()
        self.lab_ready = True
        return robot

    def ensure_adb(self) -> str:
        if self.adb_ready:
            return self.adb_shell("id")
        self._ensure_identity()
        self.pre_adb_cleanup = self.close_robot()
        assert self.robot_ip is not None and self.appid is not None
        self.adb_target = f"{self.robot_ip}:5555"
        payload = files("hanppie.payloads").joinpath("enable_adb_standalone.py.txt")
        dsp, identity = build_lab_program(payload.read_text(encoding="utf-8"), title="Hanppie-ADB")
        robot = self.robot_factory(
            robot_ip=self.robot_ip,
            appid=self.appid,
            debug=self.config.debug,
        )
        initialized = False
        started = False
        try:
            initialized = robot.initialize(
                conn_type="sta", proto_type="udp", timeout=self.config.timeout
            )
            if not initialized:
                raise RuntimeError("无法建立用于启用 ADB 的 App 会话")
            robot.enter_lab()
            digest = robot.upload_prepared_program(dsp, identity)
            robot.start_lab_program(digest)
            started = True
            self.adb_enabled = True
            self.sleep(3.0)
        finally:
            if started:
                try:
                    robot.stop_lab_program()
                except Exception:
                    pass
            if initialized:
                robot.close()
        self._wait_for_adb()
        identity_text = self.adb_shell("id")
        self.adb_ready = True
        return identity_text

    def telemetry(self) -> dict[str, Any]:
        if self.robot is None or self.robot.bridge.last_telemetry is None:
            raise RuntimeError("没有 Lab 遥测")
        values = dict(self.robot.bridge.last_telemetry.values)
        values.pop("session_id", None)
        return values

    def wait_command(self, name: str, sequence: int) -> dict[str, Any]:
        return self.wait_telemetry(
            lambda values: (
                int(values.get("rx_command_seq", 0) or 0) >= sequence
                and values.get("last_command") == name
            )
        )

    def wait_telemetry(
        self, predicate: Callable[[dict[str, Any]], bool], timeout: float | None = None
    ) -> dict[str, Any]:
        deadline = time.monotonic() + (timeout or self.config.timeout)
        while time.monotonic() < deadline:
            values = self.telemetry()
            if predicate(values):
                return values
            self.sleep(0.02)
        raise TimeoutError("等待机内遥测确认超时")

    def adb_shell(self, *arguments: str, check: bool = True) -> str:
        result = self._adb_run("shell", shlex.join(arguments), check=check)
        return "\n".join(part.strip() for part in (result.stdout, result.stderr) if part.strip())

    def run_command(
        self,
        command: Sequence[str],
        *,
        check: bool = True,
        timeout: float = 15.0,
    ) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            list(command),
            text=True,
            capture_output=True,
            timeout=timeout,
        )
        self.recorder.event(
            "debug" if result.returncode == 0 else "warning",
            "host.command",
            "执行主机命令",
            command=list(command),
            returncode=result.returncode,
            stdout=result.stdout,
            stderr=result.stderr,
        )
        if check and result.returncode != 0:
            output = result.stderr.strip() or result.stdout.strip()
            raise RuntimeError(f"命令失败 ({result.returncode})：{' '.join(command)}：{output}")
        return result

    def close_robot(self) -> dict[str, Any]:
        """Stop active output, return the gimbal to center, and close App/Lab."""

        robot = self.robot
        self.robot = None
        lab_ready = self.lab_ready
        self.lab_ready = False
        evidence: dict[str, Any] = {"robot_session_closed": robot is not None}
        if robot is None:
            return evidence
        try:
            if lab_ready:
                stop_sequence = robot.bridge.command_sequence + 1
                if robot.bridge.stop_robot():
                    stopped = self.wait_command_on(robot, "system.stop", stop_sequence)
                    evidence["motion_stopped"] = stopped.get("motion_active") is False

                led_sequence = robot.bridge.command_sequence + 1
                if robot.set_led(component="all", red=0, green=0, blue=0, effect="off"):
                    self.wait_command_on(robot, "led.set", led_sequence)
                    evidence["led_off"] = True

                blaster_sequence = robot.bridge.command_sequence + 1
                if robot.call("blaster", "reset_led"):
                    reset = self.wait_command_on(robot, "blaster.reset_led", blaster_sequence)
                    evidence["blaster_led_off"] = reset.get("last_command_ok") is True

                if self.config.allow_motion and "gimbal" in self.config.checks:
                    center_sequence = robot.bridge.command_sequence + 1
                    if robot.call("gimbal", "recenter"):
                        centered = self.wait_command_on(robot, "gimbal.recenter", center_sequence)
                        evidence["gimbal_recenter_acknowledged"] = (
                            centered.get("last_command_ok") is True
                        )
                        self.sleep(0.8)
        finally:
            robot.close()
        return evidence

    def cleanup(self) -> tuple[dict[str, Any], list[str]]:
        evidence: dict[str, Any] = {}
        errors: list[str] = []
        if self.pre_adb_cleanup:
            evidence["pre_adb_robot_cleanup"] = self.pre_adb_cleanup
        try:
            evidence.update(self.close_robot())
        except Exception as exc:
            errors.append(f"robot: {type(exc).__name__}: {exc}")
        if self.adb_enabled:
            try:
                if not self.adb_ready:
                    self.run_command(
                        [self.config.adb, "connect", self.adb_target],
                        check=False,
                        timeout=5.0,
                    )
                self.adb_shell("setprop", "service.adb.tcp.port", "-1")
                evidence["adb_tcp_disable_requested"] = True
                try:
                    reboot = self._adb_run("shell", "reboot", check=False, timeout=2.0)
                    evidence["reboot_returncode"] = reboot.returncode
                except subprocess.TimeoutExpired:
                    evidence["reboot_client_timeout"] = True
                evidence["reboot_method"] = "device-shell"
                self.run_command(
                    [self.config.adb, "disconnect", self.adb_target],
                    check=False,
                    timeout=5.0,
                )
                deadline = time.monotonic() + max(15.0, self.config.timeout)
                while self._tcp_open(self.robot_ip or "", 5555, timeout=0.5):
                    if time.monotonic() >= deadline:
                        break
                    self.sleep(0.5)
                initially_closed = not self._tcp_open(self.robot_ip or "", 5555, timeout=0.5)
                evidence["adb_tcp_closed_during_reboot"] = initially_closed
                if not initially_closed:
                    errors.append("ADB TCP 5555 remained reachable while rebooting")

                restarted = self.discover(max(45.0, self.config.timeout * 3.0))
                evidence["post_reboot_broadcast_received"] = bool(restarted)
                if not restarted:
                    errors.append("S1 App broadcast did not return after reboot")

                stable_closed = True
                if restarted:
                    self.sleep(3.0)
                for _ in range(4):
                    if self._tcp_open(self.robot_ip or "", 5555, timeout=0.5):
                        stable_closed = False
                        break
                    self.sleep(0.5)
                closed = initially_closed and bool(restarted) and stable_closed
                evidence["adb_tcp_5555_closed"] = closed
                if restarted and not stable_closed:
                    errors.append("ADB TCP 5555 reopened after S1 completed reboot")
                if closed:
                    self.adb_enabled = False
            except Exception as exc:
                errors.append(f"adb: {type(exc).__name__}: {exc}")
        return evidence, errors

    def wait_command_on(self, robot: LabRobot, name: str, sequence: int) -> dict[str, Any]:
        deadline = time.monotonic() + self.config.timeout
        while time.monotonic() < deadline:
            telemetry = robot.bridge.last_telemetry
            if telemetry is not None:
                values = dict(telemetry.values)
                if (
                    int(values.get("rx_command_seq", 0) or 0) >= sequence
                    and values.get("last_command") == name
                ):
                    return values
            self.sleep(0.02)
        raise TimeoutError(f"等待 {name} 遥测确认超时")

    def _ensure_identity(self) -> None:
        if self.robot_ip and self.appid:
            return
        robots = self.discover_devices()
        if not robots:
            raise RuntimeError("缺少 --robot-ip/--appid，且未发现 S1")
        self.adopt_discovery(robots)
        if not self.robot_ip or not self.appid or self.appid == "00000000":
            raise RuntimeError("广播中没有可用 AppID，请显式传入 --appid")

    def _select_broadcast(self, robots: Sequence[RobotBroadcast]) -> RobotBroadcast:
        for robot in robots:
            if self.robot_ip and robot.robot_ip != self.robot_ip:
                continue
            if self.appid and robot.appid != self.appid:
                continue
            return robot
        if self.robot_ip or self.appid:
            raise RuntimeError("收到的广播与显式机器人目标不匹配")
        if not robots:
            raise TimeoutError("未收到 S1 App 广播")
        return robots[0]

    def _wait_for_adb(self) -> None:
        deadline = time.monotonic() + self.config.timeout
        state = ""
        connect_output = ""
        while time.monotonic() < deadline:
            connected = self.run_command(
                [self.config.adb, "connect", self.adb_target], check=False, timeout=5.0
            )
            connect_output = connected.stderr.strip() or connected.stdout.strip()
            state_result = self._adb_run("get-state", check=False, timeout=5.0)
            state = state_result.stdout.strip()
            if state_result.returncode == 0 and state == "device":
                return
            self.sleep(0.25)
        raise RuntimeError(f"ADB target 未就绪：state={state!r}, connect={connect_output}")

    def _adb_run(
        self,
        *arguments: str,
        check: bool = True,
        timeout: float = 15.0,
    ) -> subprocess.CompletedProcess[str]:
        if not self.adb_target:
            raise RuntimeError("ADB target 尚未建立")
        return self.run_command(
            [self.config.adb, "-s", self.adb_target, *arguments],
            check=check,
            timeout=timeout,
        )

    @staticmethod
    def _tcp_open(host: str, port: int, *, timeout: float) -> bool:
        if not host:
            return False
        try:
            with socket.create_connection((host, port), timeout=timeout):
                return True
        except OSError:
            return False
