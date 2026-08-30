"""S1 App-compatible UDP session used to reach the native Lab subsystem."""

from __future__ import annotations

import select
import socket
import threading
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass

from hanppie.lab import protocol


@dataclass(frozen=True)
class AppConnectionInfo:
    ip: str
    appid: str
    state: str
    mac: str = ""


def open_udp(bind_ip: str, port: int, *, broadcast: bool = False) -> socket.socket:
    connection = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    connection.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if broadcast:
        connection.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    connection.bind((bind_ip, port))
    connection.setblocking(False)
    return connection


class AppConnection:
    """Own the AppID claim, outer session and DUSS receive loop."""

    def __init__(
        self,
        robot_ip: str,
        appid: str,
        *,
        local_ip: str = "0.0.0.0",
        local_port: int = protocol.LOCAL_CONTROL_PORT,
        debug: bool = False,
    ) -> None:
        self.robot_ip = robot_ip.strip()
        self.appid = protocol.normalize_appid(appid)
        self.local_ip = local_ip
        self.local_port = int(local_port)
        self.debug = debug
        self.info = AppConnectionInfo(self.robot_ip, self.appid, "disconnected")
        self.envelope = protocol.AppEnvelope()
        self.socket: socket.socket | None = None
        self.connected = False
        self.suspend_idle_keepalive = False
        self._sequence = protocol.INITIAL_DUSS_SEQUENCE
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._tx_lock = threading.RLock()
        self._state_lock = threading.RLock()
        self._callbacks: dict[str, list[Callable[[object], None]]] = {}
        self._battery: int | None = None
        self._control_payload = protocol.NEUTRAL_CONTROL
        self._control_deadline: float | None = None
        self._periodic_duss: dict[
            str, tuple[tuple[int, int, int, int, int, bytes], float | None]
        ] = {}
        self._mode_keepalive = bytes.fromhex("000300")
        self._send_sdk_ready = True
        self._frame_condition = threading.Condition()
        self._recent_frames: deque[protocol.DussFrame] = deque(maxlen=256)

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
                self._log(f"callback {event!r} failed: {error}")

    def initialize(self, *, timeout: float = 20.0) -> bool:
        if self.connected:
            return True
        self._claim_appid(timeout)
        time.sleep(0.2)
        self.socket = open_udp(self.local_ip, self.local_port)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
        self._open_outer_session(timeout=min(timeout, 5.0))
        self._send_connection_setup()
        self.connected = True
        self._stop.clear()
        self._thread = threading.Thread(target=self._receive_loop, name="hanppie-app", daemon=True)
        self._thread.start()
        return True

    def close(self) -> None:
        self._stop.set()
        thread = self._thread
        self._thread = None
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=1.0)
        if self.socket is not None:
            self.socket.close()
        self.socket = None
        self.connected = False
        self.info = AppConnectionInfo(self.robot_ip, self.appid, "disconnected", self.info.mac)
        with self._state_lock:
            self._control_payload = protocol.NEUTRAL_CONTROL
            self._control_deadline = None
            self._periodic_duss.clear()
            self._mode_keepalive = bytes.fromhex("000300")
            self._send_sdk_ready = True
        with self._frame_condition:
            self._recent_frames.clear()
            self._frame_condition.notify_all()

    def _claim_appid(self, timeout: float) -> None:
        claim = self.appid.encode("ascii")
        connection = open_udp("0.0.0.0", protocol.APP_PORT, broadcast=True)
        deadline = time.monotonic() + timeout
        broadcast_deadline = min(deadline, time.monotonic() + 4.0)
        last_claim = 0.0
        saw_broadcast = False
        try:
            if self.robot_ip:
                connection.sendto(claim, (self.robot_ip, protocol.ROBOT_APP_PORT))
                last_claim = time.monotonic()
            while time.monotonic() < deadline:
                now = time.monotonic()
                if self.robot_ip and not saw_broadcast and now >= broadcast_deadline:
                    self.info = AppConnectionInfo(self.robot_ip, self.appid, "reconnect")
                    return
                if self.robot_ip and saw_broadcast and now - last_claim >= 1.0:
                    connection.sendto(claim, (self.robot_ip, protocol.ROBOT_APP_PORT))
                    last_claim = now
                readable, _, _ = select.select([connection], [], [], 0.2)
                for ready in readable:
                    data, address = ready.recvfrom(65535)
                    broadcast = protocol.parse_robot_broadcast(data)
                    if broadcast is None:
                        continue
                    saw_broadcast = True
                    self.robot_ip = broadcast.robot_ip or address[0]
                    self.info = AppConnectionInfo(
                        self.robot_ip,
                        broadcast.appid,
                        "pairing" if broadcast.pairing else "idle",
                        broadcast.robot_mac,
                    )
                    if broadcast.appid == self.appid:
                        connection.sendto(claim, (self.robot_ip, protocol.ROBOT_APP_PORT))
                        return
                    if broadcast.appid == "00000000" or broadcast.pairing:
                        connection.sendto(claim, (self.robot_ip, protocol.ROBOT_APP_PORT))
                        last_claim = time.monotonic()
            raise TimeoutError(f"AppID claim timed out for {self.appid}")
        finally:
            connection.close()

    def _open_outer_session(self, timeout: float) -> None:
        if self.socket is None:
            raise RuntimeError("control socket is not open")
        target = (self.robot_ip, protocol.ROBOT_CONTROL_PORT)
        deadline = time.monotonic() + timeout
        last_send = 0.0
        while time.monotonic() < deadline:
            now = time.monotonic()
            if now - last_send >= 0.2:
                self.socket.sendto(self.envelope.preconnect(), target)
                last_send = now
            readable, _, _ = select.select([self.socket], [], [], 0.03)
            for ready in readable:
                data, _ = ready.recvfrom(65535)
                if len(data) >= 4 and data[2:4] == self.envelope.session:
                    self.envelope.observe(data)
                    return
                if len(data) >= 4:
                    self.envelope.session = protocol.next_session(data[2:4])
                    last_send = 0.0
        self._log("outer session did not acknowledge preconnect; continuing")

    def _next_sequence(self) -> int:
        with self._tx_lock:
            current = self._sequence
            self._sequence = (self._sequence + 1) & 0xFFFF
            return current

    def _send(self, packet: bytes) -> None:
        if self.socket is None:
            raise RuntimeError("App connection is not initialized")
        with self._tx_lock:
            self.socket.sendto(packet, (self.robot_ip, protocol.ROBOT_CONTROL_PORT))

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
        sequence = self._next_sequence()
        duss = protocol.build_duss(sender, receiver, attr, cmdset, cmdid, payload, sequence)
        packet = self.envelope.wrap_direct(duss, flags or bytes((attr, 0)))
        self._send(packet)
        return sequence

    def send_control(self, payload: bytes = protocol.NEUTRAL_CONTROL) -> int:
        sequence = self._next_sequence()
        duss = protocol.build_duss(0x02, 0x09, 0x00, 0x01, 0x04, payload, sequence)
        self._send(self.envelope.wrap_control(duss))
        return sequence

    def set_control_payload(self, payload: bytes, *, lease_seconds: float | None = None) -> None:
        """Set the control-channel payload repeated by the receive loop."""

        if not payload:
            raise ValueError("control payload must not be empty")
        if lease_seconds is not None and lease_seconds <= 0:
            raise ValueError("lease_seconds must be positive")
        with self._state_lock:
            self._control_payload = bytes(payload)
            self._control_deadline = (
                None if lease_seconds is None else time.monotonic() + lease_seconds
            )

    def set_periodic_duss(
        self,
        name: str,
        command: tuple[int, int, int, int, int, bytes] | None,
        *,
        lease_seconds: float | None = None,
    ) -> None:
        """Add, replace, or remove one DUSS command sent with every control tick."""

        if lease_seconds is not None and lease_seconds <= 0:
            raise ValueError("lease_seconds must be positive")
        with self._state_lock:
            if command is None:
                self._periodic_duss.pop(name, None)
            else:
                deadline = None if lease_seconds is None else time.monotonic() + lease_seconds
                self._periodic_duss[name] = (command, deadline)

    def configure_mode_keepalive(self, payload: bytes, *, send_sdk_ready: bool) -> None:
        """Configure the one-second App mode keepalive used by the active backend."""

        with self._state_lock:
            self._mode_keepalive = bytes(payload)
            self._send_sdk_ready = bool(send_sdk_ready)

    def _control_tick_state(
        self, now: float
    ) -> tuple[bytes, tuple[tuple[int, int, int, int, int, bytes], ...]]:
        """Expire command leases and snapshot one atomic 50 Hz control tick."""

        with self._state_lock:
            if self._control_deadline is not None and now >= self._control_deadline:
                self._control_payload = protocol.NEUTRAL_CONTROL
                self._control_deadline = None
            expired = [
                name
                for name, (_, deadline) in self._periodic_duss.items()
                if deadline is not None and now >= deadline
            ]
            for name in expired:
                self._periodic_duss.pop(name, None)
            return self._control_payload, tuple(
                command for command, _ in self._periodic_duss.values()
            )

    def wait_for_duss(
        self,
        sequence: int,
        *,
        cmdset: int | None = None,
        cmdid: int | None = None,
        ack: bool | None = None,
        timeout: float = 1.0,
    ) -> protocol.DussFrame | None:
        """Wait for a matching received DUSS frame without losing an early response."""

        def matches(frame: protocol.DussFrame) -> bool:
            return (
                frame.sequence == (sequence & 0xFFFF)
                and (cmdset is None or frame.cmdset == cmdset)
                and (cmdid is None or frame.cmdid == cmdid)
                and (ack is None or bool(frame.attr & 0x80) is ack)
            )

        deadline = time.monotonic() + max(0.0, timeout)
        with self._frame_condition:
            while True:
                for frame in reversed(self._recent_frames):
                    if matches(frame):
                        return frame
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not self.connected:
                    return None
                self._frame_condition.wait(remaining)

    def _send_connection_setup(self) -> None:
        for (
            kind,
            sender,
            receiver,
            attr,
            cmdset,
            cmdid,
            payload_hex,
            flags_hex,
        ) in protocol.APP_CONNECTION_SETUP:
            payload = bytes.fromhex(payload_hex)
            if kind == "control":
                self.send_control(payload)
                time.sleep(0.02)
            else:
                self.send_duss(
                    sender,
                    receiver,
                    attr,
                    cmdset,
                    cmdid,
                    payload,
                    flags=bytes.fromhex(flags_hex),
                )
                time.sleep(0.006)

    def _receive_loop(self) -> None:
        next_control = time.monotonic()
        next_keepalive = time.monotonic()
        while not self._stop.is_set():
            if self.socket is not None:
                readable, _, _ = select.select([self.socket], [], [], 0.005)
                for ready in readable:
                    try:
                        data, _ = ready.recvfrom(65535)
                    except (BlockingIOError, OSError):
                        continue
                    self._handle_packet(data)
            now = time.monotonic()
            if now >= next_control:
                try:
                    control_payload, periodic_duss = self._control_tick_state(now)
                    self.send_control(control_payload)
                    for sender, receiver, attr, cmdset, cmdid, payload in periodic_duss:
                        self.send_duss(sender, receiver, attr, cmdset, cmdid, payload)
                except OSError:
                    if not self._stop.is_set():
                        raise
                next_control = now + 0.02
            if now >= next_keepalive and not self.suspend_idle_keepalive:
                with self._state_lock:
                    mode_keepalive = self._mode_keepalive
                    send_sdk_ready = self._send_sdk_ready
                self.send_duss(0x02, 0x09, 0x00, 0x3F, 0x04, mode_keepalive)
                if send_sdk_ready:
                    self.send_duss(0x02, 0x07, 0x40, 0x07, 0x17)
                next_keepalive = now + 1.0

    def _handle_packet(self, data: bytes) -> None:
        if protocol.is_video_packet(data, self.envelope.session):
            self._emit("video", data[20:])
            return
        self.envelope.observe(data)
        # The scanner already finds DUSS frames after either a 20-byte direct
        # header or a 34-byte control/stream header. Parsing an inner slice a
        # second time would dispatch the same frame twice.
        frames = protocol.parse_duss_frames(data)
        for frame in frames:
            if not frame.valid:
                continue
            with self._frame_condition:
                self._recent_frames.append(frame)
                self._frame_condition.notify_all()
            self._emit("duss", frame)
            if frame.cmdset == 0x48 and frame.cmdid == 0x08 and len(frame.payload) == 62:
                self._battery = frame.payload[10]
            elif frame.cmdset == 0x3F and frame.cmdid == 0x1D:
                self._emit("audio", frame.payload)

    def get_battery(self) -> int | None:
        return self._battery

    def _log(self, message: str) -> None:
        if self.debug:
            print(f"[app] {message}")
