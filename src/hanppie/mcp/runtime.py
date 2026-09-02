"""Persistent robot runtime owned by the isolated MCP worker process."""

from __future__ import annotations

import dataclasses
import json
import os
import time
import traceback
from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime
from enum import Enum
from io import StringIO
from pathlib import Path
from typing import Any

from hanppie.lab.direct import DirectRobot
from hanppie.lab.robot import LabRobot


@dataclasses.dataclass(frozen=True)
class RuntimeConfig:
    """Connection settings fixed for one MCP server lifetime."""

    local_ip: str = "0.0.0.0"
    connection_timeout: float = 10.0
    lab_transition_attempts: int = 2
    lab_retry_settle: float = 0.25
    debug: bool = False


class MCPChassis:
    """Resolve the persistent Direct backend only when chassis code uses it."""

    def __init__(self, runtime: PersistentRuntime) -> None:
        self._runtime = runtime

    def drive_speed(
        self,
        x: float = 0.0,
        y: float = 0.0,
        z: float = 0.0,
        *,
        lease_seconds: float = 0.25,
    ) -> None:
        self._runtime._ensure_direct().chassis.drive_speed(
            x=x,
            y=y,
            z=z,
            lease_seconds=lease_seconds,
        )

    def drive_wheels(
        self,
        w1: int = 0,
        w2: int = 0,
        w3: int = 0,
        w4: int = 0,
        *,
        timeout: float = 1.0,
    ) -> object:
        return self._runtime._ensure_direct().chassis.drive_wheels(
            w1,
            w2,
            w3,
            w4,
            timeout=timeout,
        )

    def stop(self) -> None:
        """Stop the active chassis without opening or switching a backend."""

        self._runtime._stop_chassis()


class MCPGimbal:
    """Resolve the persistent Direct backend only when gimbal code uses it."""

    def __init__(self, runtime: PersistentRuntime) -> None:
        self._runtime = runtime

    def drive_speed(
        self,
        *,
        pitch_speed: float = 0.0,
        yaw_speed: float = 0.0,
        lease_seconds: float = 0.25,
    ) -> None:
        self._runtime._ensure_direct().gimbal.drive_speed(
            pitch_speed=pitch_speed,
            yaw_speed=yaw_speed,
            lease_seconds=lease_seconds,
        )

    def stop(self) -> None:
        """Stop the active gimbal without opening or switching a backend."""

        self._runtime._stop_gimbal()


class RobotFacade:
    """Python-facing robot that routes verified capabilities to Direct or Lab."""

    def __init__(self, runtime: PersistentRuntime) -> None:
        self._runtime = runtime
        self.chassis = MCPChassis(runtime)
        self.gimbal = MCPGimbal(runtime)

    @property
    def connected(self) -> bool:
        return bool(self._runtime.status()["connected"])

    @property
    def control_mode(self) -> bool:
        return bool(self._runtime.status()["control_mode"])

    @property
    def armed(self) -> bool:
        return bool(self._runtime.status()["armed"])

    @property
    def info(self) -> object:
        return self._runtime._active_robot().info

    @property
    def odometry(self) -> object:
        direct = self._runtime._direct_robot
        return direct.odometry if direct is not None and direct.connected else None

    @property
    def gimbal_telemetry(self) -> object:
        direct = self._runtime._direct_robot
        return direct.gimbal_telemetry if direct is not None and direct.connected else None

    @property
    def camera(self) -> object:
        return self._runtime._active_robot().camera

    @property
    def audio(self) -> object:
        return self._runtime._active_robot().audio

    def arm(self) -> None:
        self._runtime._ensure_direct().arm()

    def disarm(self) -> None:
        errors = self._runtime._neutralize()
        if errors:
            raise RuntimeError("; ".join(errors))

    def stop(self) -> None:
        direct = self._runtime._direct_robot
        if direct is not None and direct.connected:
            direct.stop()
            return
        lab = self._runtime._lab_robot
        if lab is not None and lab.connected:
            lab.bridge.stop_robot()

    def set_led(
        self,
        *,
        component: str = "all",
        red: int = 255,
        green: int = 255,
        blue: int = 255,
        effect: str = "on",
        timeout: float = 1.0,
    ) -> object:
        """Set armor LEDs, retaining the active backend when it supports LEDs."""

        lab = self._runtime._lab_robot
        if lab is not None and lab.connected:
            return lab.set_led(
                component=component,
                red=red,
                green=green,
                blue=blue,
                effect=effect,
            )
        return self._runtime._ensure_direct().set_led(
            component=component,
            red=red,
            green=green,
            blue=blue,
            effect=effect,
            timeout=timeout,
        )

    def set_muzzle_led(
        self,
        *,
        fire: bool,
        enabled: bool,
        timeout: float = 1.0,
    ) -> object:
        """Set the normal or firing muzzle LED through DirectRobot."""

        return self._runtime._ensure_direct().set_muzzle_led(
            fire=fire,
            enabled=enabled,
            timeout=timeout,
        )

    def play_sound(self, sound_id: int, *, timeout: float = 1.0) -> object:
        return self._runtime._ensure_direct().play_sound(sound_id, timeout=timeout)

    def capture(self, *, timeout: float = 1.0) -> object:
        return self._runtime._ensure_direct().capture(timeout=timeout)

    def fire_infrared(self, *, lease_seconds: float = 0.12) -> None:
        self._runtime._ensure_direct().fire_infrared(lease_seconds=lease_seconds)

    def fire_gel(self) -> dict[str, object]:
        return self._runtime.fire_gel()

    def fire(self, fire_type: str = "infrared") -> object:
        if fire_type == "gel":
            return self.fire_gel()
        if fire_type == "infrared":
            return self.fire_infrared()
        raise ValueError("fire_type must be 'infrared' or 'gel'")

    def status(self) -> dict[str, object]:
        status = self._runtime.status()
        direct = self._runtime._direct_robot
        lab = self._runtime._lab_robot
        status.update(
            {
                "info": _to_jsonable(self.info),
                "odometry": _to_jsonable(
                    direct.odometry if direct is not None and direct.connected else None
                ),
                "gimbal_telemetry": _to_jsonable(
                    direct.gimbal_telemetry if direct is not None and direct.connected else None
                ),
                "lab_telemetry": _to_jsonable(
                    lab.bridge.last_telemetry.values
                    if lab is not None and lab.bridge.last_telemetry is not None
                    else None
                ),
            }
        )
        return status


