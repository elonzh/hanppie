from __future__ import annotations

import pytest

from hanppie import protocol
from hanppie.connection import AppConnection, open_udp
from hanppie.protocol import build_duss

__all__: list[str] = []


class FakeSocket:
    def __init__(self, incoming: list[tuple[bytes, tuple[str, int]]] | None = None) -> None:
        self.incoming = list(incoming or [])
        self.sent: list[tuple[bytes, tuple[str, int]]] = []
        self.closed = False
        self.bound: tuple[str, int] | None = None
        self.timeout: float | None = None

    def sendto(self, payload: bytes, target: tuple[str, int]) -> None:
        self.sent.append((payload, target))

    def setsockopt(self, *_args: object) -> None:
        return None

    def bind(self, target: tuple[str, int]) -> None:
        self.bound = target

    def settimeout(self, timeout: float) -> None:
        self.timeout = timeout

    def setblocking(self, _blocking: bool) -> None:
        return None

    def recvfrom(self, _size: int) -> tuple[bytes, tuple[str, int]]:
        if not self.incoming:
            raise OSError("closed")
        return self.incoming.pop(0)

    def close(self) -> None:
        self.closed = True


def test_open_udp(monkeypatch: pytest.MonkeyPatch) -> None:
    fake = FakeSocket()
    monkeypatch.setattr("hanppie.connection.socket.socket", lambda *_args: fake)
    sock = open_udp("127.0.0.1", 12345, broadcast=True)
    assert sock is fake
    assert fake.bound == ("127.0.0.1", 12345)


def test_app_connection_builds_setup_and_dispatches_video_and_battery(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = AppConnection("192.0.2.10", "b6359877")
    connection.socket = FakeSocket()  # type: ignore[assignment]
    monkeypatch.setattr("hanppie.connection.time.sleep", lambda _seconds: None)

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

    audio_events: list[object] = []
    connection.on("audio", audio_events.append)
    audio = build_duss(0x01, 0x02, 0x80, 0x3F, 0x1D, b"opus", 2)
    connection._handle_packet(audio)
    assert audio_events == [b"opus"]


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
    monkeypatch.setattr("hanppie.connection.open_udp", lambda *_args, **_kwargs: control_socket)
    monkeypatch.setattr("hanppie.connection.threading.Thread", Thread)
    monkeypatch.setattr("hanppie.connection.time.sleep", lambda _seconds: None)

    assert connection.initialize(timeout=2)
    assert connection.initialize(timeout=2)
    assert connection.connected
    connection.close()
    assert not connection.connected
    assert control_socket.closed
    with pytest.raises(RuntimeError, match="not initialized"):
        connection._send(b"packet")


def test_app_connection_waits_for_early_ack_and_expires_control_leases() -> None:
    connection = AppConnection("192.0.2.10", "b6359877")
    ack = build_duss(0x09, 0x02, 0xC0, 0x3F, 0x33, b"\x00", 123)
    connection._handle_packet(ack)

    received = connection.wait_for_duss(
        123,
        cmdset=0x3F,
        cmdid=0x33,
        ack=True,
        timeout=0,
    )
    assert received is not None
    assert received.payload == b"\x00"

    connection.set_control_payload(b"motion", lease_seconds=1)
    connection.set_periodic_duss(
        "gimbal",
        (2, 4, 0, 4, 0x69, b"moving"),
        lease_seconds=1,
    )
    assert connection._control_deadline is not None
    active_payload, periodic = connection._control_tick_state(connection._control_deadline - 0.1)
    assert active_payload == b"motion"
    assert periodic

    neutral_payload, periodic = connection._control_tick_state(connection._control_deadline + 0.1)
    assert neutral_payload == protocol.NEUTRAL_CONTROL
    assert periodic == ()
