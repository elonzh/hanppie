"""S1 App video control and H.264 decoding."""

from __future__ import annotations

import queue
import threading

from hanppie.lab.app import AppConnection


class LabCamera:
    def __init__(self, connection: AppConnection) -> None:
        self._connection = connection
        self._chunks: queue.Queue[bytes] = queue.Queue(maxsize=120)
        self._frames: queue.Queue[object] = queue.Queue(maxsize=1)
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._streaming = False
        self._connection.on("video", self._accept_chunk)

    def _accept_chunk(self, payload: object) -> None:
        if not isinstance(payload, bytes):
            return
        try:
            self._chunks.put_nowait(payload)
        except queue.Full:
            self._clear(self._chunks)
            self._chunks.put_nowait(payload)

    def start_video_stream(self, *, display: bool = False, resolution: str = "720p") -> bool:
        del display
        resolution_payloads = {
            "720p": bytes.fromhex("0403000000"),
            "720": bytes.fromhex("0403000000"),
            "1080p": bytes.fromhex("0a03000000"),
            "1080": bytes.fromhex("0a03000000"),
        }
        key = resolution.lower()
        if key not in resolution_payloads:
            raise ValueError("resolution must be 720p or 1080p")
        self._clear(self._chunks)
        self._clear(self._frames)
        self._connection.send_duss(0x02, 0x01, 0x40, 0x02, 0x18, resolution_payloads[key])
        self._stream_control(1, 1, 0)
        self._stream_control(2, 1, 0)
        if self._thread is None or not self._thread.is_alive():
            self._stop.clear()
            self._thread = threading.Thread(
                target=self._decode_loop, name="hanppie-h264", daemon=True
            )
            self._thread.start()
        self._streaming = True
        return True

    def stop_video_stream(self) -> bool:
        try:
            if self._streaming:
                self._stream_control(2, 0, 0)
                self._stream_control(1, 0, 0)
        finally:
            self._streaming = False
            self._stop.set()
            thread = self._thread
            self._thread = None
            if thread is not None:
                thread.join(timeout=1.0)
        return True

    def close(self) -> None:
        try:
            self.stop_video_stream()
        except (OSError, RuntimeError):
            pass

    def _stream_control(self, control: int, state: int, resolution: int) -> None:
        self._connection.send_duss(
            0x02,
            0x01,
            0x40,
            0x3F,
            0xD2,
            bytes((control & 0xFF, state & 0x0F, resolution & 0xFF)),
        )

    def read_video_frame(self, *, timeout: float = 3.0, strategy: str = "pipeline"):
        if strategy not in {"pipeline", "newest"}:
            raise ValueError("strategy must be pipeline or newest")
        try:
            frame = self._frames.get(timeout=max(0.0, timeout))
        except queue.Empty:
            return None
        if strategy == "newest":
            while True:
                try:
                    frame = self._frames.get_nowait()
                except queue.Empty:
                    break
        return frame

    def _decode_loop(self) -> None:
        import av

        decoder = av.CodecContext.create("h264", "r")
        while not self._stop.is_set():
            try:
                chunk = self._chunks.get(timeout=0.1)
            except queue.Empty:
                continue
            try:
                for packet in decoder.parse(chunk):
                    for frame in decoder.decode(packet):
                        self._put_latest(self._frames, frame)
            except Exception:
                decoder = av.CodecContext.create("h264", "r")

    @staticmethod
    def _put_latest(target: queue.Queue[object], value: object) -> None:
        try:
            target.put_nowait(value)
        except queue.Full:
            try:
                target.get_nowait()
            except queue.Empty:
                pass
            target.put_nowait(value)

    @staticmethod
    def _clear(target: queue.Queue[object]) -> None:
        while True:
            try:
                target.get_nowait()
            except queue.Empty:
                return