class PersistentRuntime:
    """Keep one active Direct or Lab backend across MCP Python calls."""

    def __init__(
        self,
        config: RuntimeConfig,
        *,
        direct_factory: type[DirectRobot] = DirectRobot,
        lab_factory: type[LabRobot] = LabRobot,
    ) -> None:
        self.config = config
        self._direct_factory = direct_factory
        self._lab_factory = lab_factory
        self._direct_robot: DirectRobot | None = None
        self._lab_robot: LabRobot | None = None
        self._facade = RobotFacade(self)
        self._target: tuple[str, str] | None = None
        self._connection_generation = 0
        self._transition_state = "idle"
        self._transition_target: str | None = None
        self._last_transition: dict[str, object] | None = None

    def connect(self, robot_ip: str, appid: str) -> dict[str, object]:
        """Connect once and keep whichever backend is currently active."""

        requested_target = (robot_ip, appid)
        if self._connected():
            if requested_target != self._target:
                raise RuntimeError(
                    "the MCP worker is already connected to another S1; disconnect it first"
                )
            status = self.status()
            status["reused"] = True
            return status

        self.disconnect()
        self._target = requested_target
        self._ensure_direct()
        status = self.status()
        status["reused"] = False
        return status

    def status(self) -> dict[str, object]:
        direct_connected = self._direct_robot is not None and self._direct_robot.connected
        lab_connected = self._lab_robot is not None and self._lab_robot.connected
        backend = "direct" if direct_connected else "lab" if lab_connected else None
        return {
            "connected": direct_connected or lab_connected,
            "backend": backend,
            "robot_ip": self._target[0] if self._target else None,
            "appid": self._target[1] if self._target else None,
            "control_mode": bool(direct_connected and self._direct_robot.control_mode),
            "armed": bool(direct_connected and self._direct_robot.armed),
            "connection_generation": self._connection_generation,
            "transition": {
                "state": self._transition_state,
                "target": self._transition_target,
                "last": _to_jsonable(self._last_transition),
            },
        }

    def execute(
        self,
        *,
        code: str,
        output_dir: Path,
        robot_access: str,
        robot_ip: str | None,
        appid: str | None,
    ) -> dict[str, Any]:
        """Run one fresh Python namespace while retaining the active backend."""

        started = time.monotonic()
        output = StringIO()
        errors = StringIO()
        namespace: dict[str, object] = {}
        error: dict[str, str] | None = None
        cleanup_errors: list[str] = []
        execution_events: list[dict[str, object]] = []
        output_dir = output_dir.resolve()
        output_dir.mkdir(parents=True, exist_ok=True)
        previous_directory = Path.cwd()

        with redirect_stdout(output), redirect_stderr(errors):
            try:
                os.chdir(output_dir)
                facade: RobotFacade | None = None
                if robot_access == "auto":
                    if robot_ip is None or appid is None:
                        raise ValueError("robot_ip and appid are required to connect")
                    self.connect(robot_ip, appid)
                    facade = self._facade
                elif robot_access == "reuse":
                    facade = self._facade if self._connected() else None
                elif robot_access != "none":
                    raise ValueError("robot_access must be 'auto', 'reuse', or 'none'")

                def checkpoint(name: str, **data: object) -> dict[str, object]:
                    entry = {
                        "timestamp": datetime.now().astimezone().isoformat(timespec="milliseconds"),
                        "name": str(name),
                        "data": _to_jsonable(data),
                    }
                    execution_events.append(entry)
                    with (output_dir / "events.jsonl").open("a", encoding="utf-8") as stream:
                        stream.write(json.dumps(entry, ensure_ascii=False) + "\n")
                    return entry

                namespace = {
                    "__name__": "__hanppie_mcp__",
                    "output_dir": output_dir,
                    "result": None,
                    "robot": facade,
                    "checkpoint": checkpoint,
                    "save_frame": lambda frame, filename="capture.jpg": save_frame(
                        frame, filename, output_dir
                    ),
                    "sleep": time.sleep,
                    "time": time,
                }
                compiled = compile(code, "<hanppie-mcp>", "exec")
                exec(compiled, namespace, namespace)
            except BaseException as exc:  # User-code failures must be returned as data.
                error = {
                    "type": type(exc).__name__,
                    "message": str(exc),
                    "traceback": traceback.format_exc(),
                }
            finally:
                try:
                    os.chdir(previous_directory)
                except BaseException as exc:
                    cleanup_errors.append(f"chdir: {type(exc).__name__}: {exc}")
                cleanup_errors.extend(self._neutralize())

        if cleanup_errors:
            cleanup_errors.extend(self.disconnect())
        artifacts = [
            {"path": str(path), "size": path.stat().st_size}
            for path in sorted(output_dir.rglob("*"))
            if path.is_file()
        ]
        return {
            "ok": error is None and not cleanup_errors,
            "result": _to_jsonable(namespace.get("result")),
            "stdout": output.getvalue(),
            "stderr": errors.getvalue(),
            "error": error,
            "cleanup_errors": cleanup_errors,
            "artifacts": artifacts,
            "events": execution_events,
            "connection": self.status(),
            "duration_seconds": round(time.monotonic() - started, 6),
        }

    def fire_gel(self) -> dict[str, object]:
        """Fire one gel round through the verified persistent Lab Bridge."""

        robot = self._ensure_lab()
        if not robot.bridge.arm():
            raise RuntimeError("Lab bridge could not send arm")
        arm_sequence = robot.bridge.command_sequence
        armed = self._wait_lab_command(robot, "system.arm", arm_sequence)
        if armed.get("last_command_ok") is not True:
            raise RuntimeError(f"Lab bridge arm failed: {armed.get('last_command_error')}")
        if not robot.fire("gel"):
            raise RuntimeError("Lab bridge could not send gel fire")
        fire_sequence = robot.bridge.command_sequence
        telemetry = self._wait_lab_command(robot, "blaster.fire_gel", fire_sequence)
        if telemetry.get("last_command_ok") is not True:
            raise RuntimeError(f"gel fire failed: {telemetry.get('last_command_error')}")
        return {
            "fire_type": "gel",
            "count": 1,
            "acknowledged": True,
            "effects": {
                "muzzle_led": telemetry.get("last_fire_led_ok"),
                "shoot_sound": telemetry.get("last_fire_sound_ok"),
                "actuator": telemetry.get("last_fire_actuator_ok"),
            },
        }

    def disconnect(self) -> list[str]:
        """Close Direct and Lab resources, retaining no active App session."""

        errors = [*self._close_direct(), *self._close_lab()]
        self._target = None
        return errors

    def _connected(self) -> bool:
        return bool(
            (self._direct_robot is not None and self._direct_robot.connected)
            or (self._lab_robot is not None and self._lab_robot.connected)
        )

    def _active_robot(self) -> DirectRobot | LabRobot:
        if self._direct_robot is not None and self._direct_robot.connected:
            return self._direct_robot
        if self._lab_robot is not None and self._lab_robot.connected:
            return self._lab_robot
        return self._ensure_direct()

    def _ensure_direct(self) -> DirectRobot:
        if self._direct_robot is not None and self._direct_robot.connected:
            return self._direct_robot
        if self._target is None:
            raise RuntimeError("connect_robot must resolve an S1 target first")
        source = self._backend_name()
        started = self._begin_transition("direct")
        try:
            close_errors = self._close_lab()
            if close_errors:
                raise RuntimeError("; ".join(close_errors))
            robot = self._open_direct()
        except BaseException as exc:
            self._finish_transition(
                source=source,
                target="direct",
                started=started,
                attempts=1,
                error=exc,
            )
            raise
        self._finish_transition(
            source=source,
            target="direct",
            started=started,
            attempts=1,
        )
        return robot

    def _ensure_lab(self) -> LabRobot:
        if self._lab_robot is not None and self._lab_robot.connected:
            return self._lab_robot
        if self._target is None:
            raise RuntimeError("connect_robot must resolve an S1 target first")
        source = self._backend_name()
        started = self._begin_transition("lab")
        close_errors = self._close_direct()
        if close_errors:
            error = RuntimeError("; ".join(close_errors))
            self._finish_transition(
                source=source,
                target="lab",
                started=started,
                attempts=0,
                error=error,
            )
            raise error
        attempts = max(1, int(self.config.lab_transition_attempts))
        failures: list[BaseException] = []
        for attempt in range(1, attempts + 1):
            if attempt > 1:
                time.sleep(max(0.0, self.config.lab_retry_settle))
            try:
                robot = self._open_lab()
            except BaseException as exc:
                failures.append(exc)
                continue
            self._finish_transition(
                source=source,
                target="lab",
                started=started,
                attempts=attempt,
            )
            return robot

        rollback_backend: str | None = None
        rollback_error: BaseException | None = None
        if source == "direct":
            try:
                self._open_direct()
                rollback_backend = "direct"
            except BaseException as exc:
                rollback_error = exc
        last_error = failures[-1]
        self._finish_transition(
            source=source,
            target="lab",
            started=started,
            attempts=attempts,
            error=last_error,
            rollback_backend=rollback_backend,
            rollback_error=rollback_error,
        )
        message = f"backend transition {source or 'none'} -> lab failed after {attempts} attempts"
        if rollback_backend:
            message += f"; rolled back to {rollback_backend}"
        if rollback_error is not None:
            message += f"; rollback failed: {type(rollback_error).__name__}: {rollback_error}"
        raise RuntimeError(f"{message}: {type(last_error).__name__}: {last_error}") from last_error

    def _open_direct(self) -> DirectRobot:
        assert self._target is not None
        robot_ip, appid = self._target
        robot = self._direct_factory(
            robot_ip=robot_ip,
            appid=appid,
            local_ip=self.config.local_ip,
            debug=self.config.debug,
        )
        try:
            initialized = robot.initialize(
                conn_type="sta",
                proto_type="udp",
                timeout=self.config.connection_timeout,
            )
            if not initialized:
                raise RuntimeError("S1 App connection initialization failed")
            robot.enter_control_mode()
        except BaseException:
            try:
                robot.close()
            finally:
                raise
        self._direct_robot = robot
        self._connection_generation += 1
        return robot

    def _open_lab(self) -> LabRobot:
        assert self._target is not None
        robot_ip, appid = self._target
        robot = self._lab_factory(
            robot_ip=robot_ip,
            appid=appid,
            local_ip=self.config.local_ip,
            debug=self.config.debug,
        )
        try:
            initialized = robot.initialize(
                conn_type="sta",
                proto_type="udp",
                timeout=self.config.connection_timeout,
            )
            if not initialized:
                raise RuntimeError("S1 App connection initialization failed")
            robot.enter_lab()
            digest = robot.upload_lab_bridge()
            robot.start_lab_program(digest)
            robot.start_lab_bridge()
        except BaseException:
            try:
                robot.close()
            finally:
                raise
        self._lab_robot = robot
        self._connection_generation += 1
        return robot

    def _backend_name(self) -> str | None:
        if self._direct_robot is not None and self._direct_robot.connected:
            return "direct"
        if self._lab_robot is not None and self._lab_robot.connected:
            return "lab"
        return None

    def _begin_transition(self, target: str) -> float:
        self._transition_state = "switching"
        self._transition_target = target
        return time.monotonic()

    def _finish_transition(
        self,
        *,
        source: str | None,
        target: str,
        started: float,
        attempts: int,
        error: BaseException | None = None,
        rollback_backend: str | None = None,
        rollback_error: BaseException | None = None,
    ) -> None:
        self._transition_state = "idle"
        self._transition_target = None
        self._last_transition = {
            "timestamp": datetime.now().astimezone().isoformat(timespec="milliseconds"),
            "from": source,
            "to": target,
            "ok": error is None,
            "attempts": attempts,
            "duration_seconds": round(time.monotonic() - started, 6),
            "error": (None if error is None else f"{type(error).__name__}: {error}"),
            "rollback_backend": rollback_backend,
            "rollback_error": (
                None
                if rollback_error is None
                else f"{type(rollback_error).__name__}: {rollback_error}"
            ),
        }

    def _stop_chassis(self) -> None:
        direct = self._direct_robot
        if direct is not None and direct.connected:
            direct.chassis.stop()
            return
        lab = self._lab_robot
        if lab is not None and lab.connected:
            lab.chassis.stop()

    def _stop_gimbal(self) -> None:
        direct = self._direct_robot
        if direct is not None and direct.connected:
            direct.gimbal.stop()
            return
        lab = self._lab_robot
        if lab is not None and lab.connected:
            lab.gimbal.stop()

    def _wait_lab_command(
        self,
        robot: LabRobot,
        name: str,
        sequence: int,
    ) -> dict[str, object]:
        deadline = time.monotonic() + self.config.connection_timeout
        while time.monotonic() < deadline:
            telemetry = robot.bridge.last_telemetry
            if telemetry is not None:
                values = dict(telemetry.values)
                if (
                    int(values.get("rx_command_seq", 0) or 0) >= sequence
                    and values.get("last_command") == name
                ):
                    return values
            time.sleep(0.02)
        raise TimeoutError(f"waiting for {name} telemetry confirmation timed out")

    def _neutralize(self) -> list[str]:
        errors: list[str] = []
        if self._direct_robot is not None:
            try:
                self._direct_robot.disarm()
            except BaseException as exc:
                errors.append(f"direct disarm: {type(exc).__name__}: {exc}")
        if self._lab_robot is not None:
            try:
                self._lab_robot.bridge.stop_robot()
                self._lab_robot.bridge.disarm()
            except BaseException as exc:
                errors.append(f"lab disarm: {type(exc).__name__}: {exc}")
        return errors

    def _close_direct(self) -> list[str]:
        robot = self._direct_robot
        self._direct_robot = None
        if robot is None:
            return []
        errors: list[str] = []
        try:
            robot.disarm()
        except BaseException as exc:
            errors.append(f"direct disarm: {type(exc).__name__}: {exc}")
        try:
            robot.close()
        except BaseException as exc:
            errors.append(f"direct close: {type(exc).__name__}: {exc}")
        return errors

    def _close_lab(self) -> list[str]:
        robot = self._lab_robot
        self._lab_robot = None
        if robot is None:
            return []
        errors: list[str] = []
        try:
            robot.bridge.stop_robot()
            robot.bridge.disarm()
        except BaseException as exc:
            errors.append(f"lab disarm: {type(exc).__name__}: {exc}")
        try:
            robot.close()
        except BaseException as exc:
            errors.append(f"lab close: {type(exc).__name__}: {exc}")
        return errors


