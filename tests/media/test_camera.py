from __future__ import annotations

from collections.abc import Callable

import pytest

from hanppie.media.camera import Camera

__all__: list[str] = []


def test_camera_emits_app_stream_commands_and_queue_strategy(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[object, ...]] = []

    class Connection:
        def __init__(self) -> None:
            self.callbacks: dict[str, Callable[[object], None]] = {}

        def on(self, event: str, callback: Callable[[object], None]) -> None:
            self.callbacks[event] = callback

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

    monkeypatch.setattr("hanppie.media.camera.threading.Thread", Thread)
    connection = Connection()
    camera = Camera(connection)  # type: ignore[arg-type]

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
    camera._audio_decoder = type("Decoder", (), {"decode": lambda _self, _packet: b"pcm"})()
    assert camera.start_audio_stream()
    assert calls[-1][3:6] == (0x3F, 0x1E, b"\x01")
    connection.callbacks["audio"](b"opus")
    assert camera.read_audio_opus(timeout=0) == b"opus"
    connection.callbacks["audio"](b"opus")
    assert camera.read_audio_frame(timeout=0) == b"pcm"
    assert camera.stop_audio_stream()
    camera.close()
    assert camera.read_video_frame(timeout=0) is None
