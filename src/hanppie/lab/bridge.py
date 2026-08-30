"""Session-scoped host side of Hanppie's Lab UDP bridge."""

from __future__ import annotations

import json
import secrets
import socket
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

from hanppie.lab.config import DEFAULT_CONFIG, LabConfig


@dataclass(frozen=True)
class LabTelemetry:
    sequence: int
    timestamp_ms: int
    values: dict[str, object]


class LabBridge:
    def __init__(
        self,
        robot_ip: str,
        *,
        local_ip: str = "0.0.0.0",
        config: LabConfig = DEFAULT_CONFIG,
        debug: bool = False,
    ) -> None:
        self.robot_ip = robot_ip
        self.local_ip = local_ip
        self.config = config
        self.debug = debug
        self.session_id = secrets.randbelow(0x7FFFFFFE) + 1
        self.last_telemetry: LabTelemetry | None = None
        self._callbacks: list[Callable[[LabTelemetry], None]] = []
        self._tx_socket: socket.socket | None = None
        self._rx_socket: socket.socket | None = None
        self._rx_thread: threading.Thread | None = None
        self._motion_thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._ready = threading.Event()
        self._lock = threading.RLock()
        self._command_sequence = 0
        self._motion: dict[str, float] | None = None

    @property
    def worker_threads(self) -> dict[str, int | None]:
        return {
            "rx": self._rx_thread.native_id if self._rx_thread is not None else None,
            "motion": (self._motion_thread.native_id if self._motion_thread is not None else None),
        }

    @property
    def command_sequence(self) -> int:
        return self._command_sequence

    def start(self) -> None:
        if self._rx_thread is not None:
            return
        self._stop.clear()
        self._ready.clear()
        self._tx_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._rx_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._rx_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            self._rx_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
        except (AttributeError, OSError):
            pass
        self._rx_socket.bind((self.local_ip, self.config.telemetry_port))
        self._rx_socket.settimeout(0.1)
        self._rx_thread = threading.Thread(
            target=self._receive_loop, name="hanppie-lab-rx", daemon=True
        )
        self._motion_thread = threading.Thread(
            target=self._motion_loop, name="hanppie-lab-motion", daemon=True
        )
        self._rx_thread.start()
        self._motion_thread.start()

    def close(self) -> None:
        if self._tx_socket is not None:
            try:
                self.stop_robot()
            except OSError:
                pass
        self._stop.set()
        for connection in (self._rx_socket, self._tx_socket):
            if connection is not None:
                connection.close()
        for thread in (self._rx_thread, self._motion_thread):
            if thread is not None and thread is not threading.current_thread():
                thread.join(timeout=1.0)
        self._rx_socket = None
        self._tx_socket = None
        self._rx_thread = None
        self._motion_thread = None
        self._ready.clear()
        with self._lock:
            self._motion = None

    def on_telemetry(self, callback: Callable[[LabTelemetry], None]) -> None:
        if callback not in self._callbacks:
            self._callbacks.append(callback)

    def wait_for_telemetry(self, timeout: float | None = None) -> bool:
        return self._ready.wait(timeout)

    def _next_sequence(self) -> int:
        self._command_sequence = (self._command_sequence + 1) & 0x7FFFFFFF
        if self._command_sequence == 0:
            self._command_sequence = 1
        return self._command_sequence

    def send(self, **command: object) -> bool:
        with self._lock:
            if self._tx_socket is None:
                return False
            command.setdefault("command_seq", self._next_sequence())
            command.setdefault("session_id", self.session_id)
            payload = json.dumps(command, separators=(",", ":")).encode("utf-8")
            self._tx_socket.sendto(payload, (self.robot_ip, self.config.control_port))
        if self.debug:
            print(f"[lab-tx] {payload.decode('utf-8')}")
        return True

    def prime(self, count: int = 3, interval: float = 0.02) -> None:
        for _ in range(max(1, int(count))):
            self.send(stop=True)
            time.sleep(max(0.0, interval))

    def call(self, module: str, method: str, **params: object) -> bool:
        with self._lock:
            if module == "chassis":
                self._clear_motion_fields("x", "y", "z")
            elif module == "gimbal":
                self._clear_motion_fields("gimbal_pitch", "gimbal_yaw")
        return self.send(module=module, method=method, params=params)

    def _clear_motion_fields(self, *fields: str) -> None:
        if self._motion is None:
            return
        for field in fields:
            self._motion.pop(field, None)
        if not self._motion:
            self._motion = None

    def arm(self) -> bool:
        return self.call("system", "arm")

    def disarm(self) -> bool:
        return self.call("system", "disarm")

    def drive_speed(self, x: float = 0, y: float = 0, z: float = 0) -> bool:
        with self._lock:
            self._motion = {"x": float(x), "y": float(y), "z": float(z)}
        return self.send(**self._motion)

    def gimbal_drive_speed(self, pitch_speed: float = 0, yaw_speed: float = 0) -> bool:
        with self._lock:
            motion = dict(self._motion or {})
            motion.update(
                gimbal_pitch=float(pitch_speed),
                gimbal_yaw=float(yaw_speed),
            )
            self._motion = motion
        return self.send(**motion)

    def stop_robot(self) -> bool:
        with self._lock:
            self._motion = None
        return self.send(stop=True)

    def set_led(
        self,
        *,
        component: str = "all",
        red: int = 255,
        green: int = 255,
        blue: int = 255,
        effect: str = "on",
    ) -> bool:
        return self.send(
            led=True,
            component=component,
            red=int(red),
            green=int(green),
            blue=int(blue),
            effect=effect,
        )

    def fire(self, fire_type: str = "infrared") -> bool:
        return self.send(fire=True, fire_type=fire_type)

    def _motion_loop(self) -> None:
        while not self._stop.wait(0.1):
            with self._lock:
                motion = dict(self._motion) if self._motion is not None else None
            if motion is not None:
                try:
                    self.send(**motion)
                except OSError:
                    if not self._stop.is_set():
                        raise

    def _receive_loop(self) -> None:
        while not self._stop.is_set():
            if self._rx_socket is None:
                return
            try:
                payload, address = self._rx_socket.recvfrom(4096)
            except TimeoutError:
                continue
            except OSError:
                return
            if address[0] != self.robot_ip:
                continue
            try:
                values = json.loads(payload.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                continue
            if not isinstance(values, dict) or values.get("session_id") != self.session_id:
                continue
            telemetry = LabTelemetry(
                sequence=int(values.get("sequence", 0) or 0),
                timestamp_ms=int(values.get("time_ms", 0) or 0),
                values=values,
            )
            self.last_telemetry = telemetry
            self._ready.set()
            for callback in tuple(self._callbacks):
                try:
                    callback(telemetry)
                except Exception as error:  # pragma: no cover - application callback
                    if self.debug:
                        print(f"[lab-callback] {error}")