def save_frame(frame: object, filename: str, output_dir: Path) -> str:
    """Save an AV video frame below the current MCP artifact directory."""

    target = (output_dir / filename).resolve()
    if output_dir.resolve() not in target.parents:
        raise ValueError("filename must stay inside output_dir")
    target.parent.mkdir(parents=True, exist_ok=True)
    image = frame.to_ndarray(format="bgr24")
    import cv2

    if not cv2.imwrite(str(target), image):
        raise RuntimeError(f"could not write image to {target}")
    return str(target)


def _to_jsonable(value: object, *, depth: int = 0) -> object:
    if depth > 8:
        return "<maximum serialization depth reached>"
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, Enum):
        return _to_jsonable(value.value, depth=depth + 1)
    if isinstance(value, bytes):
        return {
            "type": "bytes",
            "length": len(value),
            "hex": value[:128].hex(),
            "truncated": len(value) > 128,
        }
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return _to_jsonable(dataclasses.asdict(value), depth=depth + 1)
    if isinstance(value, dict):
        return {str(key): _to_jsonable(item, depth=depth + 1) for key, item in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [_to_jsonable(item, depth=depth + 1) for item in value]
    try:
        json.dumps(value)
    except (TypeError, ValueError):
        representation = repr(value)
        return representation[:2000] + ("..." if len(representation) > 2000 else "")
    return value
