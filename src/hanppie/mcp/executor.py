"""Persistent worker manager for Python code submitted through MCP."""

from __future__ import annotations

import ipaddress
import multiprocessing
import os
import platform
import threading
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from multiprocessing.connection import Connection
from pathlib import Path
from typing import Any, Literal

from hanppie import __version__
from hanppie.diagnosis.discovery import discover_robots
from hanppie.lab.protocol import RobotBroadcast, normalize_appid
from hanppie.mcp.recorder import MCPRecorder
from hanppie.mcp.worker import run_worker

RobotAccess = Literal["auto", "reuse", "none"]


@dataclass(frozen=True)
class ExecutorConfig:
    robot_ip: str | None = None
    appid: str | None = None
    local_ip: str = "0.0.0.0"
    discovery_timeout: float = 4.0
    connection_timeout: float = 10.0
    execution_timeout: float = 20.0
    max_execution_timeout: float = 60.0
    max_code_chars: int = 100_000
    max_output_chars: int = 20_000
    artifact_base: Path = Path(".hanppie/mcp")
    debug: bool = False

    def __post_init__(self) -> None:
        if self.robot_ip is not None:
            ipaddress.IPv4Address(self.robot_ip)
        ipaddress.IPv4Address(self.local_ip)
        if self.appid is not None:
            normalized_appid = normalize_appid(self.appid)
            if normalized_appid == "00000000":
                raise ValueError("AppID 00000000 cannot be used as an explicit MCP target")
            object.__setattr__(self, "appid", normalized_appid)
        if self.discovery_timeout <= 0:
            raise ValueError("discovery_timeout must be greater than 0")
        if self.connection_timeout <= 0:
            raise ValueError("connection_timeout must be greater than 0")
        if not 0 < self.execution_timeout <= self.max_execution_timeout:
            raise ValueError("execution_timeout must be within max_execution_timeout")
        if self.max_code_chars <= 0 or self.max_output_chars <= 0:
            raise ValueError("code and output limits must be greater than 0")


class WorkerTimeoutError(TimeoutError):
    """The persistent worker did not answer before its safety deadline."""


