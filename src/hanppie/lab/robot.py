"""High-level lifecycle for the Hanppie S1 App/Lab backend."""

from __future__ import annotations

import ftplib
import threading
import time
from collections.abc import Callable

from hanppie.lab.app import AppConnection
from hanppie.lab.bridge import LabBridge, LabTelemetry
from hanppie.lab.camera import LabCamera
from hanppie.lab.config import DEFAULT_CONFIG, LabConfig
from hanppie.lab.program import LabProgramIdentity, build_lab_program, upload_lab_program

SUBSCRIPTION_FREQUENCIES = {1, 5, 10, 20, 50}


class Chassis:
    def __init__(self, robot: LabRobot) -> None:
        self._robot = robot
        self._attitude_callback: Callable[[object], None] | None = None

    def drive_speed(
        self, x: float = 0, y: float = 0, z: float = 0, timeout: float | None = None
    ) -> bool:
        accepted = self._robot.bridge.drive_speed(x, y, z)
        if accepted and timeout is not None:
            timer = threading.Timer(max(0.0, timeout), self.stop)
            timer.daemon = True
            timer.start()
        return accepted

    def stop(self) -> bool:
        return self._robot.bridge.stop_robot()

    def sub_attitude(
        self,
        *,
        freq: int = 5,
        callback: Callable[[object], None] | None = None,
    ) -> bool:
        if callback is None:
            return False
        if freq not in SUBSCRIPTION_FREQUENCIES:
            raise ValueError(f"freq must be one of {sorted(SUBSCRIPTION_FREQUENCIES)}")
        minimum_period = 1.0 / freq
        last_call = 0.0

        def limited(value: object) -> None:
            nonlocal last_call
            now = time.monotonic()
            if now - last_call >= minimum_period:
                last_call = now
                callback(value)

        self.unsub_attitude()
        self._attitude_callback = limited
        self._robot.on("attitude", limited)
        self._robot.request_telemetry()
        return True

    def unsub_attitude(self) -> bool:
        if self._attitude_callback is None:
            return False
        callback = self._attitude_callback
        self._attitude_callback = None
        return self._robot.off("attitude", callback)


class Gimbal:
    def __init__(self, robot: LabRobot) -> None:
        self._robot = robot

    def drive_speed(self, *, pitch_speed: float = 0, yaw_speed: float = 0) -> bool:
        return self._robot.bridge.gimbal_drive_speed(pitch_speed, yaw_speed)

    def stop(self) -> bool:
        return self._robot.bridge.call("gimbal", "stop")

    def recenter(self) -> bool:
        return self._robot.bridge.call("gimbal", "recenter")


class LabRobot:
    def __init__(
        self,
        *,
        robot_ip: str,
        appid: str,
        local_ip: str = "0.0.0.0",
        config: LabConfig = DEFAULT_CONFIG,
        debug: bool = False,
    ) -> None:
        self.robot_ip = robot_ip
        self.appid = appid
        self.local_ip = local_ip
        self.config = config
        self.debug = debug
        self.connection = AppConnection(robot_ip, appid, local_ip=local_ip, debug=debug)
        # Kept as an explicit transport alias for callers that need raw App data.
        self.base = self.connection
        self.bridge = self._new_bridge()
        self.camera = LabCamera(self.connection)
        self.chassis = Chassis(self)
        self.gimbal = Gimbal(self)
        self._callbacks: dict[str, list[Callable[[object], None]]] = {}
        self._lab_entered = False
        self._program_started = False
        self._bridge_started = False
        self._keepalive_stop = threading.Event()
        self._keepalive_thread: threading.Thread | None = None
        self._program_digest = ""
        self._program_identity: LabProgramIdentity | None = None
        self._program_registered = False

    def _new_bridge(self) -> LabBridge:
        bridge = LabBridge(
            self.robot_ip,
            local_ip=self.local_ip,
            config=self.config,
            debug=self.debug,
        )
        bridge.on_telemetry(self._handle_telemetry)
        return bridge

    @property
    def connected(self) -> bool:
        return self.connection.connected

    @property
    def info(self):
        return self.connection.info

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
            raise ValueError("the S1 App/Lab backend requires conn_type='sta' and proto_type='udp'")
        initialized = self.connection.initialize(timeout=timeout)
        self.robot_ip = self.connection.robot_ip
        self.bridge.robot_ip = self.robot_ip
        return initialized

    def close(self) -> None:
        self.camera.close()
        self.stop_lab_bridge()
        if self._program_started:
            try:
                self.stop_lab_program()
            except OSError:
                pass
        if self._lab_entered:
            try:
                self.exit_lab()
            except OSError:
                pass
        self._stop_keepalive()
        self.connection.close()

    def send_duss(self, *args, **kwargs) -> int:
        return self.connection.send_duss(*args, **kwargs)

    def on(self, event: str, callback: Callable[[object], None]) -> Callable[[object], None]:
        self._callbacks.setdefault(event, []).append(callback)
        return callback

    def off(self, event: str, callback: Callable[[object], None] | None = None) -> bool:
        callbacks = self._callbacks.get(event, [])
        if not callbacks:
            return False
        if callback is None:
            callbacks.clear()
            return True
        try:
            callbacks.remove(callback)
        except ValueError:
            return False
        return True

    def _emit(self, event: str, value: object) -> None:
        for callback in tuple(self._callbacks.get(event, ())):
            try:
                callback(value)
            except Exception as error:  # pragma: no cover - application callback
                if self.debug:
                    print(f"[lab-callback] {event}: {error}")

    def enter_lab(self) -> None:
        if not self.connected:
            raise RuntimeError("App connection is not initialized")
        self._stop_keepalive()
        self.connection.suspend_idle_keepalive = True
        self.connection.send_duss(0x02, 0x09, 0x00, 0x3F, 0x04, bytes.fromhex("020302"))
        self._start_keepalive()
        time.sleep(self.config.lab_mode_settle)
        parameters = bytes.fromhex(
            "05000000ea03000000000000ef0300000a000000f003000000000000"
            "f1030000b80b0000f2030000dc0500000000000000000000"
        )
        self.connection.send_duss(0x02, 0x09, 0x40, 0x3F, 0x09, parameters)
        time.sleep(self.config.lab_mode_settle)
        self.connection.send_duss(0x02, 0x09, 0x40, 0x3F, 0x57)
        self._lab_entered = True

    def exit_lab(self) -> None:
        self._stop_keepalive()
        self.connection.send_duss(0x02, 0x09, 0x00, 0x3F, 0x04, bytes.fromhex("000300"))
        self.connection.send_duss(0x02, 0x09, 0x40, 0x3F, 0x77, bytes.fromhex("010300"))
        time.sleep(0.065)
        self.connection.send_control()
        self.connection.suspend_idle_keepalive = False
        self._lab_entered = False

    def _start_keepalive(self) -> None:
        if self._keepalive_thread is not None and self._keepalive_thread.is_alive():
            return
        self._keepalive_stop.clear()
        self._keepalive_thread = threading.Thread(
            target=self._keepalive_loop, name="hanppie-lab-keepalive", daemon=True
        )
        self._keepalive_thread.start()

    def _stop_keepalive(self) -> None:
        self._keepalive_stop.set()
        thread = self._keepalive_thread
        self._keepalive_thread = None
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=1.0)

    def _keepalive_loop(self) -> None:
        while not self._keepalive_stop.wait(0.8):
            if not self.connected:
                return
            self.connection.send_duss(0x02, 0x09, 0x00, 0x3F, 0x04, bytes.fromhex("020302"))

    def send_lab_metadata(self, guid: str, sign: str, marker: int) -> None:
        payload = bytes((marker & 0xFF,)) + (guid + sign).encode("ascii")
        self.connection.send_duss(0x02, 0xA9, 0x40, 0x3F, 0xA3, payload)

    def send_lab_guid_metadata(self, guid: str, marker: int) -> None:
        payload = bytes((marker & 0xFF,)) + guid.encode("ascii") + b"\x00\x00"
        self.connection.send_duss(0x02, 0xA9, 0x40, 0x3F, 0xA3, payload)

    def send_lab_upload_size(self, byte_count: int) -> None:
        payload = b"\x01\x00\x04\x00" + int(byte_count).to_bytes(4, "little")
        self.connection.send_duss(0x02, 0xA9, 0x40, 0x3F, 0xA1, payload)

    def upload_program(self, python_source: str | None = None) -> str:
        dsp, identity = build_lab_program(python_source, config=self.config)
        return self.upload_prepared_program(dsp, identity)

    def upload_prepared_program(self, dsp: str, identity: LabProgramIdentity) -> str:
        if not self._lab_entered:
            raise RuntimeError("enter_lab() must be called before uploading a program")
        self._program_identity = identity
        self._program_registered = False
        self.connection.send_duss(0x02, 0x09, 0x40, 0x3F, 0x4C, b"\x00")
        time.sleep(0.02)
        self.send_lab_metadata(identity.guid, identity.sign, identity.full_marker)
        time.sleep(0.02)
        self.send_lab_guid_metadata(identity.guid, identity.guid_marker)
        time.sleep(0.02)
        self.send_lab_upload_size(len(dsp.encode("utf-8")))
        time.sleep(0.02)
        deadline = time.monotonic() + self.config.upload_retry_timeout
        while True:
            try:
                self._program_digest = upload_lab_program(self.robot_ip, dsp)
                break
            except (OSError, EOFError, ftplib.Error):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(self.config.bridge_probe_interval)
        time.sleep(self.config.upload_settle)
        return self._program_digest

    def upload_lab_bridge(self) -> str:
        return self.upload_program()

    def start_lab_program(self, digest: str | None = None) -> None:
        if not self._lab_entered:
            raise RuntimeError("enter_lab() must be called before starting a program")
        if digest:
            self._program_digest = digest
        if not self._program_digest:
            self.upload_lab_bridge()
        if not self._program_registered and self._program_identity is None:
            raise RuntimeError("Lab program identity is missing")
        self._stop_keepalive()
        if self._program_registered:
            self.connection.send_duss(0x02, 0xC9, 0x80, 0x3F, 0xAB, b"\x01")
        else:
            self.connection.send_duss(
                0x02,
                0xA9,
                0x40,
                0x3F,
                0xA2,
                b"\x01\x00" + bytes.fromhex(self._program_digest),
            )
            time.sleep(0.02)
            assert self._program_identity is not None
            self.send_lab_metadata(self._program_identity.guid, self._program_identity.sign, 0x52)
            time.sleep(0.02)
            self.connection.send_duss(0x42, 0xC9, 0x80, 0x3F, 0xBA, b"\x00")
            time.sleep(0.02)
            self.connection.send_duss(0x02, 0xC9, 0x80, 0x3F, 0xAB, b"\x01")
            self._program_registered = True
        self._program_started = True
        time.sleep(self.config.program_start_settle)

    def stop_lab_program(self) -> None:
        if self._program_identity is not None:
            self.send_lab_metadata(self._program_identity.guid, self._program_identity.sign, 0x55)
            time.sleep(0.052)
        self.connection.send_duss(0x42, 0xC9, 0x80, 0x3F, 0xBA, b"\x00")
        self._program_started = False
        self._program_registered = False
        if self._lab_entered:
            self._start_keepalive()

    def start_lab_bridge(self) -> None:
        if self._bridge_started:
            return
        if not self._program_started:
            raise RuntimeError("start_lab_program() must be called before starting the Bridge")
        self.bridge.start()
        try:
            self.bridge.prime(count=1, interval=0)
            self.request_telemetry()
            if not self.bridge.wait_for_telemetry(self.config.bridge_ready_timeout):
                raise TimeoutError("Lab program did not return matching-session telemetry")
            if not self.bridge.arm():
                raise RuntimeError("Lab bridge could not arm the current session")
            self.bridge.stop_robot()
        except Exception:
            self.bridge.close()
            raise
        self._bridge_started = True

    def stop_lab_bridge(self) -> None:
        if not self._bridge_started and not any(self.bridge.worker_threads.values()):
            return
        try:
            self.bridge.disarm()
        except OSError:
            pass
        self.bridge.close()
        self._bridge_started = False
        self.bridge = self._new_bridge()

    def request_telemetry(self) -> bool:
        return self.bridge.call(
            "system",
            "set_telemetry",
            fields=["x", "y", "yaw", "gimbal_yaw", "gimbal_pitch"],
        )

    def call(self, module: str, method: str, **params: object) -> bool:
        return self.bridge.call(module, method, **params)

    def set_led(
        self,
        *,
        component: str = "all",
        red: int = 255,
        green: int = 255,
        blue: int = 255,
        effect: str = "on",
    ) -> bool:
        return self.bridge.set_led(
            component=component,
            red=red,
            green=green,
            blue=blue,
            effect=effect,
        )

    def fire(self, fire_type: str = "infrared") -> bool:
        return self.bridge.fire(fire_type)

    def _handle_telemetry(self, telemetry: LabTelemetry) -> None:
        values = telemetry.values
        self._emit("telemetry", telemetry)
        if "yaw" in values:
            self._emit("attitude", (values.get("yaw"), None, None))
        if "x" in values or "y" in values:
            self._emit("position", (values.get("x"), values.get("y"), values.get("yaw")))
        if "gimbal_pitch" in values or "gimbal_yaw" in values:
            self._emit(
                "gimbal_angle",
                (values.get("gimbal_pitch"), values.get("gimbal_yaw")),
            )