class PythonExecutor:
    """Serialize calls through one worker and reuse its DirectRobot connection."""

    def __init__(
        self,
        config: ExecutorConfig,
        *,
        discover: Callable[[float], list[RobotBroadcast]] = discover_robots,
    ) -> None:
        self.config = config
        self._lock = threading.Lock()
        self._artifact_base = config.artifact_base.resolve()
        self._recorder = MCPRecorder(self._artifact_base)
        self._discover = discover
        self._target_selection = (
            "explicit" if config.robot_ip and config.appid else "automatic_on_first_connection"
        )
        self._resolved_target = (
            (config.robot_ip, config.appid) if config.robot_ip and config.appid else None
        )
        self._process: multiprocessing.Process | None = None
        self._connection: Connection | None = None

    def describe_context(self) -> dict[str, object]:
        return self._recorder.record_call("get_python_context", {}, self._describe_context)

    def context_snapshot(self) -> dict[str, object]:
        """Return the execution contract without activating persistent records."""

        return self._describe_context()

    def _describe_context(self) -> dict[str, object]:
        target = self._resolved_target
        return {
            "target": {
                "robot_ip": target[0] if target else self.config.robot_ip,
                "backends": ["DirectRobot", "LabRobot/Bridge"],
                "selection": self._target_selection,
                "resolved": target is not None,
                "discovery_timeout_seconds": self.config.discovery_timeout,
            },
            "server": {
                "hanppie_version": __version__,
                "context_schema_version": 2,
                "process_id": os.getpid(),
                "started_at": self._recorder.started.isoformat(timespec="seconds"),
                "python": platform.python_version(),
            },
            "capabilities": {
                "motion": True,
                "infrared": True,
                "gel": True,
                "startup_permission_flags": False,
                "direct_actions_use_robot_arm": True,
                "gel_backend": "persistent LabRobot/Bridge",
                "exact_chassis_angle_control": False,
                "exact_gimbal_angle_control": False,
            },
            "execution": {
                "default_timeout_seconds": self.config.execution_timeout,
                "max_timeout_seconds": self.config.max_execution_timeout,
                "persistent_worker": True,
                "persistent_robot_connection": True,
                "fresh_python_namespace_per_call": True,
                "working_directory": "the current session's .hanppie/mcp calls directory",
                "robot_access": {
                    "auto": "connect if needed, then inject robot",
                    "reuse": "inject an existing robot connection without opening one",
                    "none": "always inject robot=None and do not discover or connect",
                    "legacy_connect_robot_false": "maps to reuse, not none",
                },
            },
            "recording": {
                "root": str(self._artifact_base),
                "session_id": self._recorder.session_id,
                "session_dir": str(self._recorder.session_dir),
                "server_log": str(self._recorder.server_log_path),
                "calls_log": str(self._recorder.calls_log_path),
            },
            "preloaded_names": {
                "robot": (
                    "Direct/Lab routing facade for auto, an existing facade or None for "
                    "reuse, and always None for none"
                ),
                "time": "Python time module",
                "sleep": "time.sleep",
                "output_dir": "pathlib.Path for this call's artifacts",
                "save_frame": "save_frame(av_frame, filename='capture.jpg')",
                "result": "assign any JSON-serializable final value here",
                "checkpoint": (
                    "checkpoint(name, **data) appends a durable events.jsonl entry and is "
                    "returned even if later Python code fails"
                ),
            },
            "robot_api": {
                "robot.status": "() -> dict; includes backend, transition, and telemetry",
                "robot.arm": "() -> None; selects DirectRobot",
                "robot.disarm": "() -> None; neutralizes the active backend",
                "robot.stop": "() -> None; stops the active backend without switching",
                "robot.chassis.drive_speed": (
                    "(x=0.0, y=0.0, z=0.0, *, lease_seconds=0.25) -> None; "
                    "velocity command, not verified exact-distance or exact-angle motion"
                ),
                "robot.chassis.stop": "() -> None; never opens or switches a backend",
                "robot.gimbal.drive_speed": (
                    "(*, pitch_speed=0.0, yaw_speed=0.0, lease_seconds=0.25) -> None; "
                    "velocity command, not verified exact-angle motion"
                ),
                "robot.gimbal.stop": "() -> None; never opens or switches a backend",
                "robot.set_led": (
                    "(*, component='all', red=255, green=255, blue=255, "
                    "effect='on', timeout=1.0); component=all|top|gimbal|bottom, "
                    "effect=off|on|solid|breath|flash"
                ),
                "robot.set_muzzle_led": (
                    "(*, fire: bool, enabled: bool, timeout=1.0); no RGB parameters"
                ),
                "robot.play_sound": "(sound_id: int, *, timeout=1.0) -> DirectAck",
                "robot.capture": "(*, timeout=1.0) -> DirectAck; trigger the robot shutter",
                "robot.fire_infrared": "(*, lease_seconds=0.12) -> None; requires robot.arm()",
                "robot.fire_gel": "() -> dict; switches to or reuses LabRobot/Bridge",
                "robot.fire": "(fire_type='infrared') -> object; infrared|gel",
                "robot.camera": (
                    "start_video_stream(*, display=False, resolution='720p'); "
                    "read_video_frame(*, timeout=3.0, strategy='pipeline'); "
                    "stop_video_stream(); start_audio_stream(); "
                    "read_audio_frame(*, timeout=1.0); stop_audio_stream()"
                ),
                "robot.audio": "play_pcm(pcm: bytes) -> int; 12 kHz mono signed 16-bit PCM",
            },
            "examples": {
                "status": "result = robot.status()",
                "led": "result = robot.set_led(red=0, green=255, blue=0)",
                "turn": (
                    "robot.arm()\n"
                    "try:\n"
                    "    robot.chassis.drive_speed(z=15, lease_seconds=0.25)\n"
                    "    sleep(0.2)\n"
                    "finally:\n"
                    "    robot.stop()\n"
                    "    robot.disarm()\n"
                    "result = robot.status()"
                ),
                "photo": (
                    "robot.camera.start_video_stream()\n"
                    "try:\n"
                    "    frame = robot.camera.read_video_frame(timeout=3)\n"
                    "    result = save_frame(frame) if frame else None\n"
                    "finally:\n"
                    "    robot.camera.stop_video_stream()"
                ),
                "gel": "result = robot.fire_gel()",
                "partial_progress": (
                    "checkpoint('rotation_complete', nominal_degrees=360)\n"
                    "result = {'completed': True}"
                ),
            },
        }

    def connect(self) -> dict[str, Any]:
        return self._recorder.record_call("connect_robot", {}, self._connect)

    def _connect(self) -> dict[str, Any]:
        with self._lock:
            robot_ip, appid = self._resolve_target()
            return self._request(
                {"action": "connect", "robot_ip": robot_ip, "appid": appid},
                timeout_seconds=self.config.connection_timeout + 2.0,
            )

    def connection_status(self) -> dict[str, Any]:
        return self._recorder.record_call(
            "get_connection_status",
            {},
            self._connection_status,
        )

    def _connection_status(self) -> dict[str, Any]:
        with self._lock:
            if not self._worker_alive():
                self._drop_worker()
                return {
                    "ok": True,
                    "connection": {
                        "connected": False,
                        "robot_ip": self._resolved_target[0] if self._resolved_target else None,
                        "appid": self._resolved_target[1] if self._resolved_target else None,
                        "control_mode": False,
                        "armed": False,
                        "connection_generation": 0,
                    },
                }
            return self._request({"action": "status"}, timeout_seconds=2.0)

    def execute(
        self,
        code: str,
        *,
        robot_access: RobotAccess = "auto",
        connect_robot: bool | None = None,
        timeout_seconds: float | None = None,
    ) -> dict[str, Any]:
        return self._recorder.record_call(
            "execute_python",
            {
                "code": code,
                "robot_access": robot_access,
                "connect_robot": connect_robot,
                "timeout_seconds": timeout_seconds,
            },
            lambda: self._execute(
                code,
                robot_access=robot_access,
                connect_robot=connect_robot,
                timeout_seconds=timeout_seconds,
            ),
        )

    def _execute(
        self,
        code: str,
        *,
        robot_access: RobotAccess,
        connect_robot: bool | None,
        timeout_seconds: float | None,
    ) -> dict[str, Any]:
        effective_access = self._normalize_robot_access(robot_access, connect_robot)
        if not code.strip():
            raise ValueError("code must not be empty")
        if len(code) > self.config.max_code_chars:
            raise ValueError(f"code exceeds {self.config.max_code_chars} characters")
        effective_timeout = self.config.execution_timeout
        if timeout_seconds is not None:
            effective_timeout = float(timeout_seconds)
        if not 0 < effective_timeout <= self.config.max_execution_timeout:
            raise ValueError(
                "timeout_seconds must be greater than 0 and no more than "
                f"{self.config.max_execution_timeout}"
            )

        with self._lock:
            target = (
                self._resolve_target()
                if effective_access == "auto"
                else self._resolved_target
                if effective_access == "reuse"
                else None
            )
            run_id = f"{datetime.now().strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:8]}"
            output_dir = self._recorder.calls_dir / run_id
            output_dir.mkdir(parents=True, exist_ok=False)
            request = {
                "action": "execute",
                "code": code,
                "robot_access": effective_access,
                "output_dir": str(output_dir),
                "robot_ip": target[0] if target else None,
                "appid": target[1] if target else None,
            }
            started = time.monotonic()
            try:
                response = self._request(request, timeout_seconds=effective_timeout)
            except WorkerTimeoutError:
                response = {
                    "ok": False,
                    "timed_out": True,
                    "error": {
                        "type": "TimeoutExpired",
                        "message": f"Python execution exceeded {effective_timeout} seconds",
                    },
                    "stdout": "",
                    "stderr": "",
                    "artifacts": self._list_artifacts(output_dir),
                    "connection": {
                        "connected": False,
                        "robot_ip": target[0] if target else None,
                        "appid": target[1] if target else None,
                        "control_mode": False,
                        "armed": False,
                    },
                }
            response["artifact_dir"] = str(output_dir)
            response["run_id"] = run_id
            response.setdefault("timed_out", False)
            response["duration_seconds"] = round(time.monotonic() - started, 6)
            if isinstance(response.get("stdout"), str):
                response["stdout"] = self._truncate(response["stdout"])
            if isinstance(response.get("stderr"), str):
                response["stderr"] = self._truncate(response["stderr"])
            return response

    def disconnect(self) -> dict[str, Any]:
        return self._recorder.record_call("disconnect_robot", {}, self._disconnect)

    def _disconnect(self) -> dict[str, Any]:
        with self._lock:
            if not self._worker_alive():
                self._drop_worker()
                return {"ok": True, "cleanup_errors": [], "connection": {"connected": False}}
            return self._request({"action": "disconnect"}, timeout_seconds=5.0)

    def close(self) -> None:
        try:
            with self._lock:
                if self._worker_alive():
                    try:
                        self._request({"action": "shutdown"}, timeout_seconds=5.0)
                    except (EOFError, OSError, WorkerTimeoutError):
                        self._stop_worker()
                    else:
                        assert self._process is not None
                        self._process.join(timeout=2.0)
                        if self._process.is_alive():
                            self._stop_worker()
                self._drop_worker()
        finally:
            self._recorder.close()

    def _request(self, request: dict[str, Any], *, timeout_seconds: float) -> dict[str, Any]:
        self._ensure_worker()
        assert self._connection is not None
        try:
            self._connection.send(request)
            if not self._connection.poll(timeout_seconds):
                self._stop_worker()
                raise WorkerTimeoutError
            response = self._connection.recv()
        except (BrokenPipeError, EOFError, OSError):
            self._stop_worker()
            raise
        if not isinstance(response, dict):
            self._stop_worker()
            raise RuntimeError("MCP worker returned an invalid response")
        return response

    def _ensure_worker(self) -> None:
        if self._worker_alive():
            return
        self._drop_worker()
        context = multiprocessing.get_context("spawn")
        parent_connection, child_connection = context.Pipe()
        process = context.Process(
            target=run_worker,
            args=(
                child_connection,
                self._runtime_config_values(),
                str(self._recorder.server_log_path),
            ),
            name="hanppie-mcp-worker",
        )
        process.start()
        child_connection.close()
        self._connection = parent_connection
        self._process = process
        self._recorder.log("INFO", "worker.start", pid=process.pid)

    def _worker_alive(self) -> bool:
        return self._process is not None and self._process.is_alive()

    def _stop_worker(self) -> None:
        process = self._process
        if process is not None and process.is_alive():
            pid = process.pid
            process.kill()
            process.join(timeout=2.0)
            if process.is_alive():
                process.terminate()
                process.join(timeout=2.0)
            self._recorder.log("WARNING", "worker.stop", pid=pid, mode="forced")
        self._drop_worker()

    def _drop_worker(self) -> None:
        if self._connection is not None:
            self._connection.close()
        if self._process is not None and not self._process.is_alive():
            self._process.join(timeout=0.1)
        self._connection = None
        self._process = None

    def _resolve_target(self) -> tuple[str, str]:
        if self._resolved_target is not None:
            return self._resolved_target

        candidates: dict[tuple[str, str], RobotBroadcast] = {}
        for broadcast in self._discover(self.config.discovery_timeout):
            try:
                robot_ip = str(ipaddress.IPv4Address(broadcast.robot_ip))
                appid = normalize_appid(broadcast.appid)
            except (ValueError, UnicodeError):
                continue
            if appid == "00000000":
                continue
            if self.config.robot_ip and robot_ip != self.config.robot_ip:
                continue
            if self.config.appid and appid != self.config.appid:
                continue
            candidates[(robot_ip, appid)] = broadcast

        targets = sorted(candidates)
        if not targets:
            filters = []
            if self.config.robot_ip:
                filters.append(f"IP {self.config.robot_ip}")
            if self.config.appid:
                filters.append(f"AppID {self.config.appid}")
            suffix = f" matching {' and '.join(filters)}" if filters else ""
            raise RuntimeError(
                f"No usable RoboMaster broadcast was discovered{suffix}; keep the computer and robot "
                "on the same LAN, or pass --robot-ip and --appid explicitly"
            )
        if len(targets) > 1:
            addresses = ", ".join(target[0] for target in targets)
            raise RuntimeError(
                f"Multiple RoboMaster robots were discovered ({addresses}); select one with "
                "--robot-ip or --appid"
            )

        self._resolved_target = targets[0]
        return self._resolved_target

    def _runtime_config_values(self) -> dict[str, Any]:
        return {
            "local_ip": self.config.local_ip,
            "connection_timeout": self.config.connection_timeout,
            "debug": self.config.debug,
        }

    @staticmethod
    def _normalize_robot_access(
        robot_access: RobotAccess,
        connect_robot: bool | None,
    ) -> RobotAccess:
        if robot_access not in {"auto", "reuse", "none"}:
            raise ValueError("robot_access must be 'auto', 'reuse', or 'none'")
        if connect_robot is None:
            return robot_access
        legacy_access: RobotAccess = "auto" if connect_robot else "reuse"
        if robot_access != "auto" and robot_access != legacy_access:
            raise ValueError("connect_robot conflicts with robot_access")
        return legacy_access

    def _truncate(self, value: str) -> str:
        if len(value) <= self.config.max_output_chars:
            return value
        omitted = len(value) - self.config.max_output_chars
        return value[: self.config.max_output_chars] + f"\n... <{omitted} characters omitted>"

    @staticmethod
    def _list_artifacts(output_dir: Path) -> list[dict[str, object]]:
        return [
            {"path": str(path), "size": path.stat().st_size}
            for path in sorted(output_dir.rglob("*"))
            if path.is_file()
        ]
